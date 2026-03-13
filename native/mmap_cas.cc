// native/mmap_cas.cc
//
// Node.js native addon: file-backed MAP_SHARED mmap + atomic int32/int64 ops.
//
// Exposes to JavaScript:
//
//   open(path: string, size: number) -> Buffer
//     Map (or create) a file at path.  The file is grown to size bytes if
//     smaller.  Returns a Node.js Buffer whose backing memory IS the mmap'd
//     page — writes are immediately visible to any other process with the
//     same file mapped.  The mapping is released automatically when the
//     Buffer is GC'd (via the Deleter finaliser registered below).
//
//   load32(buf: Buffer, byteOffset: number) -> number
//   store32(buf: Buffer, byteOffset: number, val: number) -> undefined
//   cas32(buf: Buffer, byteOffset: number, expected: number, desired: number) -> number
//     Returns the OLD value.  Success iff (returned === expected).
//   add32(buf: Buffer, byteOffset: number, delta: number) -> number
//     Returns the OLD value.
//   sub32(buf: Buffer, byteOffset: number, delta: number) -> number
//     Returns the OLD value.
//   wait32(buf: Buffer, byteOffset: number, expected: number, timeoutMs: number) -> string
//     Returns "ok" | "not-equal" | "timed-out".
//     Uses Linux futex(2) on the mmap'd address directly.
//   notify32(buf: Buffer, byteOffset: number, count: number) -> number
//     Returns the number of threads woken.
//
// Build:
//   cd native && node-gyp configure build
//
// Requirements:
//   node-addon-api  (header-only, no link dep)
//   C++14 compiler (GCC 5+ / Clang 3.4+ / Apple Clang 6+)
//   Linux (futex syscall for wait/notify — Darwin uses ulock, stubbed below)
//
// Note: uses __atomic_* GCC/Clang built-ins instead of std::atomic_ref so
// that the code compiles on Apple Clang 14 (which lacks std::atomic_ref
// despite accepting -std=c++20).  The generated machine code is identical —
// std::atomic_ref is just a thin wrapper over the same builtins.

#include <napi.h>

#include <cstdint>
#include <cstring>

#include <errno.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#ifdef __linux__
#  include <linux/futex.h>
#  include <sys/syscall.h>
#  include <time.h>
// futex_wait: absolute-deadline FUTEX_WAIT_BITSET on the mapped address.
// Using an absolute CLOCK_MONOTONIC deadline (instead of a relative timeout)
// eliminates the race window between clock_gettime and the syscall — the same
// pattern used by GCC's libstdc++ since GCC 11.
// No FUTEX_PRIVATE_FLAG: cross-process futex on MAP_SHARED uses inode-based
// keying; FUTEX_PRIVATE_FLAG forces virtual-address keying (same process only)
// which would place Node and JVM in different buckets.
static long futex_wait(void* addr, int expected, long timeout_ns)
{
    if (timeout_ns < 0) {
        // Infinite wait
        return syscall(SYS_futex, addr, FUTEX_WAIT_BITSET, expected,
                       nullptr, nullptr, FUTEX_BITSET_MATCH_ANY);
    }
    struct timespec deadline{};
    clock_gettime(CLOCK_MONOTONIC, &deadline);
    deadline.tv_sec  += timeout_ns / 1'000'000'000L;
    deadline.tv_nsec += timeout_ns % 1'000'000'000L;
    if (deadline.tv_nsec >= 1'000'000'000L) {
        deadline.tv_sec++;
        deadline.tv_nsec -= 1'000'000'000L;
    }
    return syscall(SYS_futex, addr, FUTEX_WAIT_BITSET, expected,
                   &deadline, nullptr, FUTEX_BITSET_MATCH_ANY);
}
static long futex_wake(void* addr, int count)
{
    return syscall(SYS_futex, addr, FUTEX_WAKE, count, nullptr, nullptr, 0);
}
#else
// Stub for non-Linux: spin with a short sleep.
// A macOS port would use os_sync_wait_on_address (macOS 14+) or
// __ulock_wait / __ulock_wake (private API).
#  include <time.h>
static long futex_wait(void*, int, long) { return -1; }
static long futex_wake(void*, int)       { return  0; }
#endif

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

