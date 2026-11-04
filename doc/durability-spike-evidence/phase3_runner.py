#!/usr/bin/env python3
"""Phase 3: extreme multi-runtime contention, 5 s journal window.

Cells A (no durability) and J (journal, 5 s window) run the SAME
mixed workload: 8 writer processes across 6 runtimes (JVM x2, Node
x2, nbb, ClojureCLR, Basilisp, jolt) sharing one domain: a counter
atom + record atoms at ~1 KB / ~100 KB / ~1 MB / ~100 MB. Writers run
60 s, are SIGKILLed (process-crash analog; page cache persists — the
journal state is what's checked), the domain is copied, and a fresh
JVM recovery-open + the checker verify:
  (a) every atom opens and reads whole;
  (b) counter >= confirmed increments (epoch <= newest journaled
      epoch at kill) and <= total increments;
  (c) per record id: recovered :v is a logged value (or 0) and >= the
      newest confirmed put for that record.
For cell A the same checks run with E_dur = None: only (a) and the
upper bounds apply (A promises nothing; a violation of (a) there is
still reported as observed).
Outputs per cell: cell.json + verify.edn + ops logs, under
~/workspace/phase3-runs/<cell>/.
"""
import json, os, re, shutil, signal, subprocess, sys, time

EVE = os.path.expanduser("~/workspace/wt/eve-durability-spike/eve")
RUNS = os.path.expanduser("~/workspace/phase3-runs")
JDK = os.path.expanduser("~/opt/jdk-21.0.7+6-jre/bin")
CLJ = [os.path.expanduser("~/opt/clojure-tools/bin/clojure")]
ENV = dict(os.environ,
           JAVA_HOME=os.path.expanduser("~/opt/jdk-21.0.7+6-jre"),
           CLJ_CONFIG=os.path.expanduser("~/workspace/eve-env/cljconfig"),
           PATH=":".join([JDK, os.path.expanduser("~/opt/clojure-tools/bin"),
                          os.path.expanduser("~/.local/bin"), "/usr/local/bin",
                          "/usr/bin", "/bin", os.path.expanduser("~/opt/bin")]))
ADDS = ["-J--add-opens=java.base/java.nio=ALL-UNNAMED",
        "-J--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"]
DUR_MS = int(os.environ.get("PHASE3_DUR_MS", "60000"))

def sh(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True, **kw)

def bb_classpath(host):
    r = sh(["bb", "classpath", host], cwd=EVE, env=ENV)
    return r.stdout.strip()

WRITERS = []  # filled per cell

