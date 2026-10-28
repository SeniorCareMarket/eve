#!/usr/bin/env python3
"""Phase 2 — SQLite-pattern simulated-crash suite for the Eve
durability spike. Usage:
  crash_suite.py <variant B|J1|J10> <size counter|kb64> <workroot> <evidence-out>

Records a scripted commit sequence (snapshotting the domain after
each commit), then for every crash point materializes an image in
which ONLY the forced effects up to that point exist and reopens
it in a fresh JVM process. Model: a force makes its ranges durable
atomically w.r.t. the crash point; unforced data is entirely
absent (powersafe-write assumption). Root images carry only the
forced bytes of the root file (epoch word [8,12) + atom-table
slot 0 [6272,6280)); all other root bytes stay at checkpoint —
modeling dead writers' liveness state as volatile.
"""
import os, re, shutil, subprocess, sys

VARIANT, SIZE, WORK, OUT = sys.argv[1:5]
EVE = os.path.expanduser("~/workspace/wt/eve-durability-spike/eve")
ENV = dict(os.environ,
           JAVA_HOME=os.path.expanduser("~/opt/jdk-21.0.7+6-jre"),
           CLJ_CONFIG=os.path.expanduser("~/workspace/eve-env/cljconfig"),
           PATH=os.path.expanduser("~/opt/jdk-21.0.7+6-jre/bin") + ":" +
                os.path.expanduser("~/opt/clojure-tools/bin") + ":/usr/bin:" +
                os.environ["PATH"])
CLJ = ["clojure",
       "-J--add-opens=java.base/java.nio=ALL-UNNAMED",
       "-J--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
       "-Sdeps", '{:aliases {:t {:extra-paths ["test"]}}}', "-M:t"]
N = {"B": 4, "J1": 4, "J10": 11}[VARIANT]

def jhash(s):
    h = 0
    for ch in s:
        h = (31 * h + ord(ch)) & 0xFFFFFFFF
    return h - 0x100000000 if h >= 0x80000000 else h

def expected(k):
    """Value descriptor after user commit k (0 = checkpoint/init)."""
    if SIZE == "counter":
        return {"value": k}
    ch = "z" if k == 0 else chr(97 + (k % 26))
    return {"count": 2200, "elem": 30, "fills": ch * 3}

def run_clj(main, args, timeout=300):
    return subprocess.run(CLJ + ["-m", main] + args, cwd=EVE, env=ENV,
                          capture_output=True, text=True, timeout=timeout)

def record():
    import json
    mj = os.path.join(WORK, "manifest.json")
    if os.path.exists(mj) and os.path.exists(snap(N)):
        return {int(k): v for k, v in json.load(open(mj)).items()}
    os.makedirs(WORK, exist_ok=True)
    r = run_clj("eve.durability-crash-recorder",
                [VARIANT, SIZE, WORK, str(N)])
    man = {}
    for line in r.stdout.splitlines():
        m = re.match(r"commit (\d+) journal-len (\d+)", line.strip())
        if m:
            man[int(m.group(1))] = int(m.group(2))
    if len(man) != N + 1:
        raise SystemExit(f"recorder failed: {r.stdout[-500:]} {r.stderr[-500:]}")
    json.dump(man, open(mj, "w"))
    return man

def snap(i):
    return os.path.join(WORK, f"snap-{i}")

def materialize(point_dir, slab_snap, root_snap, journal):
    """journal: None (keep snap-0's file) or (snap_idx, nbytes)."""
    if os.path.exists(point_dir):
        shutil.rmtree(point_dir)
    shutil.copytree(snap(0), point_dir)
    if slab_snap:
        for f in os.listdir(snap(slab_snap)):
            if ".slab" in f:
                shutil.copy(os.path.join(snap(slab_snap), f),
                            os.path.join(point_dir, f))
    if root_snap is not None:
        rp = os.path.join(point_dir, "domain.root")
        with open(os.path.join(snap(root_snap), "domain.root"), "rb") as fh:
            src = fh.read()
        with open(rp, "r+b") as fh:
            fh.seek(8); fh.write(src[8:12])            # epoch word
            fh.seek(6272); fh.write(src[6272:8320])    # whole atom table
            # (verified on the B/counter sequence: across 4 commits
            # the only root bytes that change are the epoch word's
            # low byte and one pointer byte inside the table.)
    if journal is not None:
        sidx, nbytes = journal
        data = b""
        jp = os.path.join(snap(sidx), "domain.journal")
        if os.path.exists(jp):
            data = open(jp, "rb").read()[:nbytes]
        with open(os.path.join(point_dir, "domain.journal"), "wb") as fh:
            fh.write(data)

