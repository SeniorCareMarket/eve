#!/usr/bin/env python3
"""jlog_syncbench.py — the device-flush baseline the jlog spike's criterion 5 is
judged against (doc/perf/eve-mmap-journal-plan.md §2, "primitive costs"): one
2,084-byte record per 4 KiB slot of a preallocated 4 MiB file, median of N
appends, each shape on its own fresh file in DIR (default /tmp):

  pwrite+fdatasync   pwrite the record at its slot, then fdatasync
  mmap+msync         copy it into a MAP_SHARED mapping, then msync the slot

Prints "<shape> median_ns p90_ns". Usage: jlog_syncbench.py [dir] [n]
"""
import mmap
import os
import sys
import time

d = sys.argv[1] if len(sys.argv) > 1 else "/tmp"
n = int(sys.argv[2]) if len(sys.argv) > 2 else 400
SIZE = 1024 * 4096
REC = bytes((i * 7) & 0xFF for i in range(2084))


def fresh(name):
    p = os.path.join(d, name)
    fd = os.open(p, os.O_RDWR | os.O_CREAT | os.O_TRUNC, 0o644)
    os.posix_fallocate(fd, 0, SIZE)
    os.fsync(fd)
    return p, fd


def report(name, ts):
    ts.sort()
    print(f"{name} {ts[len(ts) // 2]} {ts[int(len(ts) * 0.9)]}")


p, fd = fresh("jlog-syncbench-pwrite")
ts = []
for i in range(n):
    t0 = time.perf_counter_ns()
    os.pwrite(fd, REC, (i % 1024) * 4096)
    os.fdatasync(fd)
    ts.append(time.perf_counter_ns() - t0)
os.close(fd)
os.unlink(p)
report("pwrite+fdatasync", ts)

p, fd = fresh("jlog-syncbench-mmap")
m = mmap.mmap(fd, SIZE, mmap.MAP_SHARED, mmap.PROT_READ | mmap.PROT_WRITE)
ts = []
for i in range(n):
    off = (i % 1024) * 4096
    t0 = time.perf_counter_ns()
    m[off:off + len(REC)] = REC
    m.flush(off, 4096)
    ts.append(time.perf_counter_ns() - t0)
m.close()
os.close(fd)
os.unlink(p)
report("mmap+msync", ts)
