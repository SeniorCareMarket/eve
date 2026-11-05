# JVM heap-grade — STATUS

Branch: claude/eve-jvm-heap-grade · Base: origin/main @ d241de3fbc27e5dd80d5f1e17661103b0680b9c0 (verified via ls-remote 11:51 ET 2026-11-05)

## Log (US Eastern)
- 11:51 — STEP 0 done: base verified, branch created and pushed. Next: read Eve JVM collection/slab code, set up REPL.
- 11:52 — Toolchain: clojure CLI installer blocked by the session's sandbox policy (remote script); worked around with Maven-fetched jars (clojure 1.12.0, spec, test.check) + deps.edn :paths as a hand-built classpath; live socket REPL on the JVM seat (OpenJDK 21, 4 cores, load ~0.3).
- 11:54 — REPL probes (first pass, same session, min-of-3 batches; NOT yet the banked interleaved run):
  - eve map get, 100-entry map built in a heap slab ctx, called on the collection directly: **0.76–2.0 µs** (stock 36 ns)
  - eve map get, 1k-entry map direct: **1.3 µs** (one 15.8 µs outlier round) (stock 58–61 ns)
  - `(get @atom k)`, 100-map root: **54–99 µs** ← the spike's ~10² µs "lookup" class appears only here, and it is deref materialization (eve->clj of the whole root), not the lookup
  - `e/$ (get @atom k)`: 2.4–6 µs (100 root), 2.8–3.5 µs (1k root)
  - deref scalar root: **0.6–6.2 µs** (spike: ~104 µs) — stock 26–31 ns
  - deref 100-map root: **54–62 µs** (spike: ~1.78 ms); 1k-map root 342–363 µs
  - swap! inc: **30–114 µs** (spike: ~178 µs) — reproduces the order — stock 32 ns
  - map assoc build, 1k entries in a fresh heap ctx: **~20 µs/assoc** (spike: 0.5–5 ms)
  - capacity wall: 10k-entry map build **dies** `coalesc-alloc!: out of memory` (reproduces); 1k-element vec **does NOT die** (spike said it does); 100k vec dies.
  - Reading of the lookup path (src/eve/map.cljc:430 hamt-get): the HAMT is already navigated in place in the slab (bitmap reads, hash compare, key-bytes compare at the stored position); only the found value's bytes are decoded. "Deserialize on access" is not what the collection lookup does.
- 11:55 — Banked baseline: doc/jvm-heap-grade/bench/baseline.clj, one JVM session, 18 arms (Eve + stock control per shape) × 3 interleaved rounds; output as printed in doc/jvm-heap-grade/bench/baseline-run1.out. Direct-lookup decomposition probed in the same session (REPORT §2).
- **STOP — stop condition "the baseline does not reproduce" hit.** Map lookup measures ~0.8–1.1 µs (spike: ~119–134 µs), vec nth ~0.32 µs, deref scalar ~0.6 µs (spike ~104 µs), deref 100-map ~49–63 µs (spike ~1.78 ms), collection assoc ~4 µs (spike 0.5–5 ms); 1k vec does not hit the wall. swap! (~21–25 µs inc, ~140–185 µs assoc on a 100-map root) and the map capacity wall (dies between 1k and 5k entries) do reproduce. The ~10² µs "lookup" class reproduces only as `(get @atom k)`, which is deref materialization. And the lookup is not decode-on-access: decode is ~38 ns of ~800 ns; the HAMT is already walked in place. The in-place-read design this step was to build is therefore aimed at a mechanism the code does not have. Steps 2–3 (design, prototype) not started. Full report: REPORT.md.

## STEP 2 — automatic guard vs whole-root materialization (US Eastern)
- 13:32 — STEP 2 started (cohost agent, ordered via lane M 12:01, re-confirmed 13:25). Branch tip f97dde6. Reading step-1 harness + atom read path (atom.cljc deref / read-pinned / read-region / $). Next: toolchain + reproduce step-1 deref numbers.
- 13:41 — Toolchain re-made (fresh container): Maven jars + deps.edn :paths, socket REPL. First REPL probes of the SAME-DOMAIN question (anonymous heap atoms share one domain sio — verified `identical?`):
  - plain `@a` → `PersistentHashMap` (materialized). Inside `$`: `EveHashMap`, header off **806 == the atom slot's ptr 806**, sio `identical?` to the atom's; `(get v :k3)` → child `EveHashMap` @689 (in place).
  - `(swap! b (fn [_] (get @a :k3)))` outside `$`: b's slot ptr **813 (a copy: deref materialized, swap re-wrote)**. Same inside `$`: b's slot ptr **689 == a's subtree ptr** (structure shared, no copy).
  - Same inside `$` into a named atom (its own heap domain): throws `swap!: Eve value was allocated in a different slab domain` (resolve-new-ptr, atom.cljc:752).
  - So today: same-domain pointer equality and no materialization already exist — but only under the manual `$` (region-deref). Next: implement the guard extent at the atom read path (eve.guard + mmap-deref hook + per-domain foreign policy).
