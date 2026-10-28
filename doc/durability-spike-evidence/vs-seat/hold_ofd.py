import ctypes, os, sys, time
libc = ctypes.CDLL("libc.so.6", use_errno=True)
class Flock(ctypes.Structure):
    _fields_ = [("l_type", ctypes.c_short), ("l_whence", ctypes.c_short),
                ("l_start", ctypes.c_long), ("l_len", ctypes.c_long), ("l_pid", ctypes.c_int)]
cmd = 37 if sys.argv[2] == "ofd" else 6
fd = os.open(sys.argv[1], os.O_RDWR)
fl = Flock(); fl.l_type = 1; fl.l_whence = 0; fl.l_start = 0; fl.l_len = 0
r = libc.fcntl(fd, cmd, ctypes.byref(fl))
print("PY-HOLDING" if r == 0 else f"PY-FAILED errno={ctypes.get_errno()}", flush=True)
time.sleep(20)
