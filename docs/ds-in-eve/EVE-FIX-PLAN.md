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

| Law | Statement                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| --- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| V1  | Every exit from an atom delivers flat data on the host heap: the end of a read (V9), `$` exit, `swap!`/`swap-vals!`/`reset!` return, watch old/new, the value `compare-and-set!` compares. No slab-backed object ever leaves a read.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| V2  | An exit delivers what the read reached — the whole value for `swap!`'s return, a watch, `$`'s result; the subtree at the end of the lookups for a deref read (V9) — as ONE complete packed value, whatever it holds inside — persistent eve data, flat values stored earlier, deftypes, value arrays. No in-atom wrapper (`FrozenValue`, `BasedValue`, `ConcatBatch`) ever surfaces.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| V3  | `eve/deftype` construction outside every atom gives the flat instance; inside a `swap!` gives the persistent instance. Both answer the type's protocols and field reads. Same for `e/make-array` and every other eve constructor.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| V4  | Clojure data with flat eve values nested in it, stored into an atom, becomes persistent eve data holding those flat values. Read back inside the atom, a flat value answers as its type (a flat `Box` is still a `Box` to protocol dispatch).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| V5  | `eve->clj` is `eve->cljs`'s rule on every host: eve maps, vectors, sets, lists → host collections, recursively. One generic fn; no per-type protocol. Inside an atom its result stored back is eve data again; outside it runs on flat data.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| V6  | Values have no identity outside atoms: two deliveries of one value are `=` and need not be `identical?`. `=` is value equality on every delivered form.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| V7  | `compare-and-set!` compares by value: `(compare-and-set! a @a x)` succeeds when no swap intervened.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| V8  | Flat delivered values live on the host heap: they need no pin, no epoch, no domain, read correctly after any number of later swaps and after the domain closes, and are reclaimed by the host GC.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| V9  | Outside `$`, a deref reads lazily and its read ends when no view of it can be used again (John, 2026-11-19: packing the whole atom per deref "not going to work"; rules B + C below). `@a` answers a pinned lazy **view** over the stored root — no copy. **Lookups** — `get`, keyword/map invoke, `get-in`, `nth`, `find`, `contains?`, `count`, `empty?`, a deftype field read, a protocol method call on a deftype view (the method runs with the view as `this`) — read in place: a **scalar** (nil, boolean, number, char, string, keyword, symbol, uuid, inst) answers itself; a **subtree** (eve map / vector / set / list, deftype, value array, stored flat value) answers a child view of the same read. **Consuming** operations — `seq` and all built on it (`first`, `map`, `filter`, `reduce`, `into`, `vec`, `keys`, `vals`, `doseq`), `=`, `hash`, `str`/print, `assoc`/`conj`/`dissoc`, storing into an atom, host interop — pack that subtree once (V2) and act on the flat copy. Reaching a scalar or consuming does NOT end the read: `(let [v @a] [(:x v) (:y v)])` reads one version. The read ends by **C** in code ValueScript compiles: the analyzer ends it after the last use of any of its views in their lexical extent (the `->` chain, the `let` body), packing a view at the point it escapes (returned, `def`'d, closed over); and by **B** on every other host: when the host GC collects the read's last view. |
| V10 | `$` works with the whole system, and is the convention (John, 2026-11-19: "make it a rule of convention to always use `$` for safety, but if anyone ever doesn't then all these fallbacks will work correctly"). Inside `$`: every deref, on every atom, reads lazily as in V9 under one pin per domain; lookups and consuming operations behave exactly as in V9; `swap!`, watches, `compare-and-set!`, construction (V3), `eve->clj` (V5) and nested `$` all work. At `$`'s exit every read taken inside it ends — deterministically, with no GC and no analyzer involved — and the result is delivered flat (V1, V2): no view escapes live. A view used after its `$` exited throws a named error (`:eve/read-ended`). Code inside `$` never depends on rules B or C.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          |

`eve->clj` on a deftype (my reading of "produce clj on that host" — John to
confirm in one word): a deftype has no Clojure collection shape, so
`eve->clj` gives its flat instance; on a flat instance it is the identity.
Eve's own collection types are not "deftypes" for this rule: they take the
collection arm. The deftype arm is checked BEFORE the shape arms, so a
map-signature deftype (the DataScript DB on bb) is never walked as a map —
this is what F-BB-DATOM-CMP needed, without a pass-through.

## 0b. Status (2026-11-19, branch `claude/magical-johnson-uv75vf`)

Laws test (`test/eve/value_model_laws_test.cljc`, 13 tests), at `v0.9.3.3`
(`a5df9d4`, `main`):

| Host        | Result                     | Left                                                                                                                                                                     |
| ----------- | -------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| JVM         | 46 assertions, 1 fail      | `v1-swap-return` — a correctness bug (V1)                                                                                                                                |
| cljs / Node | 46 assertions, 2 fail      | `v1-swap-return`; `v1-swap-vals` (cljs.core's non-Atom `swap-vals!` takes `-swap!`'s return) — both the same V1 bug                                                      |
| browser     | 61 tests, 677 pass, 2 fail | the same two                                                                                                                                                             |
| jolt        | 45 pass, 1 fail            | `v1-swap-return` — the V1 bug                                                                                                                                            |
| basilisp    | 8 pass, 2 fail, 14 error   | no flat layer on basilisp (`eve.flat.chunk` handles, sister, freeze have no `:lpy` arms): the next unit. `eve.alpha` did not load on basilisp at `f4a1c3a`; it loads now |

Landed in `v0.9.3.3`: X1 (IPlain excised; generic `eve->clj`), X2 part (one
flat form; own-class heap-domain delivery removed), X3, X4, X5, X6 (via X1),
X8 (`same?`), `swap-vals!`/`reset-vals!` atomic and flat on JVM seats, jolt's
test task and its atom re-roots.

**`v0.9.3.3` ships a known correctness bug: `swap!`'s return leaks a
persistent value.** It returns what `f` returned — slab-backed, unpinned; it
reads only until later swaps reclaim its blocks (the plan's E8). This breaks
V1. (Correction: it was first recorded here as "moved under X10, by design".
That was wrong: X10 is lazy reads and the `$`/GC/analyzer spikes; a leaking
return is a bug, filed there because the correct fix costs performance.)
The fix is on the session branch (`bd3487d`): `swap!`/`reset!` return the
new value delivered flat under the swap's pin — laws green on the JVM. Not
released, for its cost (JVM, µs per `swap!`): 100-entry map 257 → 4,058;
4,000-entry map 125 → 308,778. Deref of the same 4,000-entry atom at
`f4a1c3a` is 257,519 µs: the flat pack's existing cost, now paid per swap.
Next: profile and speed up `chunk/pack` (helps deref and `swap!` both), then
release the fix; the structural answer — incremental flat, unchanged subtrees
contributing their packed bytes — is a FORMAT-V1 change.

**X0 result** (one pass, 17 git commands): of the model, only "deref delivers
flat" was ever built (`claude/flat-deref-flip`, landed atom.broker
`e8b087ce30`, still on main). `$`'s result went through `eve->clj` from its
first commit (`147088b528`, Oct 23); CAS compared `(d/eve->clj cur)` since
`ba3b4ea275` (Oct 18); `88497b6328` (Nov 13, `claude/flat-reengine`) built
the flat work on IPlain. No ref built a deftype flat outside an atom or a
generic `eve->clj`. The one removal found: `fcfe141f15` turns flat deref off
on bb. Not reachable: the ~190 eve branches' history (fetched depth 1) and
`c7dc390` (under no ref).

### Load: B vs C on the real apps (2026-11-20, `ce24729` + the cohost-media start fix)

cohost-media (`bb cohost-media demo --headless --hosts jvm,bb,jolt,node,glj,browser`, 240 s cap per run; base
`f4a1c3a` completes in 131–136 s with `:supervised` and `:two-duplicate-groups` false):

| config                                 | result                                                                           |
| -------------------------------------- | -------------------------------------------------------------------------------- |
| B (`EVE_READ_END=gc`)                  | complete 127.9 s, base's checks                                                  |
| B, `$` a plain call (`EVE_REGION=off`) | complete 120.9 s, base's checks                                                  |
| C (`EVE_READ_END=commit`)              | fails at boot: `:eve/read-ended` in the supervisor (`supervise.cljc` 32, 37, 48) |
| C, `$` a plain call                    | the same                                                                         |

C ends a read at the domain's next commit by anyone: the supervisor holds a member's config read from the control
atom while the other processes commit, and the next lookup on it throws. B ties a read to the host GC: a discarded
`swap!` result's view holds its pin until the GC runs (`eve.read-region-test`
`what-a-region-read-is-not-freed-while-it-runs` fails under B, passes under C).

