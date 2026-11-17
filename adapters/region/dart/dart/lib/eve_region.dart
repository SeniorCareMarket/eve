/// eve's region natives for ClojureDart (adapters/region/dart): the memory
/// eve.mem's IMemRegion speaks of, over dart:ffi — a file mapped MAP_SHARED
/// or a calloc'd heap buffer, its atomics the system libatomic's
/// (__atomic_*_4/_8, sequentially consistent), wait/notify the futex
/// syscall with a bounded-poll fallback. The same construction as
/// region/ctypes (basilisp), in Dart.
///
/// Every function takes the region's base address as a plain int, so the
/// .cljd half names nothing FFI. A region's address is the mapping's base;
/// it stays valid until eveCloseRegion.
library;

import 'dart:ffi' as ffi;
import 'dart:io';
import 'dart:typed_data';

const int _seqCst = 5;

// ---------------------------------------------------------------- libc ---

final ffi.DynamicLibrary _libc = ffi.DynamicLibrary.open('libc.so.6');
final ffi.DynamicLibrary _libatomic =
    ffi.DynamicLibrary.open('libatomic.so.1');

typedef _CallocNative = ffi.Pointer<ffi.Void> Function(
    ffi.IntPtr nmemb, ffi.IntPtr size);
typedef _CallocDart = ffi.Pointer<ffi.Void> Function(int nmemb, int size);
final _calloc =
    _libc.lookupFunction<_CallocNative, _CallocDart>('calloc');

typedef _FreeNative = ffi.Void Function(ffi.Pointer<ffi.Void>);
typedef _FreeDart = void Function(ffi.Pointer<ffi.Void>);
final _free = _libc.lookupFunction<_FreeNative, _FreeDart>('free');

typedef _OpenNative = ffi.Int32 Function(
    ffi.Pointer<ffi.Uint8> path, ffi.Int32 flags, ffi.Int32 mode);
typedef _OpenDart = int Function(ffi.Pointer<ffi.Uint8> path, int flags, int mode);
final _open = _libc.lookupFunction<_OpenNative, _OpenDart>('open');

typedef _CloseNative = ffi.Int32 Function(ffi.Int32 fd);
typedef _CloseDart = int Function(int fd);
final _close = _libc.lookupFunction<_CloseNative, _CloseDart>('close');

typedef _FtruncateNative = ffi.Int32 Function(ffi.Int32 fd, ffi.Int64 len);
typedef _FtruncateDart = int Function(int fd, int len);
final _ftruncate =
    _libc.lookupFunction<_FtruncateNative, _FtruncateDart>('ftruncate');

typedef _MmapNative = ffi.Pointer<ffi.Void> Function(ffi.Pointer<ffi.Void> addr,
    ffi.IntPtr len, ffi.Int32 prot, ffi.Int32 flags, ffi.Int32 fd, ffi.Int64 off);
typedef _MmapDart = ffi.Pointer<ffi.Void> Function(
    ffi.Pointer<ffi.Void> addr, int len, int prot, int flags, int fd, int off);
final _mmap = _libc.lookupFunction<_MmapNative, _MmapDart>('mmap');

typedef _MunmapNative = ffi.Int32 Function(
    ffi.Pointer<ffi.Void> addr, ffi.IntPtr len);
typedef _MunmapDart = int Function(ffi.Pointer<ffi.Void> addr, int len);
final _munmap = _libc.lookupFunction<_MunmapNative, _MunmapDart>('munmap');

typedef _MsyncNative = ffi.Int32 Function(
    ffi.Pointer<ffi.Void> addr, ffi.IntPtr len, ffi.Int32 flags);
typedef _MsyncDart = int Function(ffi.Pointer<ffi.Void> addr, int len, int flags);
final _msync = _libc.lookupFunction<_MsyncNative, _MsyncDart>('msync');

