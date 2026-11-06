# AUDIT REPORT — release/eve-0.9.3, merged tree (PR #27 in)

- Tree audited: origin/release/eve-0.9.3 tip ca5851c7 ("Merge pull request #27"), read from the fetched ref.
- Worktree: ~/workspace/wt/eve-093-audit, branch audit/eve-093. Banks are LOCAL (direct push to SCM/eve refused for this credential); coordinator lands this report via the connector.
- Date: 2026-11-06, ~15:20–16:05 ET. The separate verification lane's JVM suite + jank-seat identity runs on this tree were NOT duplicated here.

## VERDICT

**No missed code blocker found.** The merged tree is releasable on the evidence below.
Three findings should be closed at or before the cut — one test-record gap and two
release-face stamps. None requires a code change to the mechanism.

## FINDINGS (severity order)

### F1 — Row 4: four jank map-hash test rows ship with no disposition recorded in the tree
`test/eve/collection_hash_test.cljc`, deftest `an-eve-collection-hashes-as-the-host-value`:
the map assertions (L23 `{:a 1 :b 2}` vs `m/hash-map`; L33 nested `{:k1 {:i 10 :s "v10"}}`;
L34 `{:k [1 2] :s #{3}}`; plus the constructor-map row from VS Agent 3's sweep) fail on the
jank seat because they presume host-hash equality — the presumption John's Nov 5
hash-facade ruling rejects (Eve's hash wins inside Eve; re-home on entry). The failures
are known and explained, but the explanation lives ONLY in fleet records (REPORTS.md,
Agent 3's price table). Searched the shipped tree: no comment in the test file, no entry
in doc/hosts/jank.md's defect list (ends at defect 28 + the blind-spot amendment), no
CHANGELOG note. A releaser running the seat suite meets four unexplained reds.
(An empty local ref `fix/eve-093-map-hash-tests` exists but points at the release tip
itself — no fix commits.) Recommend: record the disposition in-tree (test comment or a
jank.md row) as part of the cut.

### F2 — Row 7: release-face stamps still owed
- CHANGELOG.md: all 0.9.3 content (jank host Added entry, the Fixed entries incl. the
  blind-spot identity entries) sits under `## [Unreleased]` — there is no `[0.9.3]`
  heading. Content matches what actually merged (seat + fixes + identity mechanism);
  the cut must stamp the heading/date.
- package.json L3: `"version": "0.9.1"` — two releases behind the tree it ships in.
  Identical on main (pre-existing staleness, not introduced by this release), but the
  cut should stamp it 0.9.3.
- PASS parts of this row: doc/hosts/jank.md present and amended; README.md carries
  ONLY the labeled 0.9.0 battery numbers (README L87–96) — no unapproved 0.9.3 perf
  numbers appear anywhere in the tree.

### F3 — Row 1: the collation manifest document is not in the shipped tree
Completeness (John's definition: every manifest row dispositioned) was verified by
reconstruction: the manifest of record lived on branch `muse/eve-093-collation`
(removed from origin after the release branch was created); its rows survive as the
REPORTS.md handoffs + the release branch's own commit series + in-tree
doc/eve-0.9.3-fastpath-STATUS.md. All 12 handed-over pieces verified present as
commits in the merged tree (walk below). No silently-absent row. Records finding
only — but the next release should ship its manifest in-tree.

## ROW VERDICTS

### Row 1 — Manifest completeness: PASS (no silent miss)
Walked against the merged tree's commit series (branch point = main 6502d37, 36 commits):
seat merge PIECE 0 (729e9090); bb guard fix 85a3e5a (85a3e5ae/8213d78e); heap-grade
steps 1–3 (b98d5f09 + step-3 series); legacy-index fix (a) 67ff60b (12324bb6);
F-1 net.codec (97d42c9b + cda2d595 + 09b1493f); int-key codec (27732b10 + 090df5c0 +
0618a19a); eve->clj IPlain predicate fix / Fault 1 (b62b7388); fastpath field-meta fix
6f44aef (6f44aef5); bb cross-process docs PR #24 (39b626a9/440283a7); vector equiv
PR #25 (51d8aa55); dataset -pr-writer PR #26 (e026f974); blind-spot identity mechanism
PR #27 (48bff1e7..ca5851c7). Exclusions carry reasons in the records: the pre-blind-spot
seat-fix stack (aset-* re-roots, loop boxing, op-cache, zero_divisor, merge/into) was
excluded from the PR #27 collation as a separate landing decision (collation STATUS);
the per-node offset cache is a post-0.9.3 future optimization by John's disposition.

### Row 2 — Tree hygiene: PASS
- Conflict markers: `git grep` for `<<<<<<<` and `>>>>>>>` over the COMMITTED merged
  tree, no extension filter → zero matches (grep exit 1).
- Load smoke: JVM require of eve.core, eve.atom, eve.map, eve.set, eve.sorted,
  eve.vec, eve.dataset → `:MAIN-LOADED` printed (this box, seeded Maven repo).
  Test-namespace compilation on this tree is proven by the collated-tree gate
  (identity-test ran: +6 tests/+190 assertions delta) and is being re-proven by the
  verification lane's full-suite run; my own test-classpath require on this 8 GB box
  was SIGKILLed (exit 137, OOM, environmental) and a build-box retry hit an unseeded
  Maven pom — neither is a tree defect.

### Row 3 — BB parity: PASS (guard in; spot check environment-blocked, cause proven pre-existing)
All THREE :bb sites of Agent 2's fix verified at HEAD in src/eve/atom.cljc:
L30 `#?@(:bb [] :clj [[eve.guard :as guard]])` (require), L838 `#?(:bb nil :clj ...)`
(guard-deref), L865 `#?@(:bb [] :clj [(guard/active?) (guard-deref ...)])` (mmap-deref arm).
Spot check: `bb test:bb eve.hamt-util-test` on the merged tree (build box, bb v1.3.190)
fails at ANALYSIS of adapters/encoding/java/src/eve/encoding.clj:187 —
"Unable to resolve classname: java.util.concurrent.ConcurrentHashMap". Proof it is not
merge-induced: encoding.clj is BYTE-IDENTICAL between the branch point (main) and the
merged tree (cmp-verified); the class use entered in an Oct 28 merge that is in main.
hosts.edn's :bb row deliberately shares the java encoding root, and bb 1.3.190's SCI
cannot resolve that class — the same failure this environment shows on pristine base.
Agent 2's parity evidence stands: full bb shared suite on the fix commit under its bb
build, 294 tests / 5,632 assertions / 0/0 = exact base parity (REPORTS.md READY entry,
2026-11-05 6:49 PM ET). The merged tree's shared-code deltas (set/map/sorted) are the
blind-spot gates, which the JVM gate exercised; a bb-suite re-run wants a bb build that
can load the tree, on any box.

### Row 4 — Map-hash rows: FINDING F1 (above)

### Row 5 — Seat parity spot-check: PASS
14 adapters/*/jank roots present in the merged tree. Spot-checked 3 roots untouched
by the blind-spot merge against the seat source lines (origin/claude/eve-jank, eve/
tree): adapters/slab-io/jank/src/eve/slab_io.jank, adapters/region/jank/src/eve/
region.jank, adapters/crypto/jank/src/eve/crypto.jank — all BYTE-IDENTICAL.

### Row 6 — Main divergence: PASS (nothing missing; move is one-directional)
`git rev-list --count 6502d37..origin/main` = 0 — Eve main IS the branch point; it
gained nothing after the release branched. The release is strictly ahead (36 commits).
The reverse divergence (release-only content main lacks — the bb guard fix, the whole
jank seat, step 3) surfaces at the main-move as a one-directional landing; per the
flow ruling the bb fix is NOT for main ahead of the cut. No blocker for this release.

### Row 7 — Release face: FINDING F2 (above); jank.md present, README clean of unapproved numbers.

## Bottom line
Ship-shaped. Close F1's in-tree disposition note and F2's two stamps at the cut;
F3 is a process note for the next release. Everything else on the audit list verified
with the evidence above.

## ADDENDUM — 2026-11-06 ~16:15 ET (build-box legs completed after the main report)

1. **BB control CONFIRMED (upgrades Row 3).** The pre-merge collate tree
   (~/blindspot-collate-gate on j.slaa.us), same bb v1.3.190, `bb test:bb
   eve.hamt-util-test` → exit 1 with the identical error: "Unable to resolve
   classname: java.util.concurrent.ConcurrentHashMap". The failure is therefore
   proven — by control run, not only by byte-identity — to occur on the
   pre-merge tree: not merge-induced, full stop.

2. **JVM suite on the MERGED tree, build box (upgrades Row 2).** The
   `clojure -M:jvm-test` invocation in the synced merged tree
   (~/eve-093-audit-gate) ran the suite: **Ran 636 tests containing 10,724
   assertions. 2 failures, 1 errors.** — the failure/error count is exactly
   the known Aegis pages_v3 trio; the assertion delta vs the collated-tree
   gate (10,612) is the merged tree's additional seat tests. The merged tree's
   JVM gate is therefore green modulo the known trio, measured on the merged
   tree itself.
