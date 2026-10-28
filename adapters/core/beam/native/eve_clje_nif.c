/* eve_clje_nif.c — eve's memory on clojerl (the BEAM): the NIF behind the
 * namespace eve.clje-nif (src/eve/clje_nif.clje), which clojerl compiles to
 * the Erlang module 'eve.clje-nif'. The BEAM has no mutable byte memory, no
 * mmap and no futex; this is the whole of what eve needs of them:
 *
 *   - a region: a MAP_SHARED file mapping (open/2) or zeroed memory from the
 *     BEAM's allocator (heap/1), with seq-cst i32/i64 loads, stores, CAS,
 *     fetch-add and exchange (__atomic), byte loads and stores, bulk copies
 *     to and from binaries and between regions, fcntl byte-range locks, and
 *     futex wait/wake (shared, so they work across processes on a file).
 *     eve's heap regions, and the host's byte, int and long arrays, are heap
 *     regions;
 *   - a term array (tarray/1): n slots each holding a copy of an Erlang term —
 *     the host's object array. A slot is set under a mutex and read as a copy
 *     into the caller's process.
 *
 * Both are resources: garbage collected with the last term that refers to
 * them, and shared by every process of the node. Offsets are byte offsets;
 * i32 loads and stores accept any offset, the read-modify-write ops require
 * natural alignment (badarg otherwise). The model is eve's Node addon
 * (mmap_cas.cc) and the clojerl probe's NIF (impls/clojerl/c_src).
 *
 * Built on first use by eve.clje-nif (gcc, the BEAM's erl_nif.h), into
 * target/clje/; by hand:
 *   gcc -O2 -fPIC -shared -I"$(erl -noshell -eval \
 *     'io:format("~s/usr/include",[code:root_dir()]),halt().')" \
 *     -o eve_clje_nif.so eve_clje_nif.c
 * (on macOS with -undefined dynamic_lookup: eve.clje-nif's build! adds it)
 */
#include <erl_nif.h>

#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#ifdef __linux__
#include <linux/futex.h>
#include <sys/syscall.h>
#endif
#include <stdint.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

typedef struct {
  uint8_t *base;
  size_t len;
  int fd;      /* the file's descriptor, -1 for heap memory */
  int mapped;  /* 1: munmap on release; 0: enif_free */
} region_t;

typedef struct {
  ErlNifEnv *env;  /* holds the slot's terms */
  ERL_NIF_TERM *slots;
  size_t n;
  ErlNifMutex *mu;
} tarray_t;

static ErlNifResourceType *REGION, *TARRAY;
static ERL_NIF_TERM A_OK, A_BUSY, A_NOT_EQUAL, A_TIMED_OUT, A_ERROR, A_NIL;

static void region_dtor(ErlNifEnv *env, void *obj) {
  region_t *r = (region_t *)obj;
  (void)env;
  if (r->mapped) {
    if (r->base && r->base != MAP_FAILED) munmap(r->base, r->len);
  } else if (r->base) {
    enif_free(r->base);
  }
  if (r->fd >= 0) close(r->fd);
}

static void tarray_dtor(ErlNifEnv *env, void *obj) {
  tarray_t *a = (tarray_t *)obj;
  (void)env;
  if (a->env) enif_free_env(a->env);
  if (a->slots) enif_free(a->slots);
  if (a->mu) enif_mutex_destroy(a->mu);
}

static int load(ErlNifEnv *env, void **priv, ERL_NIF_TERM info) {
  (void)priv; (void)info;
  REGION = enif_open_resource_type(env, NULL, "eve_region", region_dtor,
                                   ERL_NIF_RT_CREATE, NULL);
  TARRAY = enif_open_resource_type(env, NULL, "eve_tarray", tarray_dtor,
                                   ERL_NIF_RT_CREATE, NULL);
  A_OK = enif_make_atom(env, "ok");
  A_BUSY = enif_make_atom(env, "busy");
  A_NOT_EQUAL = enif_make_atom(env, "not-equal");
  A_TIMED_OUT = enif_make_atom(env, "timed-out");
  A_ERROR = enif_make_atom(env, "error");
  A_NIL = enif_make_atom(env, "undefined");  /* clojerl's nil */
  return (REGION && TARRAY) ? 0 : 1;
}