typedef _MemcpyNative = ffi.Pointer<ffi.Void> Function(
    ffi.Pointer<ffi.Void> dst, ffi.Pointer<ffi.Void> src, ffi.IntPtr n);
typedef _MemcpyDart = ffi.Pointer<ffi.Void> Function(
    ffi.Pointer<ffi.Void> dst, ffi.Pointer<ffi.Void> src, int n);
final _memcpy = _libc.lookupFunction<_MemcpyNative, _MemcpyDart>('memcpy');

typedef _PwriteNative = ffi.IntPtr Function(
    ffi.Int32 fd, ffi.Pointer<ffi.Void> buf, ffi.IntPtr n, ffi.Int64 off);
typedef _PwriteDart = int Function(
    int fd, ffi.Pointer<ffi.Void> buf, int n, int off);
final _pwrite = _libc.lookupFunction<_PwriteNative, _PwriteDart>('pwrite');

// futex through the syscall varargs form: syscall(SYS_futex, uaddr, op, val,
// timeout, uaddr2, val3). SYS_futex is 202 on x86-64 (449 on aarch64).
typedef _SyscallNative = ffi.IntPtr Function(ffi.IntPtr num,
    ffi.VarArgs<(ffi.Pointer<ffi.Void>, ffi.Int32, ffi.Int32, ffi.Pointer<ffi.Void>, ffi.Pointer<ffi.Void>, ffi.Int32)>);
typedef _SyscallDart = int Function(int num, ffi.Pointer<ffi.Void> uaddr,
    int op, int val, ffi.Pointer<ffi.Void> timeout, ffi.Pointer<ffi.Void> uaddr2, int val3);
final _syscall = _libc.lookupFunction<_SyscallNative, _SyscallDart>('syscall');

typedef _ErrnoNative = ffi.Pointer<ffi.Int32> Function();
typedef _ErrnoDart = ffi.Pointer<ffi.Int32> Function();
final _errnoLocation =
    _libc.lookupFunction<_ErrnoNative, _ErrnoDart>('__errno_location');
int get _errno => _errnoLocation().value;
final int _sysFutex = _detectSysFutex();

int _detectSysFutex() {
  // dart:io knows the OS, not the arch; x86-64 is 202, aarch64 is 449. The
  // probe at first wait decides by trying; here we pick by FFI's Abi.
  final abi = ffi.Abi.current();
  if (abi == ffi.Abi.linuxArm64 || abi == ffi.Abi.androidArm64) return 449;
  return 202;
}

// ------------------------------------------------------------ libatomic ---

typedef _Load4Native = ffi.Int32 Function(ffi.Pointer<ffi.Void>, ffi.Int32);
typedef _Load4Dart = int Function(ffi.Pointer<ffi.Void>, int);
final _load4 =
    _libatomic.lookupFunction<_Load4Native, _Load4Dart>('__atomic_load_4');

typedef _Store4Native = ffi.Void Function(
    ffi.Pointer<ffi.Void>, ffi.Int32, ffi.Int32);
typedef _Store4Dart = void Function(ffi.Pointer<ffi.Void>, int, int);
final _store4 =
    _libatomic.lookupFunction<_Store4Native, _Store4Dart>('__atomic_store_4');

typedef _Add4Native = ffi.Int32 Function(
    ffi.Pointer<ffi.Void>, ffi.Int32, ffi.Int32);
typedef _Add4Dart = int Function(ffi.Pointer<ffi.Void>, int, int);
final _add4 = _libatomic.lookupFunction<_Add4Native, _Add4Dart>(
    '__atomic_fetch_add_4');

typedef _Xchg4Native = ffi.Int32 Function(
    ffi.Pointer<ffi.Void>, ffi.Int32, ffi.Int32);
typedef _Xchg4Dart = int Function(ffi.Pointer<ffi.Void>, int, int);
final _xchg4 = _libatomic.lookupFunction<_Xchg4Native, _Xchg4Dart>(
    '__atomic_exchange_4');

