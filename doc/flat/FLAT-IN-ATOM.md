# FLAT IN ATOMS — semantics and user contract

Companion to FLAT-IN-ATOM-PLAN.md (the R8 account and the R12
guidance, as built). This text merges into FLAT.md at landing.

## What freezing is

A persistent subtree can be **frozen** into flat form inside
an atom: `(swap! a (fn [v] (eve.flat.freeze/freeze-value sio v)))`,
or a chunk arriving from outside can be installed through the
checked door (`enter-checked-extent!` — bytes are vouched by
open-checked before they become content). The frozen value is
stored as ONE block, size-routed by the allocator like any
other content — a small fixed class when the framed chunk is
small, the variable-size class when it is large. (In the
target format, large flats are slated for a dedicated flat
class ladder above the shared classes; see the plan's
migration section.) A frozen value is CLOSED: it contains
nothing outside itself — no pointers into the persistent
tree — so its digest is computable from its own bytes alone,
and freeze latches that digest at freeze time.

Only freezable content can freeze: values held by reference
(functions, host objects) cannot be packed and are refused.

## How a frozen value behaves

- **Equality and hashing are one domain.** A frozen value is
  `=` to the persistent value with the same content, and
  hashes the same. Identity is NOT promised: two frozen
  values with equal content are equal, never mind which
  block they live in. Code that compares by content (the
  normal Clojure way) cannot tell the forms apart.
- **Reads are the full protocol mirror.** count, get, nth,
  seq, keys/vals, iteration — all work over the flat form,
  in place, without materializing. Row and cell reads
  assemble small views; column scans read only the columns
  they touch.
- **Delivered values own their bytes.** A value delivered
  out of an atom as a flat object (deliver-atom) is a
  snapshot packed under the atom's read pin — the same
  discipline a materializing deref obeys — and thereafter
  reads with no pin, no epoch, and no domain: it still reads
  after the source value is retired, and after the domain
  is closed. Watchers on shared atoms receive the same
  delivery in place of a materialized value.
- **Writing upgrades.** assoc/cons/pop on a value delivered
  as a chunk handle stay flat (a new handle over edited
  columns). Writing into a FROZEN value — assoc on the
  FrozenValue itself, e.g. inside a swap function — upgrades
  it: the value is materialized, the edit applied, and what
  the swap publishes is the persistent form. Cost = one
  materialization, not a path-copy. The frozen block is
  never mutated; it retires with the old root value.
- **Class-checking code must materialize.** Protocol-
  polymorphic code works unchanged on flat values. Code
  that checks concrete classes (`instance? PersistentVector`,
  Java collection interop, reflection over the object graph)
  is looking at a different representation: materialize
  first (below).

## When to freeze — and when not to

Freeze what is **scanned or shipped**: table-shaped data
read by scans, filters, projections and aggregations (flat
column scans run faster than host row walks — 21 vs 30
ns/row measured on the records shape), values that move
atom-to-atom or over the wire (a frozen value is already in
wire form), frozen reference data, query results, snapshots,
and the Arrow/dataframe boundary (the chunk IS the payload).

Do not freeze what is **navigated or picked at**: point
lookups and deep heterogeneous navigation are the persistent
tree's home ground — a HAMT get beats a flat row assembly by
roughly 2×, and materializing rows as plain maps is the
flat form's most expensive read. Small values gain nothing:
container overhead dominates. Rule of thumb: freeze what's
scanned or shipped, never what's navigated or picked at.

## Working with flat values

- **Ship directly from the atom.** A frozen or delivered
  flat value needs no conversion step before transfer! or
  the stream — it is already packed.
- **Change it in the atom first.** To edit a frozen value,
  swap! with an ordinary function: the write upgrades it
  transparently. Editing the delivered heap handle is for
  heap-local work; the result is a new flat value, not a
  new atom state.
- **Materialize when you want Clojure heap data.**
  `eve.flat.freeze/plain-of` converts a flat value to plain
  Clojure data, recursively; the existing eve->clj path does
  the same for atom values generally. Materialized data is
  ordinary heap data with ordinary heap semantics — and
  ordinary heap costs.
- **Footprint.** A flat value's footprint is its bytes:
  one block in the atom, the chunk's byte length on the
  heap. The persistent form of the same content typically
  occupies several times that in slab blocks (the records
  corpus: 2,120 chunk bytes vs a 21,968-byte slab image).

## Performance assurances

The measured assurances table (flat heap object vs Clojure
heap object vs in-atom slab read — access, scan, hash,
equality, assoc, footprint, per corpus, same session) is a
release artifact produced by the perf suite's heap battery
(`--heap` mode); it is reproduced in FLAT.md verbatim when
the canonical run lands. Until then, the directional facts
above are the ones with measurements behind them; no other
numbers are claimed.