static ERL_NIF_TERM err(ErlNifEnv *env, const char *what) {
  return enif_make_tuple3(env, A_ERROR, enif_make_atom(env, what),
                          enif_make_int(env, errno));
}

static ERL_NIF_TERM make_region(ErlNifEnv *env, uint8_t *base, size_t len, int fd, int mapped) {
  region_t *r = enif_alloc_resource(REGION, sizeof(region_t));
  ERL_NIF_TERM t;
  r->base = base; r->len = len; r->fd = fd; r->mapped = mapped;
  t = enif_make_resource(env, r);
  enif_release_resource(r);
  return t;
}

/* zeroed heap memory, 16-aligned (enif_alloc's 8 suffices for i64 atomics) */
static uint8_t *heap_bytes(size_t n) {
  uint8_t *p = enif_alloc(n ? n : 1);
  if (p) memset(p, 0, n ? n : 1);
  return p;
}

/* get region + offset, check [off, off+w) is inside and off % align == 0 */
static int reg_off(ErlNifEnv *env, const ERL_NIF_TERM argv[], size_t w,
                   size_t align, region_t **r, uint8_t **p) {
  ErlNifUInt64 off;
  if (!enif_get_resource(env, argv[0], REGION, (void **)r)) return 0;
  if (!enif_get_uint64(env, argv[1], &off)) return 0;
  if (off > (*r)->len || w > (*r)->len - off) return 0;
  if (align > 1 && (((uintptr_t)((*r)->base + off)) % align) != 0) return 0;
  *p = (*r)->base + off;
  return 1;
}

/* (open path size) -> region | {error, what, errno}
 * create if missing; grow sparse if shorter than size; never truncate. */
static ERL_NIF_TERM nif_open(ErlNifEnv *env, int argc, const ERL_NIF_TERM argv[]) {
  ErlNifBinary path;
  ErlNifUInt64 size;
  char buf[PATH_MAX];
  struct stat st;
  (void)argc;
  if (!enif_inspect_iolist_as_binary(env, argv[0], &path) || path.size >= PATH_MAX)
    return enif_make_badarg(env);
  if (!enif_get_uint64(env, argv[1], &size) || size == 0) return enif_make_badarg(env);
  memcpy(buf, path.data, path.size);
  buf[path.size] = 0;
  int fd = open(buf, O_RDWR | O_CREAT, 0644);
  if (fd < 0) return err(env, "open");
  if (fstat(fd, &st) != 0) { close(fd); return err(env, "fstat"); }
  if ((ErlNifUInt64)st.st_size < size && ftruncate(fd, (off_t)size) != 0) {
    close(fd); return err(env, "ftruncate");
  }
  void *m = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
  if (m == MAP_FAILED) { close(fd); return err(env, "mmap"); }
  return make_region(env, (uint8_t *)m, size, fd, 1);
}

/* (heap size) -> region of size zeroed bytes */
static ERL_NIF_TERM nif_heap(ErlNifEnv *env, int argc, const ERL_NIF_TERM argv[]) {
  ErlNifUInt64 size; uint8_t *p; (void)argc;
  if (!enif_get_uint64(env, argv[0], &size)) return enif_make_badarg(env);
  if (!(p = heap_bytes(size))) return err(env, "alloc");
  return make_region(env, p, size, -1, 0);
}

