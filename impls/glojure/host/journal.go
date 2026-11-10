package host

import (
	"os"
	"syscall"
)

// The commit journal's file lock (eve/adapters/journal-file/glojure):
// a POSIX fcntl write lock on the whole journal file, taken with
// F_SETLKW — the lock family the JVM seat's FileLock and the basilisp
// seat's lockf share on the same path, so seats of different hosts
// serialize on one domain's journal. (The region package's fcntlLock,
// on a plain file instead of a mapping: impls/glojure/region.)

func JournalLock(f *os.File) error {
	lk := syscall.Flock_t{Type: syscall.F_WRLCK, Whence: 0, Start: 0, Len: 0}
	return syscall.FcntlFlock(f.Fd(), syscall.F_SETLKW, &lk)
}

func JournalUnlock(f *os.File) error {
	lk := syscall.Flock_t{Type: syscall.F_UNLCK, Whence: 0, Start: 0, Len: 0}
	return syscall.FcntlFlock(f.Fd(), syscall.F_SETLKW, &lk)
}
