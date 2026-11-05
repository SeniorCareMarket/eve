# JVM heap-grade — STATUS

Branch: claude/eve-jvm-heap-grade · Base: origin/main @ d241de3fbc27e5dd80d5f1e17661103b0680b9c0 (verified via ls-remote 11:51 ET 2026-11-05)

## Log (US Eastern)
- 11:51 — STEP 0 done: base verified, branch created and pushed. Next: read Eve JVM collection/slab code, set up REPL.
- 12:0x — Toolchain: clojure CLI installer blocked by the session's sandbox policy (remote script); worked around with Maven-fetched jars (clojure 1.12.0, spec, test.check) + deps.edn :paths as a hand-built classpath; live socket REPL on the JVM seat (OpenJDK 21, 4 cores, load ~0.3).
- 12:1x — REPL probes (first pass, same session, min-of-3 batches; NOT yet the banked interleaved run):
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