typedef _Cas4Native = ffi.Bool Function(ffi.Pointer<ffi.Void>,
    ffi.Pointer<ffi.Void>, ffi.Int32, ffi.Int32, ffi.Int32);
typedef _Cas4Dart = bool Function(ffi.Pointer<ffi.Void>, ffi.Pointer<ffi.Void>,
    int, int, int);
final _cas4 = _libatomic.lookupFunction<_Cas4Native, _Cas4Dart>(
    '__atomic_compare_exchange_4');

typedef _Load8Native = ffi.Int64 Function(ffi.Pointer<ffi.Void>, ffi.Int32);
typedef _Load8Dart = int Function(ffi.Pointer<ffi.Void>, int);
final _load8 =
    _libatomic.lookupFunction<_Load8Native, _Load8Dart>('__atomic_load_8');

typedef _Store8Native = ffi.Void Function(
    ffi.Pointer<ffi.Void>, ffi.Int64, ffi.Int32);
typedef _Store8Dart = void Function(ffi.Pointer<ffi.Void>, int, int);
final _store8 =
    _libatomic.lookupFunction<_Store8Native, _Store8Dart>('__atomic_store_8');

typedef _Add8Native = ffi.Int64 Function(
    ffi.Pointer<ffi.Void>, ffi.Int64, ffi.Int32);
typedef _Add8Dart = int Function(ffi.Pointer<ffi.Void>, int, int);
final _add8 = _libatomic.lookupFunction<_Add8Native, _Add8Dart>(
    '__atomic_fetch_add_8');

typedef _Cas8Native = ffi.Bool Function(ffi.Pointer<ffi.Void>,
    ffi.Pointer<ffi.Void>, ffi.Int64, ffi.Int32, ffi.Int32);
typedef _Cas8Dart = bool Function(ffi.Pointer<ffi.Void>, ffi.Pointer<ffi.Void>,
    int, int, int);
final _cas8 = _libatomic.lookupFunction<_Cas8Native, _Cas8Dart>(
    '__atomic_compare_exchange_8');

// ------------------------------------------------------------------ API ---

ffi.Pointer<ffi.Void> _ptr(int addr) => ffi.Pointer<ffi.Void>.fromAddress(addr);

/// A zero-initialised heap region of [size] bytes; its base address.
int eveHeapRegion(int size) {
  final p = _calloc(1, size);
  if (p == ffi.nullptr) {
    throw StateError('eve_region: calloc of $size bytes failed');
  }
  return p.address;
}

/// Fds and lengths of live mappings, by base address (eveSync's mirror
/// write-back and eveCloseRegion need them).
final Map<int, ({int fd, int size, bool mirrored})> _mappings = {};

/// True when the region at [addr] is a mirrored (MAP_PRIVATE) mapping —
/// see eveMmapRegion.
bool eveIsMirrored(int addr) => _mappings[addr]?.mirrored ?? false;