/* (from-binary bin) -> a heap region holding bin's bytes */
static ERL_NIF_TERM nif_from_binary(ErlNifEnv *env, int argc, const ERL_NIF_TERM argv[]) {
  ErlNifBinary b; uint8_t *p; (void)argc;
  if (!enif_inspect_iolist_as_binary(env, argv[0], &b)) return enif_make_badarg(env);
  if (!(p = heap_bytes(b.size))) return err(env, "alloc");
  memcpy(p, b.data, b.size);
  return make_region(env, p, b.size, -1, 0);
}

/* (slice r off len) -> a heap region holding a copy of [off, off+len) */
static ERL_NIF_TERM nif_slice(ErlNifEnv *env, int argc, const ERL_NIF_TERM argv[]) {
  region_t *r; uint8_t *src, *p; ErlNifUInt64 n; (void)argc;
  if (!enif_get_uint64(env, argv[2], &n) || !reg_off(env, argv, n, 1, &r, &src))
    return enif_make_badarg(env);
  if (!(p = heap_bytes(n))) return err(env, "alloc");
  memcpy(p, src, n);
  return make_region(env, p, n, -1, 0);
}

/* (copy! src soff dst doff len) -> ok (memmove: the ranges may overlap) */
static ERL_NIF_TERM nif_copy(ErlNifEnv *env, int argc, const ERL_NIF_TERM argv[]) {
  region_t *s, *d; uint8_t *sp, *dp; ErlNifUInt64 n; (void)argc;
  if (!enif_get_uint64(env, argv[4], &n) || !reg_off(env, argv, n, 1, &s, &sp) ||
      !reg_off(env, argv + 2, n, 1, &d, &dp))
    return enif_make_badarg(env);
  memmove(dp, sp, n);
  return A_OK;
}

static ERL_NIF_TERM nif_byte_length(ErlNifEnv *env, int argc, const ERL_NIF_TERM argv[]) {
  region_t *r; (void)argc;
  if (!enif_get_resource(env, argv[0], REGION, (void **)&r)) return enif_make_badarg(env);
  return enif_make_uint64(env, r->len);
}

/* ---- bytes (signed, as a JVM byte array's) ---- */
static ERL_NIF_TERM nif_load_i8(ErlNifEnv *env, int argc, const ERL_NIF_TERM argv[]) {
  region_t *r; uint8_t *p; (void)argc;
  if (!reg_off(env, argv, 1, 1, &r, &p)) return enif_make_badarg(env);
  return enif_make_int(env, (int8_t)*p);
}

static ERL_NIF_TERM nif_store_i8(ErlNifEnv *env, int argc, const ERL_NIF_TERM argv[]) {
  region_t *r; uint8_t *p; ErlNifSInt64 v; (void)argc;
  if (!reg_off(env, argv, 1, 1, &r, &p) || !enif_get_int64(env, argv[2], &v))
    return enif_make_badarg(env);
  *p = (uint8_t)(uint64_t)v; /* low 8 bits */
  return A_OK;
}

/* ---- i16 (signed) and f32, plain, at any offset: the host's short and
 * float arrays ---- */
static ERL_NIF_TERM nif_load_i16(ErlNifEnv *env, int argc, const ERL_NIF_TERM argv[]) {
  region_t *r; uint8_t *p; int16_t v; (void)argc;
  if (!reg_off(env, argv, 2, 1, &r, &p)) return enif_make_badarg(env);
  memcpy(&v, p, 2);
  return enif_make_int(env, v);
}

static ERL_NIF_TERM nif_store_i16(ErlNifEnv *env, int argc, const ERL_NIF_TERM argv[]) {
  region_t *r; uint8_t *p; ErlNifSInt64 v; uint16_t u; (void)argc;
  if (!reg_off(env, argv, 2, 1, &r, &p) || !enif_get_int64(env, argv[2], &v))
    return enif_make_badarg(env);
  u = (uint16_t)(uint64_t)v; /* low 16 bits */
  memcpy(p, &u, 2);
  return A_OK;
}