narcis (jolt; store, buffer, s0, viewport, editor gates): every gate passes under B, C, B and C without `$`. Without
`$`, bench-open typing under B is 717 ms against 173–201 ms; under C bench-open fails with `:eve/read-ended` in
`narcis.buffer/read` (a view held across a commit).

Regressions found by these runs and fixed: v0.9.3.3 as tagged breaks cohost-media's publish on glojure (`f02d952`);
with no view layer on bb/glojure every `swap!` packed the whole root (glojure 9 ms → 4.7 s, bb 1.4 s; `ce24729`);
`eve.alpha` did not load on bb (`ce24729`); the browser seat's `started!` overwrote its join (`a89927f`).

## 1. Measured against the laws (JVM, `release/eve-0.9.3.3` `f4a1c3a`)

Provenance: OpenJDK 21.0.13, Clojure CLI 1.12.0.1530; fresh `e/atom` per run,
fresh JVM per probe; six deftype field shapes (`^:int32`+string, untyped
holding map / vector / deftype, `^:Leaf` typed nested, `^:volatile-mutable`),
a DB-shaped type with an `e/make-array` field. Browser and the other 16 hosts:
not run.

| Law                                  | Measured                                                                                                                                                                                                                        | Status                                 |
| ------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------- |
| V1 deref                             | delivers `<Type>__Flat` or own class (heap domain); protocol answers                                                                                                                                                            | green                                  |
| V1 deref of a value array, any depth | throws `flat chunk: a value held by reference cannot be packed`                                                                                                                                                                 | RED                                    |
| V1 `$` exit                          | a deftype built in `swap!` escapes LIVE (slab `Box`); a value array escapes live (`ValueArray`)                                                                                                                                 | RED                                    |
| V1 `swap!` return                    | `EveHashMap` (slab-backed); its deftype a `FrozenValue`                                                                                                                                                                         | RED                                    |
| V1 watch old/new                     | no throw; flatness not checked                                                                                                                                                                                                  | unmeasured                             |
| V2                                   | a deftype stored, then re-stored inside `swap!` (`(assoc m :old (:db m))`), derefs as `FrozenValue` — no protocol                                                                                                               | RED                                    |
| V3 deftype                           | outside → `Box__Flat`; inside → `Box`                                                                                                                                                                                           | green                                  |
| V3 `e/make-array` outside            | throws `Eve data must be constructed inside a swap!`                                                                                                                                                                            | RED                                    |
| V4                                   | a flat `Box` `reset!` in is stored as `FrozenValue`; read inside `swap!` it is a `FrozenValue` — no protocol                                                                                                                    | RED                                    |
| V5                                   | `eve->clj` dispatches on IPlain; user deftypes and value arrays pass through LIVE; a stored flat `Box` (`FrozenValue`) → `PersistentArrayMap`, type lost                                                                        | RED                                    |
| V6                                   | `identical?` false, `=` true                                                                                                                                                                                                    | green                                  |
| V7                                   | always `false`: compares `(d/eve->clj cur)` (IPlain) to the delivered value (`atom.cljc` `compare-and-set`)                                                                                                                     | RED                                    |
| V8                                   | deref'd flat `Box` reads after 1,000 swaps                                                                                                                                                                                      | green (domain-close and GC unmeasured) |
| V9                                   | deref packs the whole root every call (`deliver-flat`: "Cost = one pack"); no lazy view outside `$`. `eve.guard` (JVM-only prototype) has lazy views, released at a `with-guard` extent's end; no GC or analyzer release exists | RED                                    |
| V10                                  | `$` reads lazily and pins per domain (green); its exit materializes through `eve->clj`/IPlain, so a deftype or value array escapes live and a stored flat value loses its type (V1/V5 rows above)                               | RED                                    |

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

