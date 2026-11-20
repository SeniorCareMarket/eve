/* eve's GC hook on the BEAM (clojerl; adapters/gc/beam, eve.gc-nif): a resource
   term whose destructor records its id. A read's cell holds the term; when the
   process's GC drops the last reference to it the BEAM runs box_dtor, which
   only appends the id under a mutex — eve.gc's sweep! takes the ids
   (take-collected) and runs those releases in the reading process. A library
   of its own, beside eve_clje_nif: if it does not build or load, eve.gc
   reports no hook and eve.view reads in C. */
#include <erl_nif.h>

typedef struct { long id; } box_t;

static ErlNifResourceType *BOX;
static ErlNifMutex *MU;
static long *ids;
static size_t n_ids, cap_ids;

static void box_dtor(ErlNifEnv *env, void *obj) {
  box_t *b = (box_t *)obj;
  (void)env;
  enif_mutex_lock(MU);
  if (n_ids == cap_ids) {
    size_t nc = cap_ids ? cap_ids * 2 : 64;
    long *x = (long *)enif_realloc(ids, nc * sizeof(long));
    if (!x) { enif_mutex_unlock(MU); return; }
    ids = x;
    cap_ids = nc;
  }
  ids[n_ids++] = b->id;
  enif_mutex_unlock(MU);
}

static int load(ErlNifEnv *env, void **priv, ERL_NIF_TERM info) {
  (void)priv; (void)info;
  BOX = enif_open_resource_type(env, NULL, "eve_gc_box", box_dtor, ERL_NIF_RT_CREATE, NULL);
  MU = enif_mutex_create("eve_gc");
  return (BOX && MU) ? 0 : 1;
}

static ERL_NIF_TERM nif_new_box(ErlNifEnv *env, int argc, const ERL_NIF_TERM argv[]) {
  long id;
  box_t *b;
  ERL_NIF_TERM t;
  if (argc != 1 || !enif_get_long(env, argv[0], &id)) return enif_make_badarg(env);
  b = (box_t *)enif_alloc_resource(BOX, sizeof(box_t));
  if (!b) return enif_make_badarg(env);
  b->id = id;
  t = enif_make_resource(env, b);
  enif_release_resource(b);
  return t;
}

static ERL_NIF_TERM nif_take_collected(ErlNifEnv *env, int argc, const ERL_NIF_TERM argv[]) {
  ERL_NIF_TERM l = enif_make_list(env, 0);
  size_t i;
  (void)argc; (void)argv;
  enif_mutex_lock(MU);
  for (i = 0; i < n_ids; i++) l = enif_make_list_cell(env, enif_make_long(env, ids[i]), l);
  n_ids = 0;
  enif_mutex_unlock(MU);
  return l;
}

static ErlNifFunc funcs[] = {
  {"new-box", 1, nif_new_box, 0},
  {"take-collected", 0, nif_take_collected, 0},
};

ERL_NIF_INIT(eve.gc-nif, funcs, load, NULL, NULL, NULL)