static ERL_NIF_TERM nif_load_f32(ErlNifEnv *env, int argc, const ERL_NIF_TERM argv[]) {
  region_t *r; uint8_t *p; float f; (void)argc;
  if (!reg_off(env, argv, 4, 1, &r, &p)) return enif_make_badarg(env);
  memcpy(&f, p, 4);
  return enif_make_double(env, (double)f);
}

static ERL_NIF_TERM nif_store_f32(ErlNifEnv *env, int argc, const ERL_NIF_TERM argv[]) {
  region_t *r; uint8_t *p; double d; ErlNifSInt64 i; float f; (void)argc;
  if (!reg_off(env, argv, 4, 1, &r, &p)) return enif_make_badarg(env);
  if (!enif_get_double(env, argv[2], &d)) {
    if (!enif_get_int64(env, argv[2], &i)) return enif_make_badarg(env);
    d = (double)i;
  }
  f = (float)d;
  memcpy(p, &f, 4);
  return A_OK;
}

/* ---- i32: load/store at ANY offset (aligned -> seq-cst atomic) ---- */
static ERL_NIF_TERM nif_load_i32(ErlNifEnv *env, int argc, const ERL_NIF_TERM argv[]) {
  region_t *r; uint8_t *p; int32_t v; (void)argc;
  if (!reg_off(env, argv, 4, 1, &r, &p)) return enif_make_badarg(env);
  if (((uintptr_t)p & 3) == 0) v = __atomic_load_n((int32_t *)p, __ATOMIC_SEQ_CST);
  else memcpy(&v, p, 4);
  return enif_make_int(env, v);
}

static ERL_NIF_TERM nif_store_i32(ErlNifEnv *env, int argc, const ERL_NIF_TERM argv[]) {
  region_t *r; uint8_t *p; ErlNifSInt64 v64; int32_t v; (void)argc;
  if (!reg_off(env, argv, 4, 1, &r, &p) || !enif_get_int64(env, argv[2], &v64))
    return enif_make_badarg(env);
  v = (int32_t)(uint32_t)(uint64_t)v64; /* low 32 bits */
  if (((uintptr_t)p & 3) == 0) __atomic_store_n((int32_t *)p, v, __ATOMIC_SEQ_CST);
  else memcpy(p, &v, 4);
  return A_OK;
}

/* (cas-i32! r off expected desired) -> witness (the value found) */
static ERL_NIF_TERM nif_cas_i32(ErlNifEnv *env, int argc, const ERL_NIF_TERM argv[]) {
  region_t *r; uint8_t *p; ErlNifSInt64 e64, d64; int32_t e, d; (void)argc;
  if (!reg_off(env, argv, 4, 4, &r, &p) || !enif_get_int64(env, argv[2], &e64) ||
      !enif_get_int64(env, argv[3], &d64))
    return enif_make_badarg(env);
  e = (int32_t)(uint32_t)(uint64_t)e64; d = (int32_t)(uint32_t)(uint64_t)d64;
  __atomic_compare_exchange_n((int32_t *)p, &e, d, 0, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST);
  return enif_make_int(env, e); /* e now holds the witness */
}

/* (add-i32! r off delta) -> old, wrapping */
static ERL_NIF_TERM nif_add_i32(ErlNifEnv *env, int argc, const ERL_NIF_TERM argv[]) {
  region_t *r; uint8_t *p; ErlNifSInt64 d64; (void)argc;
  if (!reg_off(env, argv, 4, 4, &r, &p) || !enif_get_int64(env, argv[2], &d64))
    return enif_make_badarg(env);
  uint32_t old = __atomic_fetch_add((uint32_t *)p, (uint32_t)(uint64_t)d64, __ATOMIC_SEQ_CST);
  return enif_make_int(env, (int32_t)old);
}