static std::int32_t* ptr32(Napi::Buffer<uint8_t> buf, uint32_t byte_off)
{
    return reinterpret_cast<std::int32_t*>(buf.Data() + byte_off);
}

static std::int64_t* ptr64(Napi::Buffer<uint8_t> buf, uint32_t byte_off)
{
    return reinterpret_cast<std::int64_t*>(buf.Data() + byte_off);
}

// ---------------------------------------------------------------------------
// open(path, sizeBytes) -> Buffer
// ---------------------------------------------------------------------------

struct MmapDeleter {
    size_t size;
    static void Finalize(Napi::Env /*env*/, uint8_t* ptr, MmapDeleter* hint) {
        ::munmap(ptr, hint->size);
        delete hint;
    }
};

static Napi::Value Open(const Napi::CallbackInfo& info)
{
    auto env  = info.Env();
    auto path = info[0].As<Napi::String>().Utf8Value();
    auto size = static_cast<size_t>(info[1].As<Napi::Number>().Uint32Value());

    int fd = ::open(path.c_str(), O_RDWR | O_CREAT, 0600);
    if (fd < 0) {
        Napi::Error::New(env, std::string("open failed: ") + std::strerror(errno))
            .ThrowAsJavaScriptException();
        return env.Null();
    }

    // Grow to requested size if the file is smaller
    struct stat st{};
    ::fstat(fd, &st);
    if (static_cast<size_t>(st.st_size) < size) {
        if (::ftruncate(fd, static_cast<off_t>(size)) != 0) {
            ::close(fd);
            Napi::Error::New(env, std::string("ftruncate failed: ") + std::strerror(errno))
                .ThrowAsJavaScriptException();
            return env.Null();
        }
    }

    void* ptr = ::mmap(nullptr, size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    ::close(fd); // fd can be closed immediately after mmap
    if (ptr == MAP_FAILED) {
        Napi::Error::New(env, std::string("mmap failed: ") + std::strerror(errno))
            .ThrowAsJavaScriptException();
        return env.Null();
    }

    auto* deleter = new MmapDeleter{ size };
    return Napi::Buffer<uint8_t>::New(
        env,
        static_cast<uint8_t*>(ptr),
        size,
        MmapDeleter::Finalize,
        deleter);
}

// ---------------------------------------------------------------------------
// Atomic int32 operations
// ---------------------------------------------------------------------------

static Napi::Value Load32(const Napi::CallbackInfo& info)
{
    auto buf = info[0].As<Napi::Buffer<uint8_t>>();
    auto off = info[1].As<Napi::Number>().Uint32Value();
    return Napi::Number::New(info.Env(),
        __atomic_load_n(ptr32(buf, off), __ATOMIC_ACQUIRE));
}

static Napi::Value Store32(const Napi::CallbackInfo& info)
{
    auto buf = info[0].As<Napi::Buffer<uint8_t>>();
    auto off = info[1].As<Napi::Number>().Uint32Value();
    auto val = info[2].As<Napi::Number>().Int32Value();
    __atomic_store_n(ptr32(buf, off), val, __ATOMIC_RELEASE);
    return info.Env().Undefined();
}

// Returns OLD value.  JS caller checks (returned === expected) for success.
static Napi::Value Cas32(const Napi::CallbackInfo& info)
{
    auto buf      = info[0].As<Napi::Buffer<uint8_t>>();
    auto off      = info[1].As<Napi::Number>().Uint32Value();
    auto expected = info[2].As<Napi::Number>().Int32Value();
    auto desired  = info[3].As<Napi::Number>().Int32Value();
    // compare_exchange_strong writes the witnessed value back into `expected`
    // on failure; on success `expected` still holds the original value.
    // Either way `expected` is the old value — exactly what the caller needs.
    __atomic_compare_exchange_n(ptr32(buf, off), &expected, desired,
                                false, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST);
    return Napi::Number::New(info.Env(), expected);
}

static Napi::Value Add32(const Napi::CallbackInfo& info)
{
    auto buf   = info[0].As<Napi::Buffer<uint8_t>>();
    auto off   = info[1].As<Napi::Number>().Uint32Value();
    auto delta = info[2].As<Napi::Number>().Int32Value();
    auto old   = __atomic_fetch_add(ptr32(buf, off), delta, __ATOMIC_SEQ_CST);
    return Napi::Number::New(info.Env(), old);
}

static Napi::Value Sub32(const Napi::CallbackInfo& info)
{
    auto buf   = info[0].As<Napi::Buffer<uint8_t>>();
    auto off   = info[1].As<Napi::Number>().Uint32Value();
    auto delta = info[2].As<Napi::Number>().Int32Value();
    auto old   = __atomic_fetch_sub(ptr32(buf, off), delta, __ATOMIC_SEQ_CST);
    return Napi::Number::New(info.Env(), old);
}

// ---------------------------------------------------------------------------
// Atomic int64 operations
// ---------------------------------------------------------------------------
// Values are passed as JS Number (double).  This is lossless for values up to
// 2^53 (9 PB) — well beyond any practical single-machine capacity.  On JVM
// side, true 64-bit longs are used natively.

static Napi::Value Load64(const Napi::CallbackInfo& info)
{
    auto buf = info[0].As<Napi::Buffer<uint8_t>>();
    auto off = info[1].As<Napi::Number>().Uint32Value();
    auto val = __atomic_load_n(ptr64(buf, off), __ATOMIC_ACQUIRE);
    return Napi::Number::New(info.Env(), static_cast<double>(val));
}

static Napi::Value Store64(const Napi::CallbackInfo& info)
{
    auto buf = info[0].As<Napi::Buffer<uint8_t>>();
    auto off = info[1].As<Napi::Number>().Uint32Value();
    auto val = static_cast<std::int64_t>(info[2].As<Napi::Number>().Int64Value());
    __atomic_store_n(ptr64(buf, off), val, __ATOMIC_RELEASE);
    return info.Env().Undefined();
}

static Napi::Value Cas64(const Napi::CallbackInfo& info)
{
    auto buf      = info[0].As<Napi::Buffer<uint8_t>>();
    auto off      = info[1].As<Napi::Number>().Uint32Value();
    auto expected = static_cast<std::int64_t>(info[2].As<Napi::Number>().Int64Value());
    auto desired  = static_cast<std::int64_t>(info[3].As<Napi::Number>().Int64Value());
    __atomic_compare_exchange_n(ptr64(buf, off), &expected, desired,
                                false, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST);
    return Napi::Number::New(info.Env(), static_cast<double>(expected));
}

static Napi::Value Add64(const Napi::CallbackInfo& info)
{
    auto buf   = info[0].As<Napi::Buffer<uint8_t>>();
    auto off   = info[1].As<Napi::Number>().Uint32Value();
    auto delta = static_cast<std::int64_t>(info[2].As<Napi::Number>().Int64Value());
    auto old   = __atomic_fetch_add(ptr64(buf, off), delta, __ATOMIC_SEQ_CST);
    return Napi::Number::New(info.Env(), static_cast<double>(old));
}

static Napi::Value Sub64(const Napi::CallbackInfo& info)
{
    auto buf   = info[0].As<Napi::Buffer<uint8_t>>();
    auto off   = info[1].As<Napi::Number>().Uint32Value();
    auto delta = static_cast<std::int64_t>(info[2].As<Napi::Number>().Int64Value());
    auto old   = __atomic_fetch_sub(ptr64(buf, off), delta, __ATOMIC_SEQ_CST);
    return Napi::Number::New(info.Env(), static_cast<double>(old));
}

// ---------------------------------------------------------------------------
// Futex-based wait / notify
// ---------------------------------------------------------------------------

static Napi::Value Wait32(const Napi::CallbackInfo& info)
{
    auto env       = info.Env();
    auto buf       = info[0].As<Napi::Buffer<uint8_t>>();
    auto off       = info[1].As<Napi::Number>().Uint32Value();
    auto expected  = info[2].As<Napi::Number>().Int32Value();
    auto timeout_ms = info[3].As<Napi::Number>().Int64Value();

    void* addr = ptr32(buf, off);

    // Quick check: if the value already differs, return immediately.
    if (__atomic_load_n(ptr32(buf, off), __ATOMIC_ACQUIRE) != expected)
        return Napi::String::New(env, "not-equal");

#ifdef __linux__
    long timeout_ns = (timeout_ms < 0) ? -1 : timeout_ms * 1'000'000L;
    long ret = futex_wait(addr, expected, timeout_ns);
    if (ret == 0)
        return Napi::String::New(env, "ok");
    if (errno == EAGAIN)
        return Napi::String::New(env, "not-equal");
    if (errno == ETIMEDOUT)
        return Napi::String::New(env, "timed-out");
    // Other errors (EINTR etc.) — treat as timed-out
    return Napi::String::New(env, "timed-out");
#else
    // Non-Linux fallback: spin until value changes or deadline reached.
    auto deadline_ns = (timeout_ms < 0)
        ? INT64_MAX
        : ([]() -> int64_t {
               struct timespec ts{};
               clock_gettime(CLOCK_MONOTONIC, &ts);
               return ts.tv_sec * 1'000'000'000LL + ts.tv_nsec;
           }()) + timeout_ms * 1'000'000LL;
    while (true) {
        if (__atomic_load_n(ptr32(buf, off), __ATOMIC_ACQUIRE) != expected)
            return Napi::String::New(env, "ok");
        struct timespec now{};
        clock_gettime(CLOCK_MONOTONIC, &now);
        if (now.tv_sec * 1'000'000'000LL + now.tv_nsec >= deadline_ns)
            return Napi::String::New(env, "timed-out");
        struct timespec sleep_ts{ 0, 100'000 }; // 100 µs
        nanosleep(&sleep_ts, nullptr);
    }
#endif
}

static Napi::Value Notify32(const Napi::CallbackInfo& info)
{
    auto buf   = info[0].As<Napi::Buffer<uint8_t>>();
    auto off   = info[1].As<Napi::Number>().Uint32Value();
    auto count = info[2].As<Napi::Number>().Int32Value();

#ifdef __linux__
    long woken = futex_wake(ptr32(buf, off), count);
    return Napi::Number::New(info.Env(), woken < 0 ? 0 : static_cast<int>(woken));
#else
    (void)buf; (void)off; (void)count;
    return Napi::Number::New(info.Env(), 0);
#endif
}

// ---------------------------------------------------------------------------
// Lustre mode — fcntl byte-range locking for cross-node atomics
// ---------------------------------------------------------------------------
// On Lustre, hardware atomics (__atomic_*) only work within a single machine's
// cache-coherent memory domain. For cross-node atomics, we use POSIX fcntl
// byte-range locks via the Lustre LDLM (Distributed Lock Manager).
//
// Pattern: lock N bytes via F_SETLKW, pread, compare, pwrite, msync, unlock.
// This is a pessimistic lock-based CAS — slower than hardware CAS (~50-200us
// vs ~10ns) but correct across Lustre nodes.
//
// All Lustre clients must mount with -o flock (default since Lustre 2.13+).

// MmapFdDeleter: like MmapDeleter but also closes the kept-open fd.
struct MmapFdDeleter {
    size_t size;
    int fd;
    static void Finalize(Napi::Env /*env*/, uint8_t* ptr, MmapFdDeleter* hint) {
        ::munmap(ptr, hint->size);
        ::close(hint->fd);
        delete hint;
    }
};

// openWithFd(path, size) -> {buf: Buffer, fd: number}
// Like Open() but keeps the fd open for fcntl locking.
static Napi::Value OpenWithFd(const Napi::CallbackInfo& info)
{
    auto env  = info.Env();
    auto path = info[0].As<Napi::String>().Utf8Value();
    auto size = static_cast<size_t>(info[1].As<Napi::Number>().Uint32Value());

    int fd = ::open(path.c_str(), O_RDWR | O_CREAT, 0600);
    if (fd < 0) {
        Napi::Error::New(env, std::string("open failed: ") + std::strerror(errno))
            .ThrowAsJavaScriptException();
        return env.Null();
    }

    struct stat st{};
    ::fstat(fd, &st);
    if (static_cast<size_t>(st.st_size) < size) {
        if (::ftruncate(fd, static_cast<off_t>(size)) != 0) {
            ::close(fd);
            Napi::Error::New(env, std::string("ftruncate failed: ") + std::strerror(errno))
                .ThrowAsJavaScriptException();
            return env.Null();
        }
    }

    void* ptr = ::mmap(nullptr, size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    // Do NOT close fd — keep it open for fcntl byte-range locking
    if (ptr == MAP_FAILED) {
        ::close(fd);
        Napi::Error::New(env, std::string("mmap failed: ") + std::strerror(errno))
            .ThrowAsJavaScriptException();
        return env.Null();
    }

    auto* deleter = new MmapFdDeleter{ size, fd };
    auto buf = Napi::Buffer<uint8_t>::New(
        env,
        static_cast<uint8_t*>(ptr),
        size,
        MmapFdDeleter::Finalize,
        deleter);

    auto result = Napi::Object::New(env);
    result["buf"] = buf;
    result["fd"]  = Napi::Number::New(env, fd);
    return result;
}

// Helper: acquire exclusive fcntl lock on [offset, offset+len)
static int fcntl_lock(int fd, off_t offset, off_t len)
{
    struct flock fl{};
    fl.l_type   = F_WRLCK;
    fl.l_whence = SEEK_SET;
    fl.l_start  = offset;
    fl.l_len    = len;
    int ret;
    do {
        ret = ::fcntl(fd, F_SETLKW, &fl);
    } while (ret < 0 && errno == EINTR);
    return ret;
}

// Helper: release fcntl lock on [offset, offset+len)
static int fcntl_unlock(int fd, off_t offset, off_t len)
{
    struct flock fl{};
    fl.l_type   = F_UNLCK;
    fl.l_whence = SEEK_SET;
    fl.l_start  = offset;
    fl.l_len    = len;
    return ::fcntl(fd, F_SETLK, &fl);
}

// fcntlCas32(fd, buf, byteOffset, expected, desired) -> old value
static Napi::Value FcntlCas32(const Napi::CallbackInfo& info)
{
    auto env      = info.Env();
    auto fd       = info[0].As<Napi::Number>().Int32Value();
    auto buf      = info[1].As<Napi::Buffer<uint8_t>>();
    auto off      = info[2].As<Napi::Number>().Uint32Value();
    auto expected = info[3].As<Napi::Number>().Int32Value();
    auto desired  = info[4].As<Napi::Number>().Int32Value();

    if (fcntl_lock(fd, off, 4) < 0) {
        Napi::Error::New(env, std::string("fcntl lock failed: ") + std::strerror(errno))
            .ThrowAsJavaScriptException();
        return env.Null();
    }

    auto* p = reinterpret_cast<std::int32_t*>(buf.Data() + off);
    std::int32_t old;
    ::memcpy(&old, p, 4);

    if (old == expected) {
        ::memcpy(p, &desired, 4);
        ::msync(buf.Data() + (off & ~(4095UL)), 4096, MS_SYNC);
    }

    fcntl_unlock(fd, off, 4);
    return Napi::Number::New(env, old);
}

// fcntlLoad32(fd, buf, byteOffset) -> value
static Napi::Value FcntlLoad32(const Napi::CallbackInfo& info)
{
    auto env = info.Env();
    auto fd  = info[0].As<Napi::Number>().Int32Value();
    auto buf = info[1].As<Napi::Buffer<uint8_t>>();
    auto off = info[2].As<Napi::Number>().Uint32Value();

    if (fcntl_lock(fd, off, 4) < 0) {
        Napi::Error::New(env, std::string("fcntl lock failed: ") + std::strerror(errno))
            .ThrowAsJavaScriptException();
        return env.Null();
    }

    auto* p = reinterpret_cast<std::int32_t*>(buf.Data() + off);
    std::int32_t val;
    ::memcpy(&val, p, 4);

    fcntl_unlock(fd, off, 4);
    return Napi::Number::New(env, val);
}

// fcntlStore32(fd, buf, byteOffset, val) -> undefined
static Napi::Value FcntlStore32(const Napi::CallbackInfo& info)
{
    auto env = info.Env();
    auto fd  = info[0].As<Napi::Number>().Int32Value();
    auto buf = info[1].As<Napi::Buffer<uint8_t>>();
    auto off = info[2].As<Napi::Number>().Uint32Value();
    auto val = info[3].As<Napi::Number>().Int32Value();

    if (fcntl_lock(fd, off, 4) < 0) {
        Napi::Error::New(env, std::string("fcntl lock failed: ") + std::strerror(errno))
            .ThrowAsJavaScriptException();
        return env.Null();
    }

    auto* p = reinterpret_cast<std::int32_t*>(buf.Data() + off);
    ::memcpy(p, &val, 4);
    ::msync(buf.Data() + (off & ~(4095UL)), 4096, MS_SYNC);

    fcntl_unlock(fd, off, 4);
    return env.Undefined();
}

// fcntlAdd32(fd, buf, byteOffset, delta) -> old value
static Napi::Value FcntlAdd32(const Napi::CallbackInfo& info)
{
    auto env   = info.Env();
    auto fd    = info[0].As<Napi::Number>().Int32Value();
    auto buf   = info[1].As<Napi::Buffer<uint8_t>>();
    auto off   = info[2].As<Napi::Number>().Uint32Value();
    auto delta = info[3].As<Napi::Number>().Int32Value();

    if (fcntl_lock(fd, off, 4) < 0) {
        Napi::Error::New(env, std::string("fcntl lock failed: ") + std::strerror(errno))
            .ThrowAsJavaScriptException();
        return env.Null();
    }

    auto* p = reinterpret_cast<std::int32_t*>(buf.Data() + off);
    std::int32_t old;
    ::memcpy(&old, p, 4);
    std::int32_t nv = old + delta;
    ::memcpy(p, &nv, 4);
    ::msync(buf.Data() + (off & ~(4095UL)), 4096, MS_SYNC);

    fcntl_unlock(fd, off, 4);
    return Napi::Number::New(env, old);
}

// fcntlSub32(fd, buf, byteOffset, delta) -> old value
static Napi::Value FcntlSub32(const Napi::CallbackInfo& info)
{
    auto env   = info.Env();
    auto fd    = info[0].As<Napi::Number>().Int32Value();
    auto buf   = info[1].As<Napi::Buffer<uint8_t>>();
    auto off   = info[2].As<Napi::Number>().Uint32Value();
    auto delta = info[3].As<Napi::Number>().Int32Value();

    if (fcntl_lock(fd, off, 4) < 0) {
        Napi::Error::New(env, std::string("fcntl lock failed: ") + std::strerror(errno))
            .ThrowAsJavaScriptException();
        return env.Null();
    }

    auto* p = reinterpret_cast<std::int32_t*>(buf.Data() + off);
    std::int32_t old;
    ::memcpy(&old, p, 4);
    std::int32_t nv = old - delta;
    ::memcpy(p, &nv, 4);
    ::msync(buf.Data() + (off & ~(4095UL)), 4096, MS_SYNC);

    fcntl_unlock(fd, off, 4);
    return Napi::Number::New(env, old);
}

// fcntlExchange32(fd, buf, byteOffset, val) -> old value
static Napi::Value FcntlExchange32(const Napi::CallbackInfo& info)
{
    auto env = info.Env();
    auto fd  = info[0].As<Napi::Number>().Int32Value();
    auto buf = info[1].As<Napi::Buffer<uint8_t>>();
    auto off = info[2].As<Napi::Number>().Uint32Value();
    auto val = info[3].As<Napi::Number>().Int32Value();

    if (fcntl_lock(fd, off, 4) < 0) {
        Napi::Error::New(env, std::string("fcntl lock failed: ") + std::strerror(errno))
            .ThrowAsJavaScriptException();
        return env.Null();
    }

    auto* p = reinterpret_cast<std::int32_t*>(buf.Data() + off);
    std::int32_t old;
    ::memcpy(&old, p, 4);
    ::memcpy(p, &val, 4);
    ::msync(buf.Data() + (off & ~(4095UL)), 4096, MS_SYNC);

    fcntl_unlock(fd, off, 4);
    return Napi::Number::New(env, old);
}

// --- fcntl i64 variants ---

// fcntlCas64(fd, buf, byteOffset, expected, desired) -> old value
static Napi::Value FcntlCas64(const Napi::CallbackInfo& info)
{
    auto env      = info.Env();
    auto fd       = info[0].As<Napi::Number>().Int32Value();
    auto buf      = info[1].As<Napi::Buffer<uint8_t>>();
    auto off      = info[2].As<Napi::Number>().Uint32Value();
    auto expected = static_cast<std::int64_t>(info[3].As<Napi::Number>().Int64Value());
    auto desired  = static_cast<std::int64_t>(info[4].As<Napi::Number>().Int64Value());

    if (fcntl_lock(fd, off, 8) < 0) {
        Napi::Error::New(env, std::string("fcntl lock failed: ") + std::strerror(errno))
            .ThrowAsJavaScriptException();
        return env.Null();
    }

    auto* p = reinterpret_cast<std::int64_t*>(buf.Data() + off);
    std::int64_t old;
    ::memcpy(&old, p, 8);

    if (old == expected) {
        ::memcpy(p, &desired, 8);
        ::msync(buf.Data() + (off & ~(4095UL)), 4096, MS_SYNC);
    }

    fcntl_unlock(fd, off, 8);
    return Napi::Number::New(env, static_cast<double>(old));
}

// fcntlLoad64(fd, buf, byteOffset) -> value
static Napi::Value FcntlLoad64(const Napi::CallbackInfo& info)
{
    auto env = info.Env();
    auto fd  = info[0].As<Napi::Number>().Int32Value();
    auto buf = info[1].As<Napi::Buffer<uint8_t>>();
    auto off = info[2].As<Napi::Number>().Uint32Value();

    if (fcntl_lock(fd, off, 8) < 0) {
        Napi::Error::New(env, std::string("fcntl lock failed: ") + std::strerror(errno))
            .ThrowAsJavaScriptException();
        return env.Null();
    }

    auto* p = reinterpret_cast<std::int64_t*>(buf.Data() + off);
    std::int64_t val;
    ::memcpy(&val, p, 8);

    fcntl_unlock(fd, off, 8);
    return Napi::Number::New(env, static_cast<double>(val));
}

// fcntlStore64(fd, buf, byteOffset, val) -> undefined
static Napi::Value FcntlStore64(const Napi::CallbackInfo& info)
{
    auto env = info.Env();
    auto fd  = info[0].As<Napi::Number>().Int32Value();
    auto buf = info[1].As<Napi::Buffer<uint8_t>>();
    auto off = info[2].As<Napi::Number>().Uint32Value();
    auto val = static_cast<std::int64_t>(info[3].As<Napi::Number>().Int64Value());

    if (fcntl_lock(fd, off, 8) < 0) {
        Napi::Error::New(env, std::string("fcntl lock failed: ") + std::strerror(errno))
            .ThrowAsJavaScriptException();
        return env.Null();
    }

    auto* p = reinterpret_cast<std::int64_t*>(buf.Data() + off);
    ::memcpy(p, &val, 8);
    ::msync(buf.Data() + (off & ~(4095UL)), 4096, MS_SYNC);

    fcntl_unlock(fd, off, 8);
    return env.Undefined();
}

// fcntlAdd64(fd, buf, byteOffset, delta) -> old value
static Napi::Value FcntlAdd64(const Napi::CallbackInfo& info)
{
    auto env   = info.Env();
    auto fd    = info[0].As<Napi::Number>().Int32Value();
    auto buf   = info[1].As<Napi::Buffer<uint8_t>>();
    auto off   = info[2].As<Napi::Number>().Uint32Value();
    auto delta = static_cast<std::int64_t>(info[3].As<Napi::Number>().Int64Value());

    if (fcntl_lock(fd, off, 8) < 0) {
        Napi::Error::New(env, std::string("fcntl lock failed: ") + std::strerror(errno))
            .ThrowAsJavaScriptException();
        return env.Null();
    }

    auto* p = reinterpret_cast<std::int64_t*>(buf.Data() + off);
    std::int64_t old;
    ::memcpy(&old, p, 8);
    std::int64_t nv = old + delta;
    ::memcpy(p, &nv, 8);
    ::msync(buf.Data() + (off & ~(4095UL)), 4096, MS_SYNC);

    fcntl_unlock(fd, off, 8);
    return Napi::Number::New(env, static_cast<double>(old));
}

// fcntlSub64(fd, buf, byteOffset, delta) -> old value
static Napi::Value FcntlSub64(const Napi::CallbackInfo& info)
{
    auto env   = info.Env();
    auto fd    = info[0].As<Napi::Number>().Int32Value();
    auto buf   = info[1].As<Napi::Buffer<uint8_t>>();
    auto off   = info[2].As<Napi::Number>().Uint32Value();
    auto delta = static_cast<std::int64_t>(info[3].As<Napi::Number>().Int64Value());

    if (fcntl_lock(fd, off, 8) < 0) {
        Napi::Error::New(env, std::string("fcntl lock failed: ") + std::strerror(errno))
            .ThrowAsJavaScriptException();
        return env.Null();
    }

    auto* p = reinterpret_cast<std::int64_t*>(buf.Data() + off);
    std::int64_t old;
    ::memcpy(&old, p, 8);
    std::int64_t nv = old - delta;
    ::memcpy(p, &nv, 8);
    ::msync(buf.Data() + (off & ~(4095UL)), 4096, MS_SYNC);

    fcntl_unlock(fd, off, 8);
    return Napi::Number::New(env, static_cast<double>(old));
}

// closeFd(fd) -> undefined
static Napi::Value CloseFd(const Napi::CallbackInfo& info)
{
    auto fd = info[0].As<Napi::Number>().Int32Value();
    ::close(fd);
    return info.Env().Undefined();
}

// nanosleep helper for Lustre polling wait (no futex across nodes)
static Napi::Value NanoSleep(const Napi::CallbackInfo& info)
{
    auto ns = info[0].As<Napi::Number>().Int64Value();
    struct timespec ts{ 0, static_cast<long>(ns) };
    ::nanosleep(&ts, nullptr);
    return info.Env().Undefined();
}

// ---------------------------------------------------------------------------
// Module init
// ---------------------------------------------------------------------------

static Napi::Object Init(Napi::Env env, Napi::Object exports)
{
    // Existing hardware-atomic ops
    exports["open"]     = Napi::Function::New(env, Open,     "open");
    exports["load32"]   = Napi::Function::New(env, Load32,   "load32");
    exports["store32"]  = Napi::Function::New(env, Store32,  "store32");
    exports["cas32"]    = Napi::Function::New(env, Cas32,    "cas32");
    exports["add32"]    = Napi::Function::New(env, Add32,    "add32");
    exports["sub32"]    = Napi::Function::New(env, Sub32,    "sub32");
    exports["load64"]   = Napi::Function::New(env, Load64,   "load64");
    exports["store64"]  = Napi::Function::New(env, Store64,  "store64");
    exports["cas64"]    = Napi::Function::New(env, Cas64,    "cas64");
    exports["add64"]    = Napi::Function::New(env, Add64,    "add64");
    exports["sub64"]    = Napi::Function::New(env, Sub64,    "sub64");
    exports["wait32"]   = Napi::Function::New(env, Wait32,   "wait32");
    exports["notify32"] = Napi::Function::New(env, Notify32, "notify32");

    // Lustre fcntl-based ops
    exports["openWithFd"]     = Napi::Function::New(env, OpenWithFd,     "openWithFd");
    exports["fcntlLoad32"]    = Napi::Function::New(env, FcntlLoad32,    "fcntlLoad32");
    exports["fcntlStore32"]   = Napi::Function::New(env, FcntlStore32,   "fcntlStore32");
    exports["fcntlCas32"]     = Napi::Function::New(env, FcntlCas32,     "fcntlCas32");
    exports["fcntlAdd32"]     = Napi::Function::New(env, FcntlAdd32,     "fcntlAdd32");
    exports["fcntlSub32"]     = Napi::Function::New(env, FcntlSub32,     "fcntlSub32");
    exports["fcntlExchange32"] = Napi::Function::New(env, FcntlExchange32, "fcntlExchange32");
    exports["fcntlLoad64"]    = Napi::Function::New(env, FcntlLoad64,    "fcntlLoad64");
    exports["fcntlStore64"]   = Napi::Function::New(env, FcntlStore64,   "fcntlStore64");
    exports["fcntlCas64"]     = Napi::Function::New(env, FcntlCas64,     "fcntlCas64");
    exports["fcntlAdd64"]     = Napi::Function::New(env, FcntlAdd64,     "fcntlAdd64");
    exports["fcntlSub64"]     = Napi::Function::New(env, FcntlSub64,     "fcntlSub64");
    exports["closeFd"]        = Napi::Function::New(env, CloseFd,        "closeFd");
    exports["nanosleep"]      = Napi::Function::New(env, NanoSleep,      "nanosleep");
    return exports;
}

NODE_API_MODULE(mmap_cas, Init)
