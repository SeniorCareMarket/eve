# Eve fix plan — the value model, and what eve.db.ds needs from Eve

v2, Nov 19 2026. Companion: `DS-IN-EVE-PLAN.md` (same folder). The DS rewrite
does not start a unit that depends on a law below until that law is green in a
tagged Eve pin.

## 0. The value model (John, 2026-11-19, verbatim)

> `eve->clj` needs to do what `eve->cljs` did, but just across all eve
> platforms. […] IPlain should be excised.
>
> When we deref an atom into an eve host's heap, it should come out as flat
> data.
>
> When we instantiate an eve/deftype'd value outside an atom, it should
> produce a flat version. Inside an atom it should produce a persistent one.
>
> Calling `eve->clj` on either of them will produce clj on that host. Calling
> it in an atom and returning the result in an atom would just turn it right
> back into eve data. Calling it outside would imply you're calling it on flat
> data.
>
> Putting clojure data with eve flat data nested in it into an atom should
> result in persistent eve data with that flat eve data in it. Pulling that
> persistent value back out should result in the whole value being one
> complete packed value, because value extraction from atoms flatten the data.
> It basically becomes valhala data outside of atoms, without identity. Atoms
> confer the data the identity.
>
> Everything I'm describing did get impl'd at some point and some how got
> clobbered by other agents. […] Regardless, that is how it is supposed to
> work and your impl plan needs to ensure it.

### The laws (each one a `deftest`, §5)

Outside an atom = flat, value-only, no identity. Inside an atom (a `swap!`
fn, a `$` region) = persistent, slab-backed. The atom confers identity.

| Law | Statement |
|---|---|
| V1 | Every exit from an atom delivers flat data on the host heap: the end of a read (V9), `$` exit, `swap!`/`swap-vals!`/`reset!` return, watch old/new, the value `compare-and-set!` compares. No slab-backed object ever leaves a read. |
| V2 | An exit delivers what the read reached — the whole value for `swap!`'s return, a watch, `$`'s result; the subtree at the end of the lookups for a deref read (V9) — as ONE complete packed value, whatever it holds inside — persistent eve data, flat values stored earlier, deftypes, value arrays. No in-atom wrapper (`FrozenValue`, `BasedValue`, `ConcatBatch`) ever surfaces. |
| V3 | `eve/deftype` construction outside every atom gives the flat instance; inside a `swap!` gives the persistent instance. Both answer the type's protocols and field reads. Same for `e/make-array` and every other eve constructor. |
| V4 | Clojure data with flat eve values nested in it, stored into an atom, becomes persistent eve data holding those flat values. Read back inside the atom, a flat value answers as its type (a flat `Box` is still a `Box` to protocol dispatch). |
| V5 | `eve->clj` is `eve->cljs`'s rule on every host: eve maps, vectors, sets, lists → host collections, recursively. One generic fn; no per-type protocol. Inside an atom its result stored back is eve data again; outside it runs on flat data. |
| V6 | Values have no identity outside atoms: two deliveries of one value are `=` and need not be `identical?`. `=` is value equality on every delivered form. |
| V7 | `compare-and-set!` compares by value: `(compare-and-set! a @a x)` succeeds when no swap intervened. |
| V8 | Flat delivered values live on the host heap: they need no pin, no epoch, no domain, read correctly after any number of later swaps and after the domain closes, and are reclaimed by the host GC. |
| V9 | A deref reads lazily and ends its own read (John, 2026-11-19: "the scope returns from the deref after the `:c`"; packing the whole atom per deref "not going to work"). `@a` answers a pinned lazy view over the stored root — no copy. Lookups (`get`, keyword invoke, `get-in`, `nth`, `find`, `contains?`) read one level in place: a scalar answers itself and releases the pin; a subtree answers a child view on the same pin. The first operation that is not a lookup (`seq`/`reduce`/`into`, `count` of a subtree, `=`, `hash`, print, storing it into an atom, passing it to a protocol method) packs the subtree at the end of the path (V2) and releases the pin. A view never ended keeps its pin while reachable (a live reader of its version) and releases it when the host GC collects it. `(-> @a :b :c (into [1 2 3]))` packs `:c` only; `(let [v @a] (:x v) (:y v))` reads one version. `$` is optional: one snapshot over a larger body. |

`eve->clj` on a deftype (my reading of "produce clj on that host" — John to
confirm in one word): a deftype has no Clojure collection shape, so
`eve->clj` gives its flat instance; on a flat instance it is the identity.
Eve's own collection types are not "deftypes" for this rule: they take the
collection arm. The deftype arm is checked BEFORE the shape arms, so a
map-signature deftype (the DataScript DB on bb) is never walked as a map —
this is what F-BB-DATOM-CMP needed, without a pass-through.

## 1. Measured against the laws (JVM, `release/eve-0.9.3.3` `f4a1c3a`)

