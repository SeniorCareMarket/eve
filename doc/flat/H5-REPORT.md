# Lane H5 — Sister: completion report

**Status: COMPLETE. Requirement suite green in the lane's own run;
regression legs run on J (venue shift, operator order 2026-11-12
05:19 ET), numbers in §5.**

## 1. What was built

The operator's two-kinds rule (2026-11-12): every type `eve-deftype`
generates — Eve's own types and user types alike — generates two kinds
from the same macro expansion, the persistent kind and the static kind
(the sister). Generation is not an add-on pass.

- `eve.deftype-proto.sio/static-type` generates the sister class beside
  the persistent class, mirroring its field split exactly: live fields
  (mutable and eve-type references) are bound at each method's entry
  from the field source, as the persistent kind binds them from the
  slab; cached fields are the class's own host fields, read once when
  the factory builds the sister from a source. The sister is immutable
  (writes to slab fields throw), carries the type's protocols (the
  same parsed protocol bodies, their field references re-read over
  the source), and implements the chunk's `IStaticHandle` — it stands
  on the chunk's bytes, and re-packs as its type's typed rep.
- The sister reads through `eve.static.source/IFieldSource`, one leaf
  protocol with two implementations over the packed form: a row
  source (a batch row) and a record source (a single typed value).
  Reads do not materialize: the suite measures C1's whole-chunk
  full-decode counter across row and record reads and it does not
  move.