/// Open (or create) the file at [path], grow it to [size] bytes when it is
/// shorter (never truncate), and map it MAP_SHARED; the mapping's base
/// address. The fd stays open for the mapping's life (regions are few and
/// long-lived, as domains are).
///
/// Where the OS refuses a shared writable mapping (a sandbox seccomp
/// profile that answers EPERM for MAP_SHARED|PROT_WRITE — measured on
/// such a box, for Dart and for a C probe alike, while MAP_PRIVATE and
/// read-only shared mappings pass), the region falls back to a mirrored
/// MAP_PRIVATE mapping: reads and writes land in the mapping, and
/// eveSync writes the range back to the file with pwrite. Single-process
/// durability is exact (a sync is a sync); what narrows is cross-process
/// visibility between syncs — processes no longer share pages, each
/// sync publishes. eveIsMirrored names the shape where it matters.
int eveMmapRegion(String path, int size) {
  final bytes = path.codeUnits;
  final cpath = _calloc(bytes.length + 1, 1).cast<ffi.Uint8>();
  try {
    final list = cpath.asTypedList(bytes.length + 1);
    list.setAll(0, bytes);
    list[bytes.length] = 0;
    const int oRdwr = 0x02;
    const int oCreat = 0x40;
    final fd = _open(cpath, oRdwr | oCreat, 420); // 0644
    if (fd < 0) {
      throw StateError('eve_region: open($path) failed');
    }
    final st = File(path).statSync();
    if (st.size < size) {
      if (_ftruncate(fd, size) != 0) {
        _close(fd);
        throw StateError('eve_region: ftruncate($path, $size) failed');
      }
    }
    const int protRead = 1;
    const int protWrite = 2;
    const int mapShared = 1;
    const int mapPrivate = 2;
    var p = _mmap(ffi.nullptr, size, protRead | protWrite, mapShared, fd, 0);
    var mirrored = false;
    if (p.address == -1) {
      p = _mmap(ffi.nullptr, size, protRead | protWrite, mapPrivate, fd, 0);
      mirrored = true;
    }
    if (p.address == -1) {
      _close(fd);
      throw StateError('eve_region: mmap($path, $size) failed');
    }
    _mappings[p.address] = (fd: fd, size: size, mirrored: mirrored);
    return p.address;
  } finally {
    _free(cpath.cast<ffi.Void>());
  }
}

/// Release a region: unmap a mapping (writing a mirrored one back first,
/// and closing its fd), free a heap buffer. [mapped] says which. Length
/// is the region's size.
void eveCloseRegion(int addr, int size, bool mapped) {
  if (mapped) {
    final m = _mappings.remove(addr);
    if (m != null) {
      if (m.mirrored) _writeBack(addr, 0, size, m.fd);
      _munmap(_ptr(addr), size);
      _close(m.fd);
    } else {
      _munmap(_ptr(addr), size);
    }
  } else {
    _free(_ptr(addr));
  }
}

void _writeBack(int addr, int off, int len, int fd) {
  var done = 0;
  while (done < len) {
    final n = _pwrite(fd, _ptr(addr + off + done), len - done, off + done);
    if (n <= 0) {
      throw StateError('eve_region: pwrite of ${len}b at $off failed at $done');
    }
    done += n;
  }
}

/// Force a mapped range to durable storage. A shared mapping: msync
/// MS_SYNC, page-aligned here. A mirrored mapping: the range is written
/// back to the file with pwrite (that IS its sync — see eveMmapRegion).
/// A no-op on a heap region (its address is not a mapping; the caller,
/// eve.region, only syncs file regions).
void eveSync(int addr, int off, int len) {
  final m = _mappings[addr];
  if (m != null && m.mirrored) {
    _writeBack(addr, off, len, m.fd);
    return;
  }
  const int page = 4096;
  final start = addr + off;
  final aligned = start - (start % page);
  const int msSync = 4;
  _msync(_ptr(aligned), (start - aligned) + len, msSync);
}

int eveLoad32(int addr) => _load4(_ptr(addr), _seqCst);

void eveStore32(int addr, int v) => _store4(_ptr(addr), v, _seqCst);

/// Atomic compare-and-exchange; returns the witness (the value found).
int eveCas32(int addr, int expected, int desired) {
  final cell = _calloc(1, 4).cast<ffi.Int32>();
  try {
    cell.value = expected;
    _cas4(_ptr(addr), cell.cast<ffi.Void>(), desired, _seqCst, _seqCst);
    return cell.value;
  } finally {
    _free(cell.cast<ffi.Void>());
  }
}

int eveAdd32(int addr, int delta) => _add4(_ptr(addr), delta, _seqCst);

int eveExchange32(int addr, int v) => _xchg4(_ptr(addr), v, _seqCst);

int eveLoad64(int addr) => _load8(_ptr(addr), _seqCst);

void eveStore64(int addr, int v) => _store8(_ptr(addr), v, _seqCst);