Provenance: OpenJDK 21.0.13, Clojure CLI 1.12.0.1530; fresh `e/atom` per run,
fresh JVM per probe; six deftype field shapes (`^:int32`+string, untyped
holding map / vector / deftype, `^:Leaf` typed nested, `^:volatile-mutable`),
a DB-shaped type with an `e/make-array` field. Browser and the other 16 hosts:
not run.

| Law | Measured | Status |
|---|---|---|
| V1 deref | delivers `<Type>__Flat` or own class (heap domain); protocol answers | green |
| V1 deref of a value array, any depth | throws `flat chunk: a value held by reference cannot be packed` | RED |
| V1 `$` exit | a deftype built in `swap!` escapes LIVE (slab `Box`); a value array escapes live (`ValueArray`) | RED |
| V1 `swap!` return | `EveHashMap` (slab-backed); its deftype a `FrozenValue` | RED |
| V1 watch old/new | no throw; flatness not checked | unmeasured |
| V2 | a deftype stored, then re-stored inside `swap!` (`(assoc m :old (:db m))`), derefs as `FrozenValue` — no protocol | RED |
| V3 deftype | outside → `Box__Flat`; inside → `Box` | green |
| V3 `e/make-array` outside | throws `Eve data must be constructed inside a swap!` | RED |
| V4 | a flat `Box` `reset!` in is stored as `FrozenValue`; read inside `swap!` it is a `FrozenValue` — no protocol | RED |
| V5 | `eve->clj` dispatches on IPlain; user deftypes and value arrays pass through LIVE; a stored flat `Box` (`FrozenValue`) → `PersistentArrayMap`, type lost | RED |
| V6 | `identical?` false, `=` true | green |
| V7 | always `false`: compares `(d/eve->clj cur)` (IPlain) to the delivered value (`atom.cljc` `compare-and-set`) | RED |
| V8 | deref'd flat `Box` reads after 1,000 swaps | green (domain-close and GC unmeasured) |
| V9 | deref packs the whole root every call (`deliver-flat`: "Cost = one pack"); no lazy view outside `$`. `eve.guard` (JVM-only prototype) has the lazy views, released at a `with-guard` extent's end, not at the read's | RED |

## 2. How it got here (measured from history)

- **Mar 2026** (`SeniorCareMarket/eve` `0d66b99`, newest pre-reunification
  branch): `eve->cljs` — by shape (`src/eve/atom.cljc:1528`: IMap / IVector /
  ISet / ISequential → host collections) and by storage tag
  (`src/eve/shared_atom.cljs:2352`). No protocol. Its last arm passed
  anything else through, and eve arrays were kept slab-backed on purpose —
  deftypes and arrays were already not materialized.
- **Sep 2026** (`c7dc390`, "JVM reunification", a local-only commit, under
  no ref anywhere): `eve->cljs` renamed `eve->clj` for the JVM and given an
  IPlain first arm. Materializing became per-type opt-in.
- **2026-10-15** (atom.broker `b24c8c0347`): EVE at `c7dc390` vendored into
  atom.broker; IPlain arrives with it.
- **2026-11-07** (atom.broker `e774995728`, `lane-m/eve-bb-datom-cmp`):
  "an Eve type materializes through its own IPlain or passes through, on
  every seat" — an `IsEve` pass-through added to stop a bb defect (the
  DataScript DB presented as a host map, walked, and its BTSet's `-plain`
  dropping the comparator). No operator ruling quoted. The BTSet `-plain`
  defect was routed, not fixed.
- **2026-11-14** (atom.broker `e8b087ce30`): the above reaches `main` in a
  squashed rebase; released in 0.9.3.x.

## 3. The fixes, in order

X0. **Find the clobbered implementation, bounded.** John: V1–V8 worked in
sessions over the last few weeks. Search atom.broker (all 2,609 tips, full
history) and eve for: a laws/value-model test; commits touching
`eve->clj`, `deliver-flat`, `$*`, `compare-and-set` whose message names
flat delivery, valhalla, or identity; and the commits that later changed
those lines. For each hit: branch, commit, which laws its tree passes (§5
test). Output: the clobbering commits named in this file. Budget: one
pass; the fixes below do not wait on it — anything found is merged or
diffed against them.

X1. **Excise IPlain; one generic `eve->clj` (V5).** Delete `d/IPlain`, every
`-plain` body (50 lines across 20 files at this pin), the `IsEve`
pass-through arm, and `eve.record`'s IPlain portability walk (it walks the
generic `eve->clj` result instead). `eve->clj`, in this order: nil and
scalars → themselves; an eve-deftype instance or flat sister → its flat
instance (§0 reading); an eve value array → the vector of its slots'
`eve->clj`; a flat handle → its host collection; eve maps / vectors / sets
/ lists, by shape → host collections, recursively; anything else → itself.
One `.cljc`, no host branch beyond the shape predicates each host already
has. All 57 `eve->clj` call sites keep the name and get the generic rule.

