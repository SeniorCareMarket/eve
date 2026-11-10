# Fast overflow — mechanism design (landed)

Charter: checked i64 arithmetic for Eve (`+`/`-`/`*` throw `ArithmeticException`
on overflow, `+'`/`-'`/`*'` promote, `unchecked-*` wrap) whose check costs
~nothing in the hot path — the operator's constraint, in the spirit of C++
zero-cost abstractions. This document is the mechanism and the per-host
census. The semantic ruling itself is the operator's; the spike's measured
price rows are in REPORT.md and `spike/`.

**Landed state (2026-11-07, lane `lane-m/eve-overflow-land`).** The operator
ruled the semantics (DECISION.md, beside this doc): checked `+`/`-`/`*` throw
on **all** hosts; the cost varies by host, the semantics do not. The contract
is stated once in `adapters/completion/clojure`'s `eve.host-completion`
docstring and pinned per host by the shared test `eve.overflow-test`
(`test/eve/overflow_test.cljc`, in every host runner's shared-tests). Per-seat
state is the "Landed" column of §3's table: a seat is **landed** (its core
already throws, or its completion carries the binding), **finding** (no
Eve-side lever exists; the binding is specified and the gap is named, never
quiet), or **n/a by construction** (the seat has no fixed-width integer for
the contract to bind). §4 and §5 below are the spike's text, kept as the
record of the mechanism and its prices; where they say "landing is a
follow-on," the table in §3 supersedes them.

## 1. The mechanism shape

Everywhere the host gives us one, the checked op is exactly:

```
r = a OP b            ; the one fixed-width machine op, nothing added
if overflow-flag:     ; a conditional branch on the flag the op already set
    cold: throw       ; never taken in correct programs; pays only on overflow
return r
```

That is the whole design. The check is not a second computation: the CPU
already computes the overflow flag as a side effect of the add/subtract, and
a modern branch predictor prices a never-taken branch at ~0. The throw path
(exception object, stack walk) is real money spent only when an overflow
actually happens — which is precisely when the program has a bug to hear
about. Where a host has no flag, the cheapest correct fallback is named per
host in the census (§3) and its cost class is stated honestly — three hosts
have a real, nonzero cost and this design does not pretend otherwise.

Two corollaries:

- **Do not hand-roll the flag test in source on hosts that have the
  intrinsic.** Measured on the JVM (REPORT.md): a hand-written sign test
  `((r^a)&(r^b)) < 0` costs **+3.5…+5.3 ns/op** where `Math.addExact`
  costs **~0** — the JIT recognises the intrinsic and emits the flag
  branch; it does not fuse the hand-written sequence into one. The
  mechanism must be bound to the host intrinsic, never spelled out in
  portable code.
- **Multiply is the expensive sibling.** Add/sub overflow is a flag;
  multiply overflow is a *width* question (the true product is 128-bit).
  Every host's mul check is one extra compare at best (Go's hi word,
  C++'s `__builtin_mul_overflow` lowering) and a short sequence at worst
  (wasm, §3). Measured: ≲ +1 ns/op at the C++ layer; a positive delta at
  the raw JVM layer whose size this loaded box could not pin down
  (REPORT.md). Budget mul as "~1 ns class", add/sub as "free", and do not
  quote mul as free.

## 2. Where the JVM already stands (the ground-truth anchor)

