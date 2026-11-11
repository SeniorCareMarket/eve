# Eve collation round 2 — e2 only

Times below are America/New_York (ET).

## Shas

- Pre-merge baseline: `13421bebb9a2786cd1827974d27268bd15331242` (`lane-m/eve-db-ds-gaps-eve`).
- Merged in: `lane-m/eve-db-ds-gaps-eve-e2` at `46ce4bae627a8953446385396d6b7136e6e505f8`
  (BEAM persistent associative protocols, `eve.clock`, `eve.uuid` with squuid;
  see `eve/docs/one-eve-work/e2.md`).
- Collated tip (this branch, after `--no-ff` merge of e2):
  `5aa0a096cbea06918ba15f59bbb1fbf8728451f9`.

## e3 not merged

`lane-m/eve-db-ds-gaps-eve-e3` at `f1d1cd58d7480d4ce14601aac789b1d2c440b2cb`
is **not** on this tip. John rejected its new `eve.type` identity registry; that
work is being redone on top of the existing `eve.deftype.registry`.

An e3 merge was created locally as `e2701d67e76` during an earlier attempt,
then dropped with `git reset --hard 5aa0a096cbe` before any push. The remote
stayed at `13421bebb9` throughout; no force-push was used.

## Conflicts

None. e2 was a clean `--no-ff` merge onto `13421bebb9`. The overlapping runner
files only arise if e3 is also merged, which it was not.

## Scope of the gates

Worker regressions from e2: `eve.clock-uuid-test`, `eve.associative-transient-test`.

Shared Eve suites (baseline and merged): `eve.map-entry-test`,
`eve.deftype.type-id-test`, `eve.deftype.int-map-test`, `eve.deftype.rb-tree-test`,
`eve.collection-hash-test`, `eve.sorted-coll-test`, `eve.rrb-vector-test`,
`eve.heap-class-growth-cap-test`.

Basilisp omitted `eve.rrb-vector-test` on both sides (the lpy runner already
excludes it: a 40,000-element vector is too slow). Hosts: JVM (OpenJDK 21),
bb 1.13.226, nbb 1.6.214, basilisp 0.5.1.

All runs used `flock /workspace/ds-wt/one-eve-workers/test.lock`, checked
`df -Pk` / `df -Pi` first (refuse under 50k free inodes), and cleaned
`target/lpycache-test` after basilisp. Logs:
`/workspace/ds-wt/one-eve-workers/collation-eve-2-logs/`.

Exact merged commands, from `eve/`:

```
clojure -J-Xmx1g -M:jvm-test -n eve.map-entry-test -n eve.deftype.type-id-test -n eve.deftype.int-map-test -n eve.deftype.rb-tree-test -n eve.collection-hash-test -n eve.sorted-coll-test -n eve.rrb-vector-test -n eve.heap-class-growth-cap-test -n eve.clock-uuid-test -n eve.associative-transient-test

bb test:bb eve.map-entry-test eve.deftype.type-id-test eve.deftype.int-map-test eve.deftype.rb-tree-test eve.collection-hash-test eve.sorted-coll-test eve.rrb-vector-test eve.heap-class-growth-cap-test eve.clock-uuid-test eve.associative-transient-test

bb test:nbb eve.map-entry-test eve.deftype.type-id-test eve.deftype.int-map-test eve.deftype.rb-tree-test eve.collection-hash-test eve.sorted-coll-test eve.rrb-vector-test eve.heap-class-growth-cap-test eve.clock-uuid-test eve.associative-transient-test

bb test:lpy eve.map-entry-test eve.deftype.type-id-test eve.deftype.int-map-test eve.deftype.rb-tree-test eve.collection-hash-test eve.sorted-coll-test eve.heap-class-growth-cap-test eve.clock-uuid-test eve.associative-transient-test
```

Baseline used the same suite list without the two e2 namespaces.

## Per-host table

| Host | Baseline `13421bebb9` | Merged `5aa0a096cbe` | New red? |
| --- | --- | --- | --- |
| JVM | 96 tests, 1012 assertions, 0 fail / 0 error, 296s | 100 tests, 1068 assertions, 0 fail / 0 error, 294s | no |
| bb 1.13.226 | 95 tests, 1007 assertions, 0 fail / 0 error, 218s | 99 tests, 1063 assertions, 0 fail / 0 error, 222s | no |
| nbb 1.6.214 | 94 tests, 983 pass, 0 fail / 0 error, 136s | 98 tests, 1039 pass, 0 fail / 0 error, 137s | no |
| basilisp 0.5.1 | 72 tests, 915 pass, 0 fail / 0 error, 653s | 76 tests, 971 pass, 0 fail / 0 error, 564s | no |

On every host the merged run added exactly 4 tests and 56 assertions, matching
e2's clock/UUID plus mixed persistent/transient associative regressions. No
shared-suite count dropped, and no new failure or error appeared on JVM, bb,
nbb, or basilisp.

## Other hosts (not required for this gate)

jank was present. A baseline jank run of the same shared suites timed out at
600s (`rc=124`) mid-`eve.sorted-coll-test`, after pre-existing MapEntry reds
in `eve.map-entry-test`:

- `FAIL` `map-entry-contract`: expected `(map-entry? entry)`, actual `(not (map-entry? [:k 42]))`
- `ERROR` `map-entry-contract`: `Uncaught C++ exception of jank's runtime, not in assertion: invalid map entry: 'opaque_box'`
- `FAIL` `map-entry-memory-roundtrip`: expected `(map-entry? readback)`, actual `(not (map-entry? [:key 42]))`

Exact baseline jank command (from the base worktree `eve/`):

```
LC_ALL=C.UTF-8 timeout 600 jank --module-path "$(bb classpath jank):adapters/completion/jank/test:adapters/slab-io/test:test" run-main eve.runner.jank-main -- test eve.map-entry-test eve.deftype.type-id-test eve.deftype.int-map-test eve.deftype.rb-tree-test eve.collection-hash-test eve.sorted-coll-test eve.rrb-vector-test eve.heap-class-growth-cap-test
```

That red is on `13421bebb9`, not introduced by e2. e2's own report already
had its two new namespaces green on jank (4 tests / 56 assertions). The
merged four-host gate did not wait for a full jank re-run of the collection
suites.

Native BEAM remains unproven, as in e2's report: two same-kind missing-`long`
load failures (`eve.deftype_proto.data` was fixed on e2; `eve.tally/popcount32`
is still a producer issue outside this merge). The BEAM `IAssociative`
translator change is implemented but not certified by a native green gate.

CLR was not run (`cljr` is not installed).