X10. **The read ends itself (V9). Lands before X2: it decides what X2's
exit is for a deref.** Start from `eve.guard`'s lazy views (`LazyMap` /
`LazyColl`: in-place leaf decode, child views on the same pin, a validity
check on every access), moved into `.cljc` for the gate hosts. Replace its
release point: not the end of a `with-guard` extent, but (a) the first
non-lookup operation on the view or any child view — which packs the
subtree there through X2's delivery and releases the pin — or (b) the host
GC collecting an unended view (JVM `java.lang.ref.Cleaner`, JS
`FinalizationRegistry`, Python `weakref.finalize`; jolt: its host's
equivalent, named here when measured). A lookup that lands on a scalar
releases at once. Views stay read-only (assoc/conj on a view end the read
first, then act on the flat value). `$` stays, as one snapshot over a body;
its exit is X2's. Done when: `(-> @a :b :c (into [1 2 3]))` packs only
`:c` (bytes packed measured against the whole root); a chain of lookups
ending in a scalar takes one pin and releases it before returning; an
unended view's pin is released after GC; 1,000 swaps while a view is held
leave the view reading its own version; and V1–V8 hold for every value a
read ends with.

X2. **One exit (V1, V2, V8).** Every exit — the end of a deref read (X10), `$*` (today
`(rr/run f d/eve->clj)`), `swap!`/`swap-vals!`/`reset!` returns, watch
delivery, the `:copy` foreign policy, the freeze-absent `plain-deref`
fallback — calls the one delivery (`eve.flat.freeze/deliver-flat`, inside
the pin). The delivery packs the whole value as one chunk (stream for a
sequential root): a `FrozenValue`/`BasedValue`/`ConcatBatch` met inside is
packed by its content, never surfaced. The JVM own-class path (zip into a
heap domain) stays only if its result is heap-owned and passes V2/V8;
otherwise it is removed so every host delivers the same flat form.

X3. **Value arrays pack (V1, V2; the old F1 throw).** The packer gives an
eve value array a rep (its slots, as a vector rep) instead of refusing it as
a reference; `e/aget`, `e/alength`, `e/array-seq`, `e/aclone` accept the
delivered flat form on every host. Fns and host objects stay refused.

X4. **Flat values inside the atom answer as their type (V4).** A flat
deftype stored in an atom (today a `FrozenValue` around its chunk) reads
back, inside `swap!` and `$`, as an object of its type — its sister over the
stored chunk — so protocol dispatch and field reads work in the atom
exactly as outside.

X5. **Construction (V3).** `e/make-array` (and every eve constructor that
throws outside a context today) gives its flat form outside every atom,
as `eve/deftype` already does.

X6. **CAS by value (V7).** `compare-and-set!` delivers the current value
under the pin (X2) and compares it to `old` with `=`.

X7. **The routed defect, fixed at its producer.** The persistent-sorted-set
BTSet's lost comparator (F-BB-DATOM-CMP's root, routed 2026-11-07): with X1
the BTSet is a deftype, takes the deftype arm, and is never rebuilt as a
set. The bb types adapter's `IsEve` marking is deleted with the arm. Done
when the eve.db.ds bb leg reads its DB with no pass-through.

X8. **Identity in the region (DS needs it; was F3).** `eve/same?` in
`eve.alpha`: inside a `$` or `swap!`, true when two persistent handles name
the same block of the same domain. Outside an atom it is not defined —
values there have no identity (V6); it throws there.

X9. **Docs where users see them.** `doc/api-guide.md` and
`doc/memory-model.md`: the §0 model and the V-laws, word for word.

## 4. Hosts

The model holds on every eve platform (the JVM plus the 17 rows of
`hosts.edn`), and the code is written host-generic (one `.cljc`). The gate
for this fix is the top hosts (John, 2026-11-19: "Testing all 17 for this
fix will take forever. You can target the top few for now - jvm, cljs,
jolt, basilisp, etc."):

| Gate host | Row | Why |
|---|---|---|
| JVM | `deps.edn` | named |
| ClojureScript on Node | `:cljs` | named |
| ClojureScript in a browser | `:browser` | DS's second host (DS plan §1) |
| jolt | `:jolt` | named |
| basilisp | `:lpy` | named |

A law is done when the §5 test is green on all five. The other 13 rows are
not run for this fix.

## 5. The laws test (`test/eve/value_model_laws_test.cljc`)

One `deftest` per row of §1 (V1 split per exit), written against
`eve.alpha` only, asserting the law. Red today on every RED row of §1. The
DS repo copies it as its first commit.

## 6. Rules

Each commit message carries the §1 rows it moves, before → after, and the
hosts it ran on. A law is not green from one host. Report row counts, not
prose. Order: X0 (one pass) alongside the §5 test; then X1, X10, X2, X3, X4, X5,
X6, X7, X8, X9.