static ERL_NIF_TERM nif_exchange_i32(ErlNifEnv *env, int argc, const ERL_NIF_TERM argv[]) {
  region_t *r; uint8_t *p; ErlNifSInt64 v64; (void)argc;
  if (!reg_off(env, argv, 4, 4, &r, &p) || !enif_get_int64(env, argv[2], &v64))
    return enif_make_badarg(env);
  int32_t old = __atomic_exchange_n((int32_t *)p, (int32_t)(uint32_t)(uint64_t)v64, __ATOMIC_SEQ_CST);
  return enif_make_int(env, old);
}

/* ---- i64: load/store at ANY offset (aligned -> atomic), RMW 8-aligned ---- */
static ERL_NIF_TERM nif_load_i64(ErlNifEnv *env, int argc, const ERL_NIF_TERM argv[]) {
  region_t *r; uint8_t *p; int64_t v; (void)argc;
  if (!reg_off(env, argv, 8, 1, &r, &p)) return enif_make_badarg(env);
  if (((uintptr_t)p & 7) == 0) v = __atomic_load_n((int64_t *)p, __ATOMIC_SEQ_CST);
  else memcpy(&v, p, 8);
  return enif_make_int64(env, v);
}

/* the low 64 bits of an integer term of any size (a negative one as two's
 * complement): the BEAM's integers are unbounded, eve's longs wrap */
static int get_low64(ErlNifEnv *env, ERL_NIF_TERM t, uint64_t *out) {
  ErlNifSInt64 s; ErlNifUInt64 u;
  if (enif_get_int64(env, t, &s)) { *out = (uint64_t)s; return 1; }
  if (enif_get_uint64(env, t, &u)) { *out = (uint64_t)u; return 1; }
  return 0;
}

static ERL_NIF_TERM nif_store_i64(ErlNifEnv *env, int argc, const ERL_NIF_TERM argv[]) {
  region_t *r; uint8_t *p; uint64_t v; (void)argc;
  if (!reg_off(env, argv, 8, 1, &r, &p) || !get_low64(env, argv[2], &v))
    return enif_make_badarg(env);
  if (((uintptr_t)p & 7) == 0) __atomic_store_n((uint64_t *)p, v, __ATOMIC_SEQ_CST);
  else memcpy(p, &v, 8);
  return A_OK;
}

static ERL_NIF_TERM nif_cas_i64(ErlNifEnv *env, int argc, const ERL_NIF_TERM argv[]) {
  region_t *r; uint8_t *p; uint64_t e, d; (void)argc;
  if (!reg_off(env, argv, 8, 8, &r, &p) || !get_low64(env, argv[2], &e) ||
      !get_low64(env, argv[3], &d))
    return enif_make_badarg(env);
  __atomic_compare_exchange_n((uint64_t *)p, &e, d, 0, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST);
  return enif_make_int64(env, (int64_t)e);
}

static ERL_NIF_TERM nif_add_i64(ErlNifEnv *env, int argc, const ERL_NIF_TERM argv[]) {
  region_t *r; uint8_t *p; uint64_t d; (void)argc;
  if (!reg_off(env, argv, 8, 8, &r, &p) || !get_low64(env, argv[2], &d))
    return enif_make_badarg(env);
  uint64_t old = __atomic_fetch_add((uint64_t *)p, d, __ATOMIC_SEQ_CST);
  return enif_make_int64(env, (int64_t)old);
}

/* ---- doubles: IEEE-754 binary64 at any offset (plain, not atomic) ---- */
static ERL_NIF_TERM nif_load_f64(ErlNifEnv *env, int argc, const ERL_NIF_TERM argv[]) {
  region_t *r; uint8_t *p; double d; (void)argc;
  if (!reg_off(env, argv, 8, 1, &r, &p)) return enif_make_badarg(env);
  memcpy(&d, p, 8);
  return enif_make_double(env, d);  /* badarg for NaN/inf: the BEAM has neither */
}