On the JVM, Eve needs no new mechanism at all: `eve.host` expands `+` to
`clojure.core`'s own `+`, and JVM Clojure's `+` is *already* the checked
op — it throws `ArithmeticException` on long overflow (`+'` promotes).
The spike verified this at Eve's actual calling surface (Clojure 1.12.0):
core `+` overflow → `ArithmeticException`; `unchecked-add` wraps; and the
checked `+` is not slower than `unchecked-add` there (delta −2.0 / −0.6
ns/op across two runs — within noise, both dominated by the surrounding
loop machinery). The divergence Agent 3 found lives on the seats whose
core promotes (jank's), i.e. the fix is per-seat completion work, not a
new arithmetic tower.

## 3. Per-target census — Eve's 12 targets

Cost classes: **FREE** = delta within measurement noise of the unchecked
op (~0–1 ns/op). **~FREE** = a bounded sub-ns…~1 ns adder. **REAL** = a
named nonzero cost; the number or bound is stated, not smoothed over.

| # | Target | Native overflow mechanism | Cheapest correct fallback | Hot-path cost class |
|---|--------|---------------------------|---------------------------|---------------------|
| 1 | JVM | `Math.addExact/subtractExact/multiplyExact` — HotSpot intrinsics: op + overflow-flag branch to a cold throw stub | hand sign-flag test (measured *worse*: +3.5…+5.3 ns/op — do not use) | add/sub **FREE** (measured ≈0); mul **~FREE–REAL**, +1.7…+5.4 ns/op raw, ≈0 at the Clojure surface |
| 2 | Babashka | Same JVM intrinsics underneath (bb is JVM Clojure semantics under SCI); `clojure.core/+` in bb already throws via `Numbers` | — | **FREE relative to seat**: SCI interpretation prices an op at 10² ns class; the check is noise inside it |
| 3 | nbb | None at the number layer: JS numbers are doubles; Eve's i64 rides BigInt (`adapters/crypto/js-bigint`) | post-op BigInt range compare (2 compares) | **REAL but small**: BigInt ops are 10¹ ns class; two compares are a minor adder on that, not on a 1 ns op. Not zero — named |
| 4 | ClojureScript / Node | Same JS/BigInt reality as nbb | same | **REAL but small**, as nbb |
| 5 | Browser | Same JS engines (V8/JSC/SpiderMonkey), same BigInt route | same | **REAL but small**, as nbb |
| 6 | ClojureCLR | IL `add.ovf` / `sub.ovf` / `mul.ovf` — the overflow check *is* the instruction (C# `checked`); `clojure.lang.Numbers` already throws | `checked(...)` context in the completion's C# | **FREE** (flag-as-opcode) |
| 7 | Basilisp / Python | **None.** Python ints are arbitrary-precision; overflow does not exist natively — promotion is the host's nature | post-op range test `MIN <= r <= MAX` (two Python compares) per op | **REAL**: Python prices a bare int add at 10¹ ns class and two compares roughly double the arithmetic line. The most expensive seat for checked semantics, honestly stated. (Checking only at domain boundaries would be cheaper but changes the semantics — an intermediate overflow would pass silently — so it is not the same contract and is not proposed.) |
| 8 | Jolt | Jolt's roots are the JVM adapters (`adapters/coll/jvm`, `types/jvm`) with its own completion; numbers are JVM longs | `Math.*Exact` via its completion, as JVM | **FREE**, as JVM (the mechanism landed as designed; the census's premise — that jolt's core already throws — measured false at landing: its core promotes. See the landed table's row 8.) |
| 9 | glojure / Go | `math/bits.Add64/Sub64` return the carry/borrow — the hardware flag, exposed; `bits.Mul64` returns hi/lo, overflow iff hi ≠ sign-extension of lo | — (the intrinsic *is* the cheap path) | add/sub **FREE**; mul **~FREE** (one extra word compare) |
| 10 | clojerl / BEAM | **None.** Erlang integers are arbitrary-precision by design | post-op range compare (BEAM small-int compares are ~ns class) | **REAL but small**: a compare per op on a VM whose arithmetic already allocates on growth; modest relative adder, not zero |
| 11 | jank | C++ `__builtin_add_overflow/sub_overflow/mul_overflow` (clang/gcc) = LLVM `*.with.overflow` intrinsics: op + flag branch, cold throw | hand sign-flag in C++ (compiles to nearly the same; intrinsic preferred) | add/sub **FREE** (measured ≈0); mul **~FREE** (measured ≲ +1 ns/op). Seat context: jank's object-number `+` prices at ~300 ns/op in `jank run` mode — the check is two orders of magnitude below seat noise |
| 12 | ValueScript seat (wasm) | **None.** wasm `i64.add` has no trapping variant and no flags register to read | add/sub: sign trick `((a^r)&(b^r)) < 0` = 3 extra i64 ops; mul: 32-bit limb decomposition (~6 ops) or divide cross-check | add/sub **REAL but tiny** (~2–3 extra ops, sub-ns…~1 ns at wasm speeds); mul **REAL** (~5–15 cycles). A future kernel `i64.add_checked` intrinsic could close this to FREE; not assumed by this design |

Reading of the table: the flag exists — and the design is free — exactly
on the seats with fixed-width native integers and a systems compiler
(JVM, CLR, Go, jank, and by inheritance bb/Jolt). The three
arbitrary-precision hosts (Python, BEAM, and the JS family via BigInt)
pay a compare-shaped cost that is small next to those hosts' own op
prices but is not zero, and the wasm seat pays a few extra ops. Nobody
gets fudged into "free."

### Landed state per seat (2026-11-07)

| # | Target | State | Where / what |
|---|--------|-------|--------------|
| 1 | JVM | **landed** | By construction (its `+` is clojure.core's, which throws); pinned by `eve.overflow-test`. No mechanism added — none was needed. |
| 2 | Babashka | **landed** | By construction (SCI over the JVM's Numbers, which throw); verified by direct evaluation through the completion (throws / promotes / wraps). The seat check in its runner could not run on the landing box: its Babashka (1.3.190) is too old for the tree (it cannot load `eve.array`); the test is wired into its runner for a current Babashka. |
| 3 | nbb | **n/a by construction** | The seat has no fixed-width integer type: general arithmetic is doubles (no i64 contract exists to bind) and exact integers are BigInts, whose arithmetic is exact — it promotes, it never wraps (pinned by the test's `:cljs` arm; verified on nbb 1.6.214: 1 test, 2 assertions, green). Eve's exact wide arithmetic on JS is limb-based (`eve.net.u64`, mod 2^64 by design). There is no i64 op on this seat for a completion to make checked; the divergence from the throw contract is this row, named. |
| 4 | ClojureScript / Node | **n/a by construction** | As nbb; verified in the node test build's `all` suite (the `:cljs` arm runs clean; the suite's only failures are thread-timing assertions under load, unrelated to arithmetic). |
| 5 | Browser | **n/a by construction** | As nbb. |
| 6 | ClojureCLR | **landed** | By construction (clojure.lang.Numbers throws, as on the JVM); seat check green (3 tests, 24 assertions). The check needed a runtime repair first: the landing box's .NET install is corrupt (its libhostfxr.so is 0 bytes), so it ran under a fresh .NET 8 runtime installed beside it. |
| 7 | Basilisp / Python | **landed — REAL cost as censused** | `adapters/completion/basilisp`: `+`/`-`/`*`/`inc`/`dec` patched to range-check two i64-range integer operands and throw the completion's ArithmeticException (resolved through its var — a module-attribute lookup answers AttributeError on this seat, measured). The completion also interns the `+'`/`-'`/`*'` basilisp.core lacks, and its own unchecked wrap family is re-based on `basilisp.lang.numbers` so it wraps instead of throwing through the checked `+`. An operand already past i64 is indistinguishable from a promoted long on this seat and keeps Python's answer — the seat's named approximation. Seat check green (3 tests, 24 assertions). |
| 8 | Jolt | **landed at the var surface — compiled-call FINDING open** | NOT by construction (the census's assumption was wrong, measured at landing): jolt's core promotes. `adapters/completion/jolt` binds `+`/`-`/`*`/`inc`/`dec` to `Math/*Exact` for Long pairs (var roots + arity-exact `:inline`s): var-mediated calls throw (pinned by the test's `:jolt` arm; seat check green, 17 assertions). **Finding F-JOLT-COMPILED-ARITH:** jolt's compiler emits a direct `+` call as a native promoting op that consults neither the var root nor `:inline` metadata (measured three ways), so compiled direct calls in Eve code still promote on this seat; no Eve-side surface reaches them. Upstream observation for jolt's compiler, with the repro in the landing lane's report; the var-level binding is the interim form, named as such. |
| 9 | glojure / Go | **landed — REAL cost, follow-on named** | `adapters/completion/glojure`: `+`/`-`/`*`/`inc`/`dec` re-rooted to an exact-promote range check that throws. The thrown class is `RuntimeException`: this runtime carries no `ArithmeticException` type (measured, bare and java.lang-qualified), so the seat's divergence is the class name, never the semantics. The flag binding (math/bits carry) is Go-level, in eve's Go package for the seat (impls/glojure): the priced follow-on that retires this seat's REAL cost. Seat check green (3 tests, 24 assertions). |
| 10 | clojerl / BEAM | **FINDING — not landed** | The BEAM's integers are unbounded and clojerl gives Eve no lever: its `alter-var-root` is unsupported (adapters/core/beam's eve.host says so of its atom ops), and Eve has no completion root on this seat. The binding is specified — the range compare of §3 over the core ops — and waits on a clojerl-side completion surface (an upstream surface, not an Eve patch). Until then the seat promotes; the test's `:clje` arm pins exactly that, labeled as this finding. |
| 11 | jank | **landed** | `adapters/completion/jank`: the divergent seat closed. `eve_arith`'s checked ops bind `__builtin_*_overflow` (add/sub FREE, mul ≲ +1 ns/op per the spike's rows); `+`/`-`/`*`/`inc`/`dec` re-pointed from the promoting ops to them, the throw the seat's ex-info "integer overflow". The primed ops are bound exact too: jank's own `promoting_sub` wraps at the i64 boundary (measured at landing: `(-' MIN 1)` answered the wrapped value in a big box), so `+'`/`-'`/`*'` are re-rooted to `eve_arith` exact ops (var roots only — a 2-arity `:inline` on `-'` breaks its 1-arity call sites on this seat, measured). `unchecked-*` rebuilt on the promoting ops + a mod-based wrap64. Seat check green (3 tests, 24 assertions). Nothing upstream was patched: jank exposes no Clojure-level checked op, so the intrinsic lives in the completion's cpp/raw, where this completion already keeps its C++. |
| 12 | ValueScript seat (wasm) | **FINDING — not landed from Eve's tree** | On this seat Eve code's `+` is ValueScript's own clojure.core `+` (vs/src/vs/core.vs, in the atom.broker repo — not Eve's tree, and not Eve's completion surface: hosts.edn's :vs roots carry the shared completion only). The binding belongs there: the wasm sequences of §3 row 12 (sign trick for add/sub, limb decomposition for mul) in VS's core arithmetic, or a kernel `i64.add_checked` intrinsic closing it to FREE. Named here so the seat is an owned item, not a quiet divergence. |

## 4. Adapter shape — where this lives

Eve's standing rule: shared semantics in shared code, host mechanism in
the per-host adapter/completion layers. Eve already has the exact seam;
nothing new needs inventing:

- **Shared semantics (one place):** Eve's `+`/`-`/`*` mean *checked*,
  `+'`/`-'`/`*'` mean *promote*, `unchecked-*` mean *wrap* — the JVM
  Clojure contract, stated once. On hosts whose core already implements
  it (JVM, bb, CLR) nothing changes; jolt's core promotes and takes the
  completion binding of the landed table's row 8.
- **The binding seam:** `eve.host` (`eve/adapters/core/<host>/src/eve/host.*`)
  — its names are macros expanding to the host core's own calls with "nothing
  added at run time", which is the zero-cost shape in Eve's own idiom — plus
  `eve.host-completion` (`eve/adapters/completion/<host>/src/eve/host_completion.*`),
  which loads first and makes a host's core Clojure-shaped where it isn't.
  The checked trio binds there, one completion at a time:
  - `completion/clojure` (JVM/bb/cljr/jolt compiler side): bind to
    `Math/addExact` etc. under `^long` hints (or `clojure.lang.Numbers/add`,
    already checked) — expected to be a no-op or near-no-op, since the
    host core is already checked.
  - `completion/jank` (`host_completion.jank`): the seat that actually
    diverges — today its core `+` promotes. Bind the checked trio to
    jank's runtime checked ops (the C++ `__builtin_*_overflow` layer). If
    jank exposes no Clojure-level checked op, the completion carries the
    sign-flag form in jank source as the interim binding (costlier than
    the intrinsic in isolation, invisible at the seat's ~300 ns/op op
    price) and the intrinsic binding is filed as an upstream observation
    with a repro, per the no-patches-to-jank rule.
  - `completion/basilisp` (`host_completion.lpy`): range-test binding.
  - `completion/glojure` (`host_completion.glj`): `bits.Add64` carry binding.
  - JS-family cores (cljs/nbb/browser, via `adapters/core/cljs-compiler`
    + the BigInt adapters): BigInt range-compare binding on the i64 path
    only — the double path never claimed i64 semantics.
  - `core/beam` (clojerl): range-compare binding.
  - VS seat: the wasm sequences of §3 row 12, in the VS core adapters.
- **Throw identity:** the completion throws (or maps to) the host object
  Eve already treats as `ArithmeticException`, so portable
  `(catch ArithmeticException …)` keeps working per host — exception
  identity across hosts is already the completion layer's job.

No shipped Eve arithmetic changes in this lane: the spike proves the
mechanism and prices it; landing is a follow-on per-seat completion
change, smallest first (jank, the one divergent seat).

**Landing note (2026-11-07).** The follow-on landed as the spike shaped
it: the shared statement in `completion/clojure`, the divergent seat
(jank) bound in its completion's cpp/raw, the two arbitrary-precision
seats with an Eve-side lever (basilisp, glojure) bound in their
completions at the censused REAL cost, and the two seats with no Eve-side
lever (BEAM, the VS seat) carried as findings in §3's landed table with
their bindings specified. Nothing was hand-rolled on a flag-native seat,
per §1's corollary.

## 5. What the spike established (summary; full numbers in REPORT.md)

- JVM raw layer: `addExact` delta vs unchecked ≈ 0 across three runs;
  the hand-written flag test is +3.5…+5.3 ns/op (never hand-roll);
  `multiplyExact` delta positive (+1.7…+5.4 under load — magnitude needs
  one quiet-box confirmation run).
- JVM at Eve's surface (Clojure core `+` vs `unchecked-add`): delta ≈ 0;
  core `+` already throws. The JVM seat is done by construction.
- jank's layer (C++ `__builtin_*_overflow`): add delta ≈ 0; mul ≲ +1 ns/op.
- Verdict against the target (checked within ~0–1 ns/op of unchecked):
  **hit for add/subtract on both required seats; hit for multiply at the
  jank layer; multiply on the raw JVM layer is the one row not proven to
  target on this box** — and at Eve's actual JVM surface (where the op
  rides inside Clojure's machinery) even that delta disappears into noise.