def points(man):
    """(name, slab_snap, root_snap, journal, expected_commit)"""
    pts = [("p0-checkpoint", 0, None, None, 0)]
    if VARIANT == "B":
        for i in range(1, N + 1):
            # after commit i's slab force: events slab_1..slab_i and
            # root_1..root_{i-1} have landed — the root carries
            # commit i-1's forced bytes, the slab commit i's.
            pts.append((f"c{i}-after-slab-force", i, i - 1, None, i - 1))
            pts.append((f"c{i}-after-root-force", i, i, None, i))
    elif VARIANT == "J1":
        for i in range(1, N + 1):
            pts.append((f"c{i}-after-slab-force", i, None, (i, man[i - 1]), i - 1))
            pts.append((f"c{i}-after-journal-force", i, None, (i, man[i]), i))
            pts.append((f"c{i}-root-ahead-of-journal", i, i, (i, man[i - 1]), i - 1))
        for t in (4, 100, 1030, 2067):
            pts.append((f"c3-torn-tail-{t}b", 3, None, (3, man[2] + t), 2))
    elif VARIANT == "J10":
        # POST-FLOOR-FIX journal modeling (2026-10-27): since the
        # anchor fix, the journal is never empty after a checkpoint —
        # snap-0's journal IS the anchor record (man[0] bytes, forced
        # at setup's checkpoint). Window points therefore carry
        # (snap, man[0]) — the anchor alone — not 0 bytes; the
        # pre-fix (0, 0) compositions modeled a journal the fixed
        # code never produces.
        for i in (1, 5, 9):
            pts.append((f"window-c{i}-nothing-forced", 0, None, (0, man[0]), 0))
        pts.append(("flush-after-sweep", 10, None, (10, man[0]), 0))
        for t in (100, 1030):
            pts.append((f"flush-torn-{t}b", 10, None, (10, man[0] + t), 0))
        pts.append(("flush-done", 10, None, (10, man[10]), 10))
        pts.append(("post-flush-window-c11", 10, None, (10, man[10]), 10))
        # live root lands mid-window, journal holds only the anchor,
        # the window's blocks never forced: reopen must install the
        # anchor's epoch (0) — the recovery floor, in its batch form.
        pts.append(("window-c5-root-ahead", 0, 5, (0, man[0]), 0))
    return pts

def parse_reader(out):
    d = {}
    m = re.search(r":value (-?\d+)", out)
    if m: d["value"] = int(m.group(1))
    m = re.search(r":count (\d+)", out)
    if m: d["count"] = int(m.group(1))
    m = re.search(r":head (\S+)", out)
    if m: d["head"] = m.group(1)
    m = re.search(r":hash (-?\d+)", out)
    if m: d["hash"] = int(m.group(1))
    m = re.search(r":elem (\d+)", out)
    if m: d["elem"] = int(m.group(1))
    m = re.search(r":fills (\S+)", out)
    if m: d["fills"] = m.group(1)
    return d

def main():
    import time
    man = record()
    # The recorder exited without closing: its worker heartbeat in
    # snap-0 is fresh for 30 s, and journal recovery (correctly)
    # defers on a live-looking domain. Wait out the staleness
    # window once, before any reader opens an image.
    age = time.time() - os.path.getmtime(snap(0))
    if age < 35:
        time.sleep(35 - age)
    done = set()
    if os.path.exists(OUT):
        for line in open(OUT):
            m = re.match(r"point (\S+):", line)
            if m:
                done.add(m.group(1))
    lines = []
    if not done:
        lines.append(f"# crash-suite variant={VARIANT} size={SIZE} manifest={man}")
        with open(OUT, "w") as fh:
            fh.write(lines[0] + "\n")
    n_pass = n_viol = 0
    for name, slab_s, root_s, journal, exp_k in points(man):
        if name in done:
            continue
        pdir = os.path.join(WORK, "img-" + name)
        materialize(pdir, slab_s, root_s, journal)
        try:
            r = run_clj("eve.durability-crash-reader",
                        [VARIANT, os.path.join(pdir, "domain")], timeout=240)
            got = parse_reader(r.stdout)
            exp = expected(exp_k)
            ok = got == exp and r.returncode == 0
            detail = f"expect {exp} got {got} rc={r.returncode}"
            if not ok and r.stderr:
                detail += " err=" + r.stderr.strip().splitlines()[-1][:160]
        except subprocess.TimeoutExpired:
            ok, detail = False, "reader TIMEOUT"
        line = f"point {name}: {'PASS' if ok else 'VIOLATION'} ({detail})"
        with open(OUT, "a") as fh:
            fh.write(line + "\n")
        n_pass, n_viol = n_pass + ok, n_viol + (not ok)
        print(line, flush=True)
    # totals count every point line in OUT (including resumed ones)
    total = [l for l in open(OUT) if l.startswith("point ")]
    np_ = sum(" PASS" in l for l in total)
    nv_ = sum(" VIOLATION" in l for l in total)
    with open(OUT, "a") as fh:
        fh.write(f"# totals: {np_} pass, {nv_} violations\n")
    print(f"# totals: {np_} pass, {nv_} violations")

if __name__ == "__main__":
    main()