static ERL_NIF_TERM nif_store_f64(ErlNifEnv *env, int argc, const ERL_NIF_TERM argv[]) {
  region_t *r; uint8_t *p; double d; ErlNifSInt64 i; (void)argc;
  if (!reg_off(env, argv, 8, 1, &r, &p)) return enif_make_badarg(env);
  if (!enif_get_double(env, argv[2], &d)) {
    if (!enif_get_int64(env, argv[2], &i)) return enif_make_badarg(env);
    d = (double)i;
  }
  memcpy(p, &d, 8);
  return A_OK;
}

/* (double-bits d) -> the IEEE-754 bits of d as a signed 64-bit integer */
static ERL_NIF_TERM nif_double_bits(ErlNifEnv *env, int argc, const ERL_NIF_TERM argv[]) {
  double d; ErlNifSInt64 i; int64_t b; (void)argc;
  if (!enif_get_double(env, argv[0], &d)) {
    if (!enif_get_int64(env, argv[0], &i)) return enif_make_badarg(env);
    d = (double)i;
  }
  memcpy(&b, &d, 8);
  return enif_make_int64(env, b);
}

/* (bits-double b) -> the double whose IEEE-754 bits are b (badarg when they
 * are a NaN or an infinity: the BEAM's floats are finite) */
static ERL_NIF_TERM nif_bits_double(ErlNifEnv *env, int argc, const ERL_NIF_TERM argv[]) {
  uint64_t b; double d; (void)argc;
  if (!get_low64(env, argv[0], &b)) return enif_make_badarg(env);
  memcpy(&d, &b, 8);
  return enif_make_double(env, d);
}

/* ---- bulk bytes to and from binaries ---- */
static ERL_NIF_TERM nif_read_bytes(ErlNifEnv *env, int argc, const ERL_NIF_TERM argv[]) {
  region_t *r; uint8_t *p; ErlNifUInt64 n; ERL_NIF_TERM t; (void)argc;
  if (!enif_get_uint64(env, argv[2], &n) || !reg_off(env, argv, n, 1, &r, &p))
    return enif_make_badarg(env);
  memcpy(enif_make_new_binary(env, n, &t), p, n);
  return t;
}

static ERL_NIF_TERM nif_write_bytes(ErlNifEnv *env, int argc, const ERL_NIF_TERM argv[]) {
  region_t *r; uint8_t *p; ErlNifBinary b; (void)argc;
  if (!enif_inspect_iolist_as_binary(env, argv[2], &b) || !reg_off(env, argv, b.size, 1, &r, &p))
    return enif_make_badarg(env);
  memcpy(p, b.data, b.size);
  return A_OK;
}

/* ---- fcntl byte-range lock (F_SETLK, non-blocking): ok | busy ---- */
static ERL_NIF_TERM do_lock(ErlNifEnv *env, const ERL_NIF_TERM argv[], short type) {
  region_t *r; ErlNifUInt64 off, len; struct flock fl;
  if (!enif_get_resource(env, argv[0], REGION, (void **)&r) || r->fd < 0 ||
      !enif_get_uint64(env, argv[1], &off) || !enif_get_uint64(env, argv[2], &len))
    return enif_make_badarg(env);
  memset(&fl, 0, sizeof fl);
  fl.l_type = type; fl.l_whence = SEEK_SET; fl.l_start = (off_t)off; fl.l_len = (off_t)len;
  if (fcntl(r->fd, F_SETLK, &fl) == 0) return A_OK;
  if (errno == EACCES || errno == EAGAIN) return A_BUSY;
  return err(env, "fcntl");
}
static ERL_NIF_TERM nif_lock(ErlNifEnv *env, int argc, const ERL_NIF_TERM argv[]) {
  (void)argc; return do_lock(env, argv, F_WRLCK);
}
static ERL_NIF_TERM nif_unlock(ErlNifEnv *env, int argc, const ERL_NIF_TERM argv[]) {
  (void)argc; return do_lock(env, argv, F_UNLCK);
}

