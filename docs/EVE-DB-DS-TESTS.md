# #56 Eve producer test evidence — 2026-11-11 ET

Implementation: `c43ec01` MapEntry, `375500d` interpreted-JS byte storage,
`4c670a0` vector/JS MapEntry face, `c94843e` isolated nth regression, plus
this commit's Basilisp scalar byte initialization. Release base `a11a182`.
No release merge or tag. Logs are in `docs/eve-db-ds-logs/`.

## Isolated regression proof

| Host | Tests | Assertions passed | Fail/errors | Scope |
|---|---:|---:|---|---|
| JVM, Clojure 1.12.0 / Temurin 21.0.13 | 12 | 152 | 0/0 | MapEntry + bytes + existing collection/type/hash regressions |
| nbb 1.6.214 / Node 24.22.1 | 12 | 152 | 0/0 | same |
| Basilisp 0.5.1 / Python 3.13, fresh namespace cache | 12 | 152 | 0/0 | same |
| bb 1.13.226 | 10 | 81 | 0/0 | byte storage + existing collection/type/hash regressions, **not native MapEntry parity** |
| bb 1.13.226 native MapEntry contract | 2 | 16 | 17/38 | **OPEN**, full failure log retained |

The focused MapEntry + byte-storage contract alone is 3 tests / 76
assertions on JVM, nbb and fresh-cache Basilisp. It covers native
`map-entry?`, `key`, `val`, `vector?`, `nth` (including not-found), count,
seq, symmetric equality and hash with vectors, map conj, vector operations,
and the Eve codec round trip. No test is skipped to call bb native parity
PASS. On bb the Eve protocol representation exists but native-core
visibility is unresolved; wakes DISPATCH #2 owns that independent adapter
work. The newly added contracts are registered with all shared runners,
so the bb default runner will expose this gap until that work lands.

The regression set is:

```
eve.map-entry-test eve.host-bytes-test eve.collection-hash-test
eve.deftype.type-id-test eve.hamt-util-test
```

The green bb subset omits `eve.map-entry-test`; its separate strict run
is red as shown above. Each process ran in a different directory under
`/workspace/ds-wt-eve-regress/<host>`, with absolute classpath entries, so
relative test domains cannot collide between hosts. Production sources
were held fixed during these proof runs. Disk preflight had about 32 GiB
free; this task's checkout/cache/log use stayed below 1 GiB (half-free
budget at start was about 17 GiB).

### Reproduce

From this repo, obtain `bb classpath <host>` for jvm/bb/nbb/lpy and add the
absolute `test` directory. Run in a fresh **per-host** working directory:

```
bb --classpath "$BB_CP:$TEST" -m eve.runner.bb-main test <namespaces>
nbb --classpath "$NBB_CP:$TEST" -m eve.runner.nbb-main test <namespaces>
```

For JVM use Clojure 1.12.0, test.check 1.1.1, the absolute `jvm` classpath
and `test`, then require the named namespaces and run
`(apply clojure.test/run-tests '[<namespaces>])`; exit nonzero on failures
or errors. For Basilisp pass every absolute root as `-p` in **reverse**
order, set a fresh `PYTHONPYCACHEPREFIX`, then:

```
basilisp run <reversed -p roots> -n eve.runner.lpy-main test <namespaces>
```

Basilisp's supported Eve runner counts the assertions only if the tests
are compiled into its fresh cache. Calling clojure.test/run-tests or
basilisp.test/run-tests directly is not supported on this host; the
exploratory commands failed resolution and were stopped in favor of the
repository runner.

## #404 nth bootstrap

Both of these pass in independent bb processes:

```
bb -cp "$(bb classpath bb)" test/eve/seam_bootstrap.clj
bb -cp "$(bb classpath bb-minimal)" test/eve/seam_bootstrap.clj
```

The script initially requires only crypto, hamt-util and thread-host,
then compiles/evaluates the formerly failing `(nth (sort ...) ...)`
function and asserts `eve.core/nth` resolves. The release's existing E1
`eve-core-ready?` fix is sufficient; no second nth was added.

## Full-suite attempts — NOT full-suite PASS

All four official/default suite attempts were leashed to 600 seconds and
ended with **exit 124**. Raw logs are retained; no complete totals exist.

| Host | Command | Last observed progress / errors |
|---|---|---|
| JVM | `timeout 600 clojure -J-Xmx1g -M:jvm-test` | Reached eve.net.seen-set-test; had loaded an earlier version of the new bytes test with an untyped scalar initializer (fixed and proven by the isolated 152-assertion rerun) |
| bb | `timeout 600 bb --classpath "$BB_CP:test" -m eve.runner.bb-main test` | Reached eve.rrb-vector-test |
| nbb | `timeout 600 nbb --classpath "$NBB_CP:test" -m eve.runner.nbb-main test` | Reached eve.thread-test; encrypted-domain/keyring and domain reopen tests report unimplemented host operations |
| Basilisp | `timeout 600 basilisp run <reversed -p roots> -n eve.runner.lpy-main test` | Reached numeric-tags tests; decimal values beyond int64 unscaled fail with ArithmeticException integer overflow |

These broad exploratory runs shared the repository working directory and
were started before the final bytes-test correction. They are **not**
used as isolated release-gate evidence. They also mutated tracked slab
fixture metadata under `eve/`; only this task's generated modifications
were restored after all four processes exited. Future full runs must use
isolated working directories and a larger venue leash.

A first cold Basilisp compile had a transient `e.lang/IPersistentStack`
resolution failure while source was still being edited; the stable,
isolated cold-cache run passes all 152 assertions. The earlier normal-
cache result with 0 counted assertions is not used as the final proof.

Remaining: bb native MapEntry visibility, complete long suites in isolated
work directories, other hosts not present here, and remaining in-scope
GAPS rows (muse DISPATCH #37). **Do not bank RESULT #56 as done.**
