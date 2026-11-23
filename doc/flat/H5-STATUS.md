# H5 — the generated sister (lane "Sister", pulled forward from Wave 2 by the operator, 2026-11-12)

The operator's rule this lane implements: every type `eve-deftype` generates —
Eve's own and user types alike — generates **two kinds from the same macro
expansion**: the persistent kind (in a domain) and the static kind (the packed
sister). Persistent turns to static when it crosses or leaves an atom boundary.
CLJC first: generation lives in the .cljc deftype pipeline; host branches only
where hosts genuinely differ. No hand-written per-type static code.

## Pins (lane open, 2026-11-12 ~02:40 ET)

- Base: research tip `7402ef9` (branch `claude/sweet-carson-6pt29d`).
- Merged in order: W0 contracts `5d4b4f6` · C1 chunk `9ea620b` ("compiles;
  tests not yet run" — its pin at lane open) · Z1 image `22ceacb` (wires
  `register-deftype-layout!` into `src/eve/deftype/macros.cljc`).
- Also merged for S5 (the brief's coordination instruction — T1's path, not a
  second one): T1 transfer `f4d6160`, which brings Z3's `eve.static.place`
  and the `:zip` foreign-value branch in `eve.atom`. Merge was clean
  (verified with merge-tree first). T1's own tests had not yet run on its
  branch at merge time (load-gated there); S5 will exercise the path here.

## What the pins already have (read firsthand)

- C1's chunk: writer scans plain values to reps; a typed value is a host
  **defrecord** (`record?` + `getBasis`) → `TypedVal`. An eve-deftype instance
  is not a host record: today it reaches the writer as an opaque scalar and
  its bytes carry the reference tag the packer refuses. **The writer-side
  conversion for eve types does not exist — it is this lane's.**
- C1's reader: `eve.static.chunk/register-type! [tkey fp]` — a typed node
  whose [type key, fingerprint] is registered reads as a map handle
  (`StaticRecord`), anything else as a generic record (`StaticTagged`).
  Batch rows always read as map handles (`StaticRow`). Registration is
  keyed on C1's fingerprint, which is computed from one instance's field
  **values** — a declaration-based fingerprint (FORMAT-V1 §6: field names,
  classes and hints, in declaration order) cannot match it, so the eve-type
  writer path must carry the registered fingerprint in its `TypedVal`,
  which it can: the rep is built by the converter, not by `typed-rep`.
- The persistent kind's generation: `eve-deftype*` (macros.cljc) →
  `t/eve-type` → `eve.deftype-proto.sio/eve-type`, which binds every live
  field's name as a local read from the slab at each method's entry, then
  runs the user's body. **The sister's generation mirrors this exactly**:
  a second generated type whose method-entry bindings read the fields from
  the packed row/record instead of `block + offset` (FORMAT-V1 §6's "a row
  type whose field reads go to `column[row]`").

## Design (to be confirmed by the tests)

- `eve-deftype*` emits, in the same `do`, beside `register-deftype-layout!`:
  the sister type's generation (a `t/static-type` host entry, realized for
  ISlabIO hosts in `eve.deftype-proto.sio`) and a registration form calling
  `eve.static.sister/register-sister!` with the type key, the field
  descriptors (slab fields only; `^:host` fields are not written, §6) and
  the sister factory.
- `eve.static.sister` (.cljc, new): the layout fingerprint (declaration
  data only, deterministic across processes), the descriptor registry, and
  the writer-side converter chunk consults for eve instances.