def writer_cmds(cell_dir, variant):
    """(wid, cmd, cwd) for the 8 writers of a cell."""
    dom = os.path.join(cell_dir, "domain")
    out = []
    def cfg(wid, vrt, seed):
        d = os.path.join(cell_dir, f"w-{wid}")
        os.makedirs(d, exist_ok=True)
        log = os.path.join(d, "ops.log")
        c = {"dir": dom, "variant": vrt, "wid": wid, "seed": seed,
             "dur-ms": DUR_MS, "log": log}
        with open(os.path.join(d, "wl-config.edn"), "w") as f:
            f.write("{" + " ".join(f":{k} {json.dumps(v) if isinstance(v,str) else v}"
                                    for k, v in c.items()) + "}\n")
        with open(os.path.join(d, "wl-config.json"), "w") as f:
            json.dump(c, f)
        return d, log
    jvm_t = ["-Sdeps", "{:aliases {:t {:extra-paths [\"test\"]}}}", "-M:t"]
    for i in (1, 2):
        wid = f"jvm{i}"
        d, log = cfg(wid, variant, 1000 + i)
        out.append((wid, CLJ + ["-J-Xmx1g"] + ADDS + jvm_t +
                    ["-m", "eve.durability-workload", os.path.join(d, "wl-config.edn")], EVE))
    for i in (1, 2):
        wid = f"node{i}"
        d, log = cfg(wid, variant, 2000 + i)
        # CWD=EVE (native addon resolution); config via argv.
        out.append((wid, ["node", os.path.join(EVE, "target/durability-workload/main.js"),
                          os.path.join(d, "wl-config.json")], EVE))
    wid = "nbb"
    d, log = cfg(wid, variant, 3001)
    cp = bb_classpath("nbb") + ":" + os.path.join(EVE, "test")
    # CWD=EVE: the region's native-addon loader resolves relative to
    # the eve package (a foreign CWD fails with "addon not found");
    # the config path travels as -main's first argument instead.
    out.append((wid, [os.path.join(EVE, "node_modules/.bin/nbb"), "-cp", cp,
                      "-m", "eve.durability-workload",
                      os.path.join(d, "wl-config.json")], EVE))
    wid = "cljr"
    # Journal arm live on cljr since the journals-follow-up lane
    # (eve.journal-file/clr + ISyncableRegion msync): cljr seats run
    # the J arm itself. lpy's journal arm is written too
    # (eve.journal-file/python) but the lpy seat is down on current
    # main — eve.host-completion is unreadable by the pinned
    # basilisp's reader — so lpy still joins as Jn until that lands.
    d, log = cfg(wid, "A" if variant == "A" else "J", 4001)
    env_note = os.path.join(d, "env.txt")
    out.append((wid, ["cljr", "-m", "eve.durability-workload-cljr",
                      os.path.join(d, "wl-config.edn")], d))
    wid = "lpy"
    d, log = cfg(wid, "A" if variant == "A" else "Jn", 5001)
    roots = bb_classpath("lpy").split(":")
    args = [os.path.expanduser("~/workspace/venv-basilisp/bin/basilisp"), "run"]
    # basilisp searches the LAST -p first; test must be searched LAST
    # (bb.edn test:lpy's warning: test/eve/deftype/ would otherwise
    # shadow eve.deftype) — so test goes FIRST in argv.
    args += ["-p", os.path.join(EVE, "test")]
    for r in reversed(roots):
        args += ["-p", r]
    args += [os.path.join(EVE, "test/eve/durability_workload.lpy")]
    out.append((wid, args, d))
    wid = "jolt"
    d, log = cfg(wid, "A" if variant == "A" else "Jn", 6001)
    cp = bb_classpath("jolt") + ":" + os.path.join(EVE, "test")
    out.append((wid, ["jolt", "-Scp", cp, "-m", "eve.durability-workload",
                      os.path.join(d, "wl-config.edn")], EVE))
    return out

def writer_env(wid):
    e = dict(ENV)
    if wid == "cljr":
        e["CLOJURE_LOAD_PATH"] = bb_classpath("cljr") + ":" + os.path.join(EVE, "test")
        e["DOTNET_ROOT"] = os.path.expanduser("~/opt/dotnet8")
    if wid == "lpy":
        e["PYTHONPYCACHEPREFIX"] = os.path.expanduser("~/workspace/census-lpy/pycache")
        e["PYTHONUNBUFFERED"] = "1"
    return e

# ---------- journal parse ----------
def journal_newest_epoch(path):
    if not os.path.exists(path):
        return None
    data = open(path, "rb").read()
    import struct
    o, newest = 0, None
    while o + 8 <= len(data):
        if data[o:o+4] != b"EVJ1":
            break
        (ln,) = struct.unpack_from(">i", data, o + 4)
        # ints are big-endian (ByteBuffer.putInt default). ln counts
        # everything after the len field itself (epoch + table-len +
        # table + crc = 2060); record stride = 8 + ln.
        if ln <= 0 or o + 8 + ln > len(data):
            break
        (ep,) = struct.unpack_from(">i", data, o + 8)
        newest = ep
        o += 8 + ln
    return newest

# ---------- tiny EDN reader (maps, vectors, keywords, strings, ints, nil) ----------
class Kw(str): pass
def parse_edn(s):
    toks = re.findall(r'"(?:\\.|[^"\\])*"|:[^\s{}()\[\]",]+|[{}()\[\],]|[^\s{}()\[\]",]+', s)
    pos = [0]
    def nxt():
        t = toks[pos[0]]; pos[0] += 1
        while t == ",":  # CLJS/jolt pr-str separates map entries with commas
            t = toks[pos[0]]; pos[0] += 1
        return t
    def parse():
        t = nxt()
        if t == "#":  # set literal #{...} -> list
            v = []
            assert nxt() == "{"
            while toks[pos[0]] != "}":
                v.append(parse())
            nxt(); return v
        if t == "{":
            d = {}
            while toks[pos[0]] != "}":
                k = parse(); v = parse(); d[k] = v
            nxt(); return d
        if t == "[":
            v = []
            while toks[pos[0]] != "]":
                v.append(parse())
            nxt(); return v
        if t.startswith('"'):
            return json.loads(t)
        if t.startswith(":"):
            return Kw(t[1:])
        if t == "nil": return None
        try: return int(t)
        except ValueError:
            try: return float(t)
            except ValueError: return t
    return parse()