- `eve.static.sister` holds what the macro registers per type: the
  descriptor (type key, declaration-based layout fingerprint — FNV-1a
  64 over the field names, classes and hints in declaration order,
  FORMAT-V1 §6), the converter that turns a slab instance into the
  chunk writer's typed rep (its fields read from the instance's own
  block by class), and the generated factory the packed reader calls.
  The fingerprint is carried in the typed rep itself, so the type
  table of every chunk a user type travels in states the layout it
  was packed under; a reader whose registered fingerprint differs
  answers the generic record, never wrong fields (FORMAT-V1 §6,
  the reader's rule).
- `eve-deftype`'s expansion ends with the registration
  (`register-sister!`): type key, the slab fields as data, and the
  generated factory where the host realizes one. ClojureScript
  realizes no class at this pin — its `eve.types/static-type` answers
  nil and the expansion registers the layout alone (see §6).
- Equality (fork F2): the sister implements option (a) on the static
  side — statics compare by type key + fields; the slab kind is
  unchanged. F2 is not formally decided; the static side had to pick
  the plan's recommended reading to be testable at all.

## 2. Requirements — verdicts (own run: 6 tests, 45 assertions, 0 failures, 0 errors)

| id        | requirement                                                                                           | verdict                                                                                                                                                                                                                                                                                  |
| --------- | ----------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| H5.1 / S1 | sister-roundtrip — a fresh user type: vector of instances → static batch → instances, equal by fields | **green** — batch rows read as sisters of the user type; a single instance roundtrips too                                                                                                                                                                                                |
| H5.2 / S2 | sister-protocols — the type's own protocols satisfied over the packed bytes, no materialization       | **green** — `describe`/`total`/`part-desc` computed on batch rows and a packed single, full-decode counter unmoved                                                                                                                                                                       |
| H5.3 / S3 | sister-fingerprint-mismatch — changed layout, same type key → generic record                          | **green** — the type table's fingerprint bytes are flipped post-pack; the value opens as a generic record carrying the right fields, not a sister and not a crash                                                                                                                        |
| H5.4 / S4 | sister-is-generated — a second fresh user type has a sister with zero additional code                 | **green** — Doohickey, defined with plain `eve-deftype` like the first pair, packs to sisters and computes its protocol over them                                                                                                                                                        |
| H5.5 / S5 | boundary-conversion — across an atom boundary via the generated registration                          | **green** — via T1's `transfer!` (faithful, and self-contained: reach in B equals reach in A); nested in a map under the `:zip` foreign policy; leaving as a static refuses without the sister registration; the detach image is not self-contained without the layout registration (§4) |
| H5.6 / S6 | cljc-first — generation and sister in .cljc, no host-only parallel implementation                     | **green** — asserted from the files: macros.cljc, sio.cljc, sister.cljc, source.cljc are .cljc; no `eve/static/sister` .clj or .cljs exists                                                                                                                                              |

## 3. Pins built against

- base (research tip, `claude/sweet-carson-6pt29d`): 7402ef9
- W0 contracts: 5d4b4f6 (merged)
- C1 chunk: 9ea620b (merged; its pin note stands — "compiles; tests
  not yet run" — and §4 is what the first runs found)
- Z1 image: 22ceacb (merged)
- T1 transfer: f4d6160 (merged; brings Z3's `eve.static.place` and
  the `:zip` foreign-value branch in eve.atom)

No pushes: this box has no GitHub credential. All work is committed
locally on `claude/static-h5-sister`; the bundle for J is
`~/workspace/static-eve-bundles/h5.bundle`.

## 4. Defects found by this lane's runs — each re-derived at this pin, each fixed in its owner's file as its own flagged commit, or banked for its owner

In merged lanes' files (never run at their pins, by their own STATUS
notes):

1. **Z3 `eve.static.place` did not read** — one missing closer in
   `execute-plan!`; the file was byte-identical in Z3's and T1's
   trees. Fixed (`8f92e38`).
2. **T1 `eve.static.transfer` did not compile or read** — a 10-arg
   fn with primitive hints (the JVM allows 4); one surplus closer
   ending `block-scan`'s case early; a stray `]` closing
   `image-bytes`' binding vector four bindings early. Fixed
   (`373498d`).
3. **T1 `block-scan` NPE** — `ip` bound by `when`, compared with
   `==`: nil for every serialized value block not exactly 7 bytes.
   Fixed (`abe3404`).
4. **C1 `chunk/scan` threw on any pack with two shapes or two types**
   — `sort-by val` and entry accessors over plain `[k v]` vectors;
   as written, the type list would also have put the `[tkey fp]`
   pair in `:key` and the index in `:fp`. Fixed (`55ef315`,
   `4ebe864`).
5. **C1 `chunk-test` never loaded** — `gen/large-integer*` (a
   function) used bare in a `one-of` vector. Fixed (`6db2aac`).
6. **For Z1, banked not fixed:** `eve.static.image/detach` throws
   ArithmeticException (RT.intCast overflow in its ordinal table's
   hash, image.cljc) on an atom holding an eve-deftype instance, in
   a process state where the instance's slab offset's low word is
   ≥ 2^31; in another it silently leaf-copies. Z1's corpora were
   plain values. H5's requirements ride on T1's path, not this one.

In this lane's own work: the converter reached the chunk by
requiring-resolve (chunk is JVM-only at this pin; sister.cljc is
not), and the first version of S5's negative arm measured the wrong
quantity twice (a refusal that is Eve's store rule working as
designed; then retire/reach, which conflates walk knowledge with
presence). The arm as landed censuses T1's image: 4 blocks with the
registration, 1 without, with a premise assertion tying the census
to the reach span while both are visible.

## 5. Regressions

Filed with the job agent for J (venue shift); logs at
`~/static-eve-results/` on J:

- `h5-reg1.json` — eve.static.requirements-test,
  eve.static.contract-test, eve.static.chunk-test → reg1.log
- `h5-reg2.json` — eve.static.image-test, eve.static.transfer-test
  → reg2.log
- `h5-reg3.json` — eve.deftype.type-id-test, eve.dataset-test,
  eve.tally-test → reg3.log

**Numbers (from J, read firsthand by the coordinator).** These legs
ran the suites of the pins merged into this tree (§3: W0 5d4b4f6 ·
C1 9ea620b · Z1 22ceacb · T1 f4d6160) — they measure those pins'
states, not Sister's, whose verdicts stand on its own suite (§2).
The reds below attribute to the pins and their owners:

- **reg1** — contract + requirements: 2 tests, 522 assertions,
  **1 failure, 1 error**. The error is the known W0 api-surface
  nil-probe contract item (W0's accepted outcomes predate any lane
  landing; Chunk's lane hit and attributes it identically). Owner:
  **W0 contract — a collation item**, not Sister.
  Chunk suite at pin 9ea620b: 3 tests, 33 assertions, **2 failures**
  — the known pin reds (the cached-hash `set!`-on-final-fields
  family), fixed at Chunk's own later tip; Node's lane measured the
  same reds at the same pin.
- **reg2** — image + transfer: 6 tests, 152 assertions,
  **10 failures, 1 error**. This is Transfer's suite at pin f4d6160
  showing the same failures T1's own J run showed — including the
  three since proven to be the test's own construction defect
  (anonymous atoms share one cached default domain, so the test
  measured one domain twice); owner: **T1's lane**. The image suite
  at Z1 22ceacb carries the pre-fix `hash-of` overflow state (§4
  item 6); fixed later in Z1's own tree (a3a0d20). Owner: **Z1**.
- **reg3** — deftype type-id + dataset + tally: **ALL GREEN** —
  7 + 98 + 216 assertions, 0 failures, 0 errors across the three.
  The generation change touches every `eve-deftype` in the repo;
  nothing regressed there.

No red in any leg attributes to Sister's own code: the failures are
the merged pins' already-known states, each with its owner named
and, for the chunk and image reds, a fix already landed in the
owning lane's later tip.

## 6. Known gaps (named, not hidden)

- `eve-extend-type` does not yet extend the sister: a protocol added
  to a type after its definition is satisfied by the persistent kind
  and not by the sister. (The suite's protocols are the ones the
  defining `eve-deftype` itself carries.)
- ClojureScript realizes no sister class at this pin: registration
  only, reads answer the generic form. H4/X1 territory — the
  generation point and the registration are host-shared, so a CLJS
  realization plugs into the same expansion.
- The sister's field-read converter for slab instances is JVM
  (`ISlabIO`) only, matching the chunk's own position at this pin.
- A sister stored into an atom is out of scope: statics enter atoms
  through place/transfer, as values of the persistent kind.
- Fork F2 (user-type equality default) is implemented as the plan's
  recommended (a) on the static side only, pending the operator's
  disposition.