- Chunk changes (C1's file — own commits, flagged here as required):
  (1) `register-type!` gains a factory arity; the reader answers the
  factory's sister for a registered typed node and for batch rows of a
  registered type (today: map handles unconditionally); (2) `to-rep`
  consults a registered instance converter before treating a value as a
  scalar.

## Requirements (tests first, red)

- S1 `sister-roundtrip` (H5.1) — S2 `sister-protocols` (H5.2) —
  S3 `sister-fingerprint-mismatch` (H5.3) — S4 `sister-is-generated` (H5.4) —
  S5 `boundary-conversion` (H5.5, added: the operator's boundary rule) —
  S6 `cljc-first` (H5.6, added: the operator's CLJC-first instruction).

## As built (implementation commits)

- `eve.static.source` (.cljc, new): `IFieldSource` (`-field`, `-field-map`,
  `-source-bytes`) and `ISister` — the leaf the chunk, the generator and the
  registry share.
- `eve.static.sister` (.cljc, new): the declaration fingerprint (FNV-1a over
  type key + per-field name/class/hint, declaration order — FORMAT §6's
  data, computable identically in every process); the descriptor registry;
  `field-values` (an instance's fields read from its block by class, the
  persistent kind's own reads at runtime); `instance->typed-rep`, the
  writer-side converter. The chunk is reached by requiring-resolve (it is
  JVM-only at this pin; this namespace is not).
- `eve.deftype-proto.sio/static-type`: the sister's realization for ISlabIO
  hosts, mirroring `eve-type`'s transform — cached fields as the class's own
  fields (read from the source at factory time, as `Type__from-slab` reads
  them from the block), live fields bound at each method's entry from
  `-field`, `set!`/`cas!` on a slab field rewritten to a throw (a static
  value is immutable). Boilerplate: ISabpType, ISister, IHashEq/Object with
  equality by type key + fields — fork F2's option (a), applied to the
  static side only; the slab kind's identity comparison is untouched.
  `eve.types/static-type` dispatches (JVM adapter); a ClojureScript compile
  realizes no class at this pin (registration only; H4's territory).
- `eve-deftype*` emits, in the same `do` as the persistent kind and beside
  the layout/type-refs registrations: the sister's generation forms (where
  the host realizes them) and `register-sister!` with the type key, the
  slab fields as data, and the factory.
- **Chunk changes (C1's file, own commit `487f3d4`, flagged there and
  here):** rep converters consulted by `to-rep`/`pack`;
  `register-type!`'s factory arity; a typed node with a factory reads as
  the sister over a `RecordSource`; a batch row of a factory-registered
  type reads as the sister over a `RowSource`. Unregistered values take
  the exact paths they took before.

## Log

- ~02:40 ET — lane open: clone, merges above, plan/format/code read.
  Design settled as above; tests next.
- ~03:0x ET — tests first, red (`c8ebc3b`): the suite fails to load,
  `eve.static.sister` does not exist — the rest of the tree compiles.
  Manifest: H5.1–H5.4 `:built`, H5.5/H5.6 added `:built`.
- Implementation committed in four commits (source+sister, chunk hooks,
  generation). Sister suite run in flight.
- First full run: **the suite runs** — 6 tests, 17 assertions, 0 failures,
  5 errors, every error in code that had never run at its pin (each lane's
  own STATUS said so: C1 "compiles; tests not yet run", T1 likewise).
  Defects found by this suite, each fixed in its owner's file as its own
  flagged commit:
  - **Z3 `place.cljc` — the file did not read** (one missing closer in
    `execute-plan!`). Fixed `8f92e38`.
  - **T1 `transfer.cljc` — the file did not compile or read**: a 10-arg
    fn with primitive hints (max 4); one surplus closer closing
    `block-scan`'s case early; a stray `]` closing `image-bytes`'
    binding vector four bindings early. Fixed `373498d`.
  - **T1 `transfer.cljc` — `block-scan` NPE**: `ip` bound by `when`,
    compared with `==` — nil for any value block not exactly 7 bytes.
    Fixed `abe3404`.
  - **C1 `chunk.cljc` — `scan` threw on any pack with ≥2 shapes**:
    `sort-by val` and entry accessors over plain `[k v]` vectors
    (val/key need MapEntries); and the type list as written would have
    put the `[tkey fp]` pair in `:key` and the index in `:fp`. Fixed
    `55ef315`, `4ebe864`.
  - One error was this lane's own test: the boundary instance was built
    in the process domain and atom A refused it as foreign — Eve
    working as designed; the test now builds in the atom's domain
    (`b46931f`).
- Re-run owed when the box cools (load1 spiked past 40 under the whole
  fleet; the runs above were taken in windows under the gate).
- Runs since: run 3 — 37 assertions, protocols/generated/cljc green,
  chunk-bytes-on-a-sister and the S5 negative arm open. A sister now
  implements chunk's IStaticHandle (it stands on the chunk's bytes;
  `-rep` delegates to the converter), and eve.static.sister loads
  eve.static.chunk on :clj so the protocol exists before any type is
  defined. Run 4 — 43 assertions, 0 errors, only the S5 negative arm
  red. Run 5 — 44 assertions, same single failure.
- The S5 negative arm took three instruments to get honest (all probed
  at this pin, probe since deleted): retire/reach conflates walk
  knowledge with presence (with the layout removed it cannot see
  inside the source either — both sides read 1). The measured facts:
  the value spans 4 blocks; T1's image carries 4 with the registration
  and 1 without; the 1-block copy still reads correctly in-process
  because its unwalked pointers address the source's blocks in the
  shared slab — it aliases its source domain. The arm now censuses
  T1's image directly, with a premise assertion tying census to reach
  while both are visible.
- **Run 6 — THE SISTER SUITE IS GREEN: 6 tests, 45 assertions,
  0 failures, 0 errors** (own run, hatch, before the venue shift).
  S1–S6 all pass: roundtrip, protocols over packed bytes without a
  full decode, fingerprint mismatch → generic record, generation for
  two fresh user types with zero per-type code, boundary conversion
  via T1's transfer (faithful + self-contained with the registration;
  the pack arm refuses without the sister registration; the detach
  image is not self-contained without the layout registration), and
  cljc-first (checked from the files themselves).
- **Venue shift (operator order, 2026-11-12 05:19 ET): no heavy runs
  on hatch at any load.** All remaining legs are filed with the job
  agent for J. Bundle: `~/workspace/static-eve-bundles/h5.bundle`
  (created from this tree; **coordinator: refresh the staged bundle on
  J from this tree's tip at each sweep — the lane commits locally
  first and notes the tip here**). Jobs filed in
  `~/workspace/job-agent/inbox/`:
  - `h5-reg1.json` — requirements + contract + chunk suites →
    `~/static-eve-results/reg1.log` on J. (A local attempt at this
    batch just before the order died on the test runner's arity: this
    runner takes one `-n` per invocation; the J legs chain
    single-namespace runs.)
  - `h5-reg2.json` — image + transfer suites → `reg2.log`.
  - `h5-reg3.json` — type-id + dataset + tally suites → `reg3.log`
    (the generation change touches every eve-deftype in the repo).
    Results come back through the coordinator; the lane does not idle —
    light work (STATUS, the report draft) continues here.
- **Finding for Z1 (banked, not fixed here):** `eve.static.image/detach`
  throws ArithmeticException (RT.intCast overflow in its ordinal
  table's hash-of, image.cljc) on an atom holding an eve-deftype
  instance, in a process where the instance's slab offset's low word
  is ≥ 2^31; in another process state it silently leaf-copies. Z1's
  corpora were plain values. T1's transfer path is unaffected. Owner:
  Z1; the H5 requirements do not ride on image/detach (S5 is specified
  on T1's path).
- **Close-out (2026-11-12): regression numbers delivered from J and
  written into H5-REPORT.md §5.** reg1: contract/requirements 2 tests,
  522 assertions, 1 failure + 1 error (the W0 api-surface nil-probe
  item — collation's); chunk suite at pin 9ea620b 3 tests, 33
  assertions, 2 failures (pin reds, fixed at Chunk's later tip).
  reg2: image + transfer 6 tests, 152 assertions, 10 failures,
  1 error — Transfer's pin state (owner T1, incl. the three failures
  since proven to be its test's same-domain construction defect) and
  image's pre-fix hash-of overflow state (fixed in Z1's tree at
  a3a0d20). reg3: ALL GREEN (7 + 98 + 216 assertions, 0/0). No red
  attributes to Sister's own code; Sister's verdicts stand on its own
  suite (6 tests, 45 assertions, S1–S6 green). Lane COMPLETE; named
  gaps stand in the report §6 (eve-extend-type, CLJS registration-
  only, F2 as (a) pending the operator's disposition).
