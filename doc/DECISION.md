# Decision — Eve arithmetic overflow semantics

**Decided by John Newman, 2026-11-07 ~12:29 AM ET** (on the zero-cost
overflow proof lane's report; "Let's go with that. Let's get that
merged in."):

> Checked `+`/`-`/`*` THROW `ArithmeticException` on i64 overflow on ALL
> hosts — stock Clojure semantics. Primed `+'`/`-'`/`*'` promote;
> `unchecked-*` wrap. The semantics are the same everywhere; the COST
> varies by host: a host with a native overflow flag binds the intrinsic
> and pays ~0; a host without one pays a named small compare cost and
> still throws. Overflow is not dropped on hosts that lack the flag, and
> the check is never hand-rolled where an intrinsic exists.

This is the answer to cohost Agent 3's arithmetic question (should Eve's
`+`/`-`/`*` throw on overflow like the JVM, with the primed ops
promoting?): **yes** — backed by the proof lane's measured evidence:

- Add/subtract checked-vs-unchecked delta ≈ 0 ns/op on both required
  seats (JVM intrinsic; C++ `__builtin_*_overflow` at jank's layer);
  jank multiply ≲ +1 ns/op. A hand-written sign-flag check costs
  +3.5–5.3 ns/op — forbidden: bind the intrinsic, never hand-roll.
- On the JVM no mechanism is needed: Eve's `+` already expands to stock
  Clojure's `+`, which already throws (verified at Eve's surface).
- Jank was the one divergent seat (its core promoted); its completion
  now binds the C++ overflow builtins.
- Flag-native seats (JVM, Babashka, ClojureCLR, Jolt, glojure, jank)
  are free/~free; Basilisp/Python and BEAM pay a range compare; the JS
  family has no fixed-width integer (BigInt is exact); the VS wasm seat
  pays a few ops. Nobody was fudged into "free."
- One measurement remains owed: quiet-box confirmation of the raw-JVM
  `multiplyExact` delta (positive under load, magnitude unpinned).

Landed state per seat: `doc/arithmetic-overflow.md` §3 (landed table).
Contract test: `eve.overflow-test`. Landing lane:
`lane-m/eve-overflow-land` (onto the 0.9.3.1 release branch,
`lane-m/eve-release-0931`).