X10. **`$` first, then the spike for life without it (V10, V9).** Lands
before X2: it decides what X2's exit is for a read.

1. **`$` (V10), unconditionally.** `$*` keeps its pins per domain and gains
   the V9 view semantics inside it (start from `eve.guard`'s `LazyMap` /
   `LazyColl` — in-place leaf decode, child views, a validity check on
   every access — moved into `.cljc` for the gate hosts). Its exit ends
   every read taken inside it and delivers the result through X2; a view
   used after exit throws `:eve/read-ended`. Done when every V-law's test
   also passes with its body wrapped in `$`, and nothing in `$`'s path
   touches GC hooks or the analyzer. `doc/api-guide.md` states the
   convention: wrap atom reads in `$`.
2. **Spike: the GC end (rule B), measured before it ships.** Views outside
   `$` register their read's pin with the host GC (JVM
   `java.lang.ref.Cleaner`, JS `FinalizationRegistry`, Python
   `weakref.finalize`; jolt's host equivalent, named when measured). Load:
   N threads/workers swapping a DS-sized value while M readers do
   `(-> @a :b :c (into []))` chains without `$`. Measure, against the same
   load inside `$`: swap throughput, slab bytes held (peak and steady),
   reclamation lag (epochs between retire and free), and pin lifetimes.
   **Go/no-go:** B ships only if Eve's throughput and slab growth are not
   bounded by the host GC's schedule — the numbers decide, recorded here.
   On no-go, a deref outside `$` takes the X2 whole-value delivery
   (correct, costly), and the docs say so.
3. **Spike: the analyzer end (rule C) in ValueScript.** The analyzer finds
   each view's lexical extent and escape points and emits the read's end;
   measured on the same load. Ships with B's verdict or alone.
4. **Holes.** Every leak or stale-view case found later is pinned by a
   test in §5 and fixed at its producer; the convention (`$`) is the
   workaround meanwhile, never the fix.

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

| Gate host                  | Row        | Why                           |
| -------------------------- | ---------- | ----------------------------- |
| JVM                        | `deps.edn` | named                         |
| ClojureScript on Node      | `:cljs`    | named                         |
| ClojureScript in a browser | `:browser` | DS's second host (DS plan §1) |
| jolt                       | `:jolt`    | named                         |
| basilisp                   | `:lpy`     | named                         |

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