int eveCas64(int addr, int expected, int desired) {
  final cell = _calloc(1, 8).cast<ffi.Int64>();
  try {
    cell.value = expected;
    _cas8(_ptr(addr), cell.cast<ffi.Void>(), desired, _seqCst, _seqCst);
    return cell.value;
  } finally {
    _free(cell.cast<ffi.Void>());
  }
}

int eveAdd64(int addr, int delta) => _add8(_ptr(addr), delta, _seqCst);

/// Copy [len] bytes at [addr]+[off] into a fresh Uint8List.
Uint8List eveReadBytes(int addr, int off, int len) {
  final out = Uint8List(len);
  if (len == 0) return out;
  final tmp = _calloc(len, 1);
  try {
    _memcpy(tmp, _ptr(addr + off), len);
    out.setAll(0, tmp.cast<ffi.Uint8>().asTypedList(len));
  } finally {
    _free(tmp);
  }
  return out;
}

/// Copy [src]'s bytes into the region at [addr]+[off].
void eveWriteBytes(int addr, int off, Uint8List src) {
  if (src.isEmpty) return;
  final tmp = _calloc(src.length, 1);
  try {
    tmp.cast<ffi.Uint8>().asTypedList(src.length).setAll(0, src);
    _memcpy(_ptr(addr + off), tmp, src.length);
  } finally {
    _free(tmp);
  }
}

/// Wait until the i32 at [addr] differs from [expected], or [timeoutMs]
/// elapse. Returns 0 (:ok), 1 (:not-equal), 2 (:timed-out). The futex is
/// the fast path; any environment where it answers an unexpected error
/// drops to the bounded poll beneath, which keeps the same contract.
int eveWait32(int addr, int expected, int timeoutMs) {
  if (_futexUsable) {
    final r = _futexWait(addr, expected, timeoutMs);
    if (r >= 0) return r;
    _futexUsable = false;
  }
  return _pollWait32(addr, expected, timeoutMs);
}

bool _futexUsable = true;

int _futexWait(int addr, int expected, int timeoutMs) {
  // struct timespec { long tv_sec; long tv_nsec; } as two Int64 slots.
  final ts = _calloc(2, 8).cast<ffi.Int64>();
  try {
    ts[0] = timeoutMs ~/ 1000;
    ts[1] = (timeoutMs % 1000) * 1000000;
    const int futexWait = 0;
    final r = _syscall(
        _sysFutex, _ptr(addr), futexWait, expected, ts.cast<ffi.Void>(), ffi.nullptr, 0);
    if (r == 0) return 0;
    final e = _errno;
    if (e == 11) return 1; // EAGAIN: the word already differed
    if (e == 110) return 2; // ETIMEDOUT
    if (e == 4) return 0; // EINTR: a wake-shaped interruption; callers re-check
    return -1; // unexpected: fall back to polling from now on
  } finally {
    _free(ts.cast<ffi.Void>());
  }
}

int _pollWait32(int addr, int expected, int timeoutMs) {
  final deadline = DateTime.now().millisecondsSinceEpoch + timeoutMs;
  while (true) {
    if (eveLoad32(addr) != expected) return 1;
    final now = DateTime.now().millisecondsSinceEpoch;
    if (now >= deadline) {
      return eveLoad32(addr) != expected ? 1 : 2;
    }
    final remain = deadline - now;
    sleep(Duration(milliseconds: remain < 1 ? remain : 1));
  }
}

/// Wake up to [n] waiters on the i32 at [addr]; the number actually woken
/// (0 when waiters are polling — nobody is blocked in a primitive to wake,
/// which the contract allows).
int eveNotify32(int addr, int n) {
  if (!_futexUsable) return 0;
  const int futexWake = 1;
  final r = _syscall(
      _sysFutex, _ptr(addr), futexWake, n, ffi.nullptr, ffi.nullptr, 0);
  return r < 0 ? 0 : r;
}
