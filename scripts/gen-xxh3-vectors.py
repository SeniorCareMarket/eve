#!/usr/bin/env python3
"""The XXH3-128 vectors of unit 4.1 (eve/doc/network-atoms/IMPL-PLAN.md §6.1): the VSv2 kernel's own xxh3_128
(vs/src/vs/kernel/wasm/kernel.wasm, through the kernel-direct harness vs/test-xfail/retirement-epochs/kernel_direct.py)
over deterministic inputs — byte i of an input of length n is (i * 31 + n * 7) mod 256 — for every length 0..300 (each
short path and both mid paths) and long inputs (241 … 10,000: the stripe and block loops). Writes
eve/test/eve/fixtures/xxh3-vectors.edn: [{:len n :hash "<32 hex>"} …]. Tooling, not product code."""
import ctypes, importlib.util, os, sys

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
spec = importlib.util.spec_from_file_location("kd", os.path.join(ROOT, "vs", "test-xfail", "retirement-epochs", "kernel_direct.py"))
kd = importlib.util.module_from_spec(spec); spec.loader.exec_module(kd)

LENS = list(range(0, 301)) + [384, 512, 777, 1024, 1025, 1500, 2048, 4096, 4097, 10000]


def data(n): return bytes((i * 31 + n * 7) % 256 for i in range(n))


def main(out_path):
    k = kd.fresh(os.path.join(ROOT, "vs", "src", "vs", "kernel", "wasm", "kernel.wasm"))
    buf = k.f("claim_region"); assert buf > 0, buf
    rows = []
    for n in LENS:
        d = data(n)
        ctypes.memmove(k.base() + buf, d, n)
        k.f("xxh3_128", buf, n, buf + 65536)
        rows.append('{:len %d :hash "%s"}' % (n, ctypes.string_at(k.base() + buf + 65536, 16).hex()))
    open(out_path, "w").write(";; XXH3-128 of the kernel (eve/scripts/gen-xxh3-vectors.py): input byte i of length n is (i*31 + n*7) mod 256\n["
                              + "\n ".join(rows) + "]\n")
    print("wrote %d vectors to %s" % (len(rows), out_path))


main(sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "eve", "test", "eve", "fixtures", "xxh3-vectors.edn"))
