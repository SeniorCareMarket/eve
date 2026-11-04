import ctypes, errno, fcntl, os, struct, sys
libc = ctypes.CDLL("libc.so.6", use_errno=True)
class Flock(ctypes.Structure):
    _fields_ = [("l_type", ctypes.c_short), ("l_whence", ctypes.c_short),
                ("l_start", ctypes.c_long), ("l_len", ctypes.c_long), ("l_pid", ctypes.c_int)]
F_OFD_SETLK = 37; F_OFD_SETLKW = 38; F_SETLK = 6; F_WRLCK = 1; SEEK_SET = 0
def try_fcntl(path, cmd, label, rng=None):
    fd = os.open(path, os.O_RDWR)
    fl = Flock(); fl.l_type = F_WRLCK; fl.l_whence = SEEK_SET
    fl.l_start, fl.l_len = (rng if rng else (0, 0))
    r = libc.fcntl(fd, cmd, ctypes.byref(fl))
    e = ctypes.get_errno()
    os.close(fd)
    print(f"{label}: {'ACQUIRED (no exclusion!)' if r == 0 else 'BLOCKED/DENIED errno=' + str(e)}")
def try_flock(path):
    fd = os.open(path, os.O_RDWR)
    try:
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB); print("flock(2) LOCK_EX: ACQUIRED (no exclusion!)"); fcntl.flock(fd, fcntl.LOCK_UN)
    except OSError as ex: print(f"flock(2) LOCK_EX: BLOCKED/DENIED errno={ex.errno}")
    os.close(fd)
def try_lockf(path):
    fd = os.open(path, os.O_RDWR)
    try:
        fcntl.lockf(fd, fcntl.LOCK_EX | fcntl.LOCK_NB); print("python lockf (F_SETLK): ACQUIRED (no exclusion!)"); fcntl.lockf(fd, fcntl.LOCK_UN)
    except OSError as ex: print(f"python lockf (F_SETLK): BLOCKED/DENIED errno={ex.errno}")
    os.close(fd)
def try_mkdir(path):
    d = path + ".lock"
    try:
        os.mkdir(d); print("mkdir lock-dir: ACQUIRED (no exclusion!)"); os.rmdir(d)
    except OSError as ex: print(f"mkdir lock-dir: BLOCKED/DENIED errno={ex.errno}")
p = sys.argv[1]
try_fcntl(p, F_SETLK, "fcntl F_SETLK (classic POSIX) whole-file")
try_fcntl(p, F_OFD_SETLK, "fcntl F_OFD_SETLK (OFD) whole-file")
try_flock(p); try_lockf(p); try_mkdir(p)
