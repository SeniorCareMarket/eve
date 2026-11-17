# U4 — deftype / defrecord flat sisters, field reads as loads (wave 2; PLAN.md unit 4) — STUB, filled at wave 2

Depends on: U1 (the VS FlatMap a citizen) and U3 (eve on VS) collated. Scope: eve's sisters (src/eve/flat/sister.cljc:
every eve-deftype type gets a flat kind whose field reads go to column[row]) on all three hosts over flat-io; on VS,
the compiler's field-read lowering: a map whose values are records is a BATCH, `(:min s)` / `(.-min s)` on a cursor
row is one load (as `(nth s 0)` is after R7). Done bar: a record-valued 1BRC fold (vanilla with a defrecord Stats) on
the flat path with answers = cfast's; eve's sister tests (sister_test.clj and its host_suite rows) on three hosts.