/* ---- futex (shared, not _PRIVATE: works across processes on MAP_SHARED) ----
 * wait runs on a dirty I/O scheduler so a blocked waiter never stalls a
 * normal BEAM scheduler. -> ok | not-equal | timed-out. Outside Linux there is
 * no futex: wait polls every 100 us (as eve's Node addon does there) and
 * notify wakes no one — a poller sees the new value on its next look. */
static ERL_NIF_TERM nif_wait_i32(ErlNifEnv *env, int argc, const ERL_NIF_TERM argv[]) {
  region_t *r; uint8_t *p; ErlNifSInt64 e64, ms; struct timespec ts; (void)argc;
  if (!reg_off(env, argv, 4, 4, &r, &p) || !enif_get_int64(env, argv[2], &e64) ||
      !enif_get_int64(env, argv[3], &ms))
    return enif_make_badarg(env);
#ifdef __linux__
  ts.tv_sec = ms / 1000; ts.tv_nsec = (ms % 1000) * 1000000L;
  long rc = syscall(SYS_futex, (int32_t *)p, FUTEX_WAIT, (int32_t)(uint32_t)(uint64_t)e64,
                    &ts, NULL, 0);
  if (rc == 0) return A_OK;
  if (errno == EAGAIN) return A_NOT_EQUAL;
  if (errno == ETIMEDOUT) return A_TIMED_OUT;
  if (errno == EINTR) return A_OK; /* spurious: caller re-checks */
  return err(env, "futex");
#else
  int32_t exp = (int32_t)(uint32_t)(uint64_t)e64;
  if (__atomic_load_n((int32_t *)p, __ATOMIC_SEQ_CST) != exp) return A_NOT_EQUAL;
  struct timespec now, step = {0, 100000L};
  clock_gettime(CLOCK_MONOTONIC, &ts);
  int64_t deadline = ts.tv_sec * 1000000000LL + ts.tv_nsec + ms * 1000000LL;
  for (;;) {
    if (__atomic_load_n((int32_t *)p, __ATOMIC_SEQ_CST) != exp) return A_OK;
    clock_gettime(CLOCK_MONOTONIC, &now);
    if (ms >= 0 && now.tv_sec * 1000000000LL + now.tv_nsec >= deadline) return A_TIMED_OUT;
    nanosleep(&step, NULL);
  }
#endif
}

static ERL_NIF_TERM nif_notify_i32(ErlNifEnv *env, int argc, const ERL_NIF_TERM argv[]) {
  region_t *r; uint8_t *p; ErlNifSInt64 n; (void)argc;
  if (!reg_off(env, argv, 4, 4, &r, &p) || !enif_get_int64(env, argv[2], &n))
    return enif_make_badarg(env);
  if (n > INT_MAX) n = INT_MAX;
#ifdef __linux__
  long rc = syscall(SYS_futex, (int32_t *)p, FUTEX_WAKE, (int)n, NULL, NULL, 0);
#else
  long rc = 0; (void)p;
#endif
  return enif_make_long(env, rc);
}

/* ---- term arrays (the host's object arrays) ---- */
static ERL_NIF_TERM nif_tarray(ErlNifEnv *env, int argc, const ERL_NIF_TERM argv[]) {
  ErlNifUInt64 n; tarray_t *a; ERL_NIF_TERM t; size_t i; (void)argc;
  if (!enif_get_uint64(env, argv[0], &n)) return enif_make_badarg(env);
  a = enif_alloc_resource(TARRAY, sizeof(tarray_t));
  a->n = n;
  a->env = enif_alloc_env();
  a->slots = enif_alloc(sizeof(ERL_NIF_TERM) * (n ? n : 1));
  a->mu = enif_mutex_create("eve_tarray");
  for (i = 0; i < n; i++) a->slots[i] = A_NIL;
  t = enif_make_resource(env, a);
  enif_release_resource(a);
  return t;
}