def copy_domain(src_prefix, dst_prefix):
    """An eve domain is a file PREFIX (domain.root, domain.slab0, ...),
    not a directory."""
    import glob
    for f in glob.glob(src_prefix + ".*"):
        shutil.copy2(f, dst_prefix + f[len(src_prefix):])

def run_cell(variant):
    cell = os.path.join(RUNS, f"cell-{variant}")
    if os.path.exists(cell):
        shutil.rmtree(cell)
    os.makedirs(cell)
    copy_domain(os.path.join(RUNS, "setup-domain"), os.path.join(cell, "domain"))
    procs = []
    journal = os.path.join(cell, "domain.journal")
    samples = []
    t0 = time.time()
    for wid, cmd, cwd in writer_cmds(cell, variant):
        f = open(os.path.join(cell, f"w-{wid}", "stdout.log"), "w")
        p = subprocess.Popen(cmd, cwd=cwd, env=writer_env(wid),
                             stdout=f, stderr=subprocess.STDOUT,
                             start_new_session=True)
        procs.append((wid, p))
        print(f"[{variant}] spawned {wid} pid {p.pid}", flush=True)
    # writers boot at very different speeds (node ~1 s, JVM/cljr/lpy
    # tens of seconds under 8-way contention); each runs DUR_MS from
    # its own start. The kill window gives every writer its full
    # duration if it boots within ~90 s; kills go to the process
    # GROUP (the clojure/dotnet launchers wrap children that would
    # otherwise survive).
    kill_after = DUR_MS / 1000 + 100
    while time.time() - t0 < kill_after:
        samples.append((round(time.time() - t0, 1),
                        os.path.getsize(journal) if os.path.exists(journal) else 0))
        if all(p.poll() is not None for _, p in procs):
            break
        time.sleep(1)
    for wid, p in procs:
        if p.poll() is None:
            try:
                os.killpg(p.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
    for wid, p in procs:
        p.wait()
    print(f"[{variant}] writers done", flush=True)
    # snapshot for recovery
    vdir = os.path.join(cell, "verify-domain")
    copy_domain(os.path.join(cell, "domain"), vdir)
    e_dur = journal_newest_epoch(os.path.join(vdir + ".journal")) \
        if variant != "A" else None
    # rids touched (for the 100m spot check)
    rids = set()
    ops = []
    for wid, _ in procs:
        lp = os.path.join(cell, f"w-{wid}", "ops.log")
        if not os.path.exists(lp):
            continue
        for line in open(lp):
            line = line.strip()
            if not line:
                continue
            try:
                m = parse_edn(line)
            except Exception:
                continue
            if isinstance(m, dict):
                ops.append(m)
                if m.get(Kw("rec")):
                    rids.add(m[Kw("rec")])
    with open(os.path.join(cell, "rids.txt"), "w") as f:
        f.write("\n".join(sorted(rids)))
    # verifier (fresh JVM recovery-open on the copy)
    out = os.path.join(cell, "verify.edn")
    r = sh(CLJ + ["-J-Xmx2g"] + ADDS +
           ["-Sdeps", "{:aliases {:t {:extra-paths [\"test\"]}}}", "-M:t",
            "-m", "eve.durability-workload-verify",
            vdir, out, os.path.join(cell, "rids.txt")],
           cwd=EVE, env=ENV, timeout=1800)
    open(os.path.join(cell, "verify-stdout.log"), "w").write(r.stdout + r.stderr)
    result = {"variant": variant, "e_dur": e_dur,
              "journal_samples": samples,
              "verify_rc": r.returncode}
    if r.returncode == 0 and os.path.exists(out):
        ver = parse_edn(open(out).read())
        result["verify"] = ver
        result["check"] = check(ops, ver, e_dur)
    else:
        result["check"] = {"error": "verifier failed", "rc": r.returncode}
    result["ops"] = summarize_ops(ops)
    with open(os.path.join(cell, "cell.json"), "w") as f:
        json.dump(result, f, indent=1, default=str)
    print(f"[{variant}] CHECK: {json.dumps(result['check'], default=str)[:400]}",
          flush=True)
    print(f"[{variant}] OPS: {json.dumps(result['ops'], default=str)[:300]}",
          flush=True)
    return result

def summarize_ops(ops):
    per = {}
    flushes = []
    for m in ops:
        if m.get(Kw("event")) == Kw("flush"):
            flushes.append(m)
        op = m.get(Kw("op"))
        if op:
            a = str(m.get(Kw("atom")))
            per[a] = per.get(a, 0) + 1
    fms = [m[Kw("ms")] for m in flushes if m.get(Kw("ms")) is not None]
    ats = sorted(m[Kw("at")] for m in flushes if m.get(Kw("at")))
    gaps = [round((b - a) / 1000, 2) for a, b in zip(ats, ats[1:])]
    return {"per_atom": per, "total_ops": sum(per.values()),
            "flush_events": len(flushes),
            "flush_ms_mean": round(sum(fms) / len(fms), 1) if fms else None,
            "flush_ms_max": max(fms) if fms else None,
            "flush_gaps_s": gaps}

def check(ops, ver, e_dur):
    incs = [m for m in ops if m.get(Kw("op")) == Kw("inc")]
    puts = [m for m in ops if m.get(Kw("op")) == Kw("put")]
    res = {"total_incs": len(incs)}
    recovered_counter = ver[Kw("counter")]
    res["recovered_counter"] = recovered_counter
    if e_dur is not None:
        conf = sum(1 for m in incs if m.get(Kw("epoch")) is not None
                   and m[Kw("epoch")] <= e_dur)
        res["confirmed_incs"] = conf
        res["counter_ok"] = conf <= recovered_counter <= len(incs)
    else:
        res["counter_ok"] = recovered_counter <= len(incs)
    # per-record checks
    classes = {str(k): v for k, v in ver[Kw("classes")].items()}
    atom2cls = {"rec-1k": "s1k", "rec-100k": "s100k", "rec-1m": "s1m",
                "rec-100m": "s100m"}
    violations = []
    by_rid = {}
    for m in puts:
        by_rid.setdefault((str(m[Kw("atom")]), m[Kw("rec")]), []).append(m)
    checked = 0
    for (atom_name, rid), ms in by_rid.items():
        cls = atom2cls[atom_name]
        vs = classes[cls][Kw("vs")]
        if rid not in vs:
            violations.append(f"{cls}/{rid}: rid missing from recovered")
            continue
        rv = vs[rid]
        logged = {m[Kw("v")] for m in ms} | {0}
        if rv not in logged:
            violations.append(f"{cls}/{rid}: recovered :v {rv} not in logged {sorted(logged)[:5]}")
        if e_dur is not None:
            # :v is a WRITER-LOCAL sequence, not a global order: the
            # recovered value must be exactly the put with the
            # greatest epoch <= E_dur (the journaled table names the
            # state at E_dur), or 0 when no confirmed put exists.
            conf = [m for m in ms
                    if m.get(Kw("epoch")) is not None and m[Kw("epoch")] <= e_dur]
            expected = (max(conf, key=lambda m: m[Kw("epoch")])[Kw("v")]
                        if conf else 0)
            if rv != expected:
                violations.append(f"{cls}/{rid}: recovered :v {rv} != last confirmed :v {expected}")
        checked += 1
    res["records_checked"] = checked
    res["violations"] = violations[:20]
    res["records_ok"] = not violations
    res["counts"] = {k: v[Kw("count")] for k, v in classes.items()}
    res["anomalies"] = {k: v[Kw("n-anoms")] for k, v in classes.items()
                        if v.get(Kw("n-anoms"))}
    res["anomaly_samples"] = {k: v[Kw("anoms")] for k, v in classes.items()
                               if v.get(Kw("n-anoms"))}
    res["atoms_whole"] = all(v.get(Kw("n-anoms"), 0) == 0
                             for v in classes.values())
    return res

def setup():
    dom = os.path.join(RUNS, "setup-domain")
    if os.path.exists(dom + ".root"):
        print("setup exists", flush=True)
        return
    os.makedirs(RUNS, exist_ok=True)
    r = sh(CLJ + ["-J-Xmx2g"] + ADDS +
           ["-Sdeps", "{:aliases {:t {:extra-paths [\"test\"]}}}", "-M:t",
            "-e", f"(require 'eve.durability-workload) "
                  f"(eve.durability-workload/setup-domain! \"{dom}\")"],
           cwd=EVE, env=ENV, timeout=3600)
    open(os.path.join(RUNS, "setup.log"), "w").write(r.stdout + r.stderr)
    print("setup rc", r.returncode, flush=True)
    if r.returncode != 0:
        sys.exit(1)

if __name__ == "__main__":
    setup()
    for v in sys.argv[1:]:
        run_cell(v)
