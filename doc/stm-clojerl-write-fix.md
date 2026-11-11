# Clojerl S3 write failure — release 0.9.3.2

## Re-derived on the release

At release 9c9fef4acf6a30087480548f66d2d68ec61ae7f9 with clojerl
4ad14e57df85d30cc3332308f861f4e174383483 and OTP 27.3.4.19, the original
stm-s3-probe-main passes construction/deref/no-op/read but reports
`:STEP-DIED :tx-write-s3`, `:function_clause`, and leaves the ref at 41.
The ordinary runner exits zero because this diagnostic probe catches failures;
its STEP lines, not that exit status, determine success.

## Actual cause, not the Throwable warning

Capture the raw BEAM stack *inside* run-in-transaction's catch using clojerl's
`e :stack raw-stack` catch binding, before it rethrows the reason. The first
frame is `clojerl.Atom:swap/2`, clojerl/src/erl/lang/clojerl.Atom.erl:62:
its first argument is an Eve shared atom, not a clojerl.Atom. The next frames
are Eve's cell-cas! (original stm.cljc:362), install! (:778), commit! (:837),
and run-in-transaction (:931).

`clojerl.Atom:swap/2` pattern-matches its own type/id/value fields; it has no
clause for an Eve atom. STM failed to import the existing Eve host atom seam
and therefore called the native core swap! directly on its ref cell.
`adapters/core/beam/src/eve/host.clje:274-286` already dispatches native atoms
to core and Eve atoms to IAtom. The fix consumes this seam for swap!, reset!,
and compare-and-set! (the latter two also cover seated clock/lock paths).
On JVM/Babashka the host macros expand to the same core operations as before.

The unresolved Throwable warning is NOT this root cause. That symbol is
accepted as a catch pattern binding by clojerl. It remains unchanged and the
original S3 probe now passes every step: write returns 42, deref-after is 42,
and stats show three attempts / three commits / zero retries. The new abort
regression also verifies that an exception still propagates and does not install
staged writes.

## Regression

`test/eve/stm_write_test.cljc`: two tests / 14 assertions covering own-domain
cells, read/no-op, alter, set, history, watches, abort isolation and the next write.
JVM also runs test/eve/stm/portable.cljc: 15 checks including 1600 commutes,
2000 two-ref transfers, validators, watches and held sends. This is sequential
algebra coverage, NOT a contention or multi-process proof.
Babashka passes the two-test / 14-assertion write regression too. Its optional
full portable suite was stopped after ~77 seconds while still in the first
1600-commute workload (six checks had passed); do not count that interrupted
suite as a full Babashka pass.

Local evidence retained under /workspace/eve-s3-rootcause/: release-before.log,
release-raw-stack.log, release-fixed-s3.log, clje-regression.log, jvm-stm.log,
bb-stm.log and bb-regression.log. The unmodified failing S3 was not repeatedly rerun; one baseline
and one stack-instrumented diagnostic established the failure before the fix.