static int tarray_ix(ErlNifEnv *env, const ERL_NIF_TERM argv[], tarray_t **a, size_t *i) {
  ErlNifUInt64 ix;
  if (!enif_get_resource(env, argv[0], TARRAY, (void **)a)) return 0;
  if (!enif_get_uint64(env, argv[1], &ix) || ix >= (*a)->n) return 0;
  *i = ix;
  return 1;
}

static ERL_NIF_TERM nif_tget(ErlNifEnv *env, int argc, const ERL_NIF_TERM argv[]) {
  tarray_t *a; size_t i; ERL_NIF_TERM t; (void)argc;
  if (!tarray_ix(env, argv, &a, &i)) return enif_make_badarg(env);
  enif_mutex_lock(a->mu);
  t = enif_make_copy(env, a->slots[i]);
  enif_mutex_unlock(a->mu);
  return t;
}

/* A slot's old term stays in the array's env until the array is collected
 * (an env frees only whole); the host's object arrays are small and few. */
static ERL_NIF_TERM nif_tset(ErlNifEnv *env, int argc, const ERL_NIF_TERM argv[]) {
  tarray_t *a; size_t i; (void)argc;
  if (!tarray_ix(env, argv, &a, &i)) return enif_make_badarg(env);
  enif_mutex_lock(a->mu);
  a->slots[i] = enif_make_copy(a->env, argv[2]);
  enif_mutex_unlock(a->mu);
  return A_OK;
}

static ERL_NIF_TERM nif_tlength(ErlNifEnv *env, int argc, const ERL_NIF_TERM argv[]) {
  tarray_t *a; (void)argc;
  if (!enif_get_resource(env, argv[0], TARRAY, (void **)&a)) return enif_make_badarg(env);
  return enif_make_uint64(env, a->n);
}

static ErlNifFunc funcs[] = {
  {"open", 2, nif_open, ERL_NIF_DIRTY_JOB_IO_BOUND},
  {"heap", 1, nif_heap, 0},
  {"from-binary", 1, nif_from_binary, 0},
  {"slice", 3, nif_slice, 0},
  {"copy!", 5, nif_copy, 0},
  {"byte-length", 1, nif_byte_length, 0},
  {"load-i8", 2, nif_load_i8, 0},
  {"store-i8!", 3, nif_store_i8, 0},
  {"load-i16", 2, nif_load_i16, 0},
  {"store-i16!", 3, nif_store_i16, 0},
  {"load-f32", 2, nif_load_f32, 0},
  {"store-f32!", 3, nif_store_f32, 0},
  {"load-i32", 2, nif_load_i32, 0},
  {"store-i32!", 3, nif_store_i32, 0},
  {"cas-i32!", 4, nif_cas_i32, 0},
  {"add-i32!", 3, nif_add_i32, 0},
  {"exchange-i32!", 3, nif_exchange_i32, 0},
  {"load-i64", 2, nif_load_i64, 0},
  {"store-i64!", 3, nif_store_i64, 0},
  {"cas-i64!", 4, nif_cas_i64, 0},
  {"add-i64!", 3, nif_add_i64, 0},
  {"load-f64", 2, nif_load_f64, 0},
  {"store-f64!", 3, nif_store_f64, 0},
  {"double-bits", 1, nif_double_bits, 0},
  {"bits-double", 1, nif_bits_double, 0},
  {"read-bytes", 3, nif_read_bytes, 0},
  {"write-bytes!", 3, nif_write_bytes, 0},
  {"lock!", 3, nif_lock, 0},
  {"unlock!", 3, nif_unlock, 0},
  {"wait-i32!", 4, nif_wait_i32, ERL_NIF_DIRTY_JOB_IO_BOUND},
  {"notify-i32!", 3, nif_notify_i32, 0},
  {"tarray", 1, nif_tarray, 0},
  {"tget", 2, nif_tget, 0},
  {"tset!", 3, nif_tset, 0},
  {"tlength", 1, nif_tlength, 0},
};

ERL_NIF_INIT(eve.clje-nif, funcs, load, NULL, NULL, NULL)
