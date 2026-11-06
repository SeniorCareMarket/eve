# Eve 0.9.3.1 — Babashka adapter fix for F-LRU-BB: final report

Lane M, branch `fix/0931-bb` off origin/main (0.9.3), Fri Nov 6 2026.
Declared in chat and here, numbers as printed.

## The defect

A vanilla library deftype declaring `clojure.lang.ILookup` /
`clojure.lang.Associative` piecemeal — DataScript 1.8.1's
`datascript.lru` LRU, byte-identical upstream — is refused by
Babashka's SCI ("Babashka's deftype supports full map types (add
IPersistentMap to the interface list)"), so the library cannot load
on Eve's Babashka seat. Control (flagship lane, re-verified in probe
P1): stock bb with no Eve refuses the identical declaration.

## The fix — Eve's Babashka adapter layer; the library is untouched

- NEW `adapters/completion/bb/src/eve/host_completion.clj` — the
  `eve.host-completion` namespace for the bb seat (the file `eve.host`
  loads first). It captures SCI's `clojure.core/deftype` and re-roots
  that var with a presenter — the re-root pattern the jank seat's
  completion already uses — that appends `clojure.lang.IPersistentMap`
  to a deftype declaring `clojure.lang.ILookup` or
  `clojure.lang.Associative` without it, and passes every other
  deftype through argument-identical.
- `hosts.edn` (:bb and :bb-minimal rows) and `bb.edn` (:paths):
  `adapters/completion/bb/src` inserted immediately before
  `adapters/completion/clojure/src`, so the bb seat resolves
  `eve.host-completion` to the new file; every other seat is
  unchanged.

Measured SCI facts the design rests on (probes in `fix/probes/`,
bb 1.13.226): the refusal matches only the clojure.lang-qualified
spellings of those two interfaces, and only when IPersistentMap is
absent; unqualified spellings pass; other clojure.lang interfaces
(Counted, Seqable, IPersistentCollection, IHashEq, Indexed, …) are
refused with a different error ("only support protocol
implementations") and are outside this finding. A macro var's root
is called with (form env . args) only when the root is a plain fn —
a defmacro product is dispatched to its inner fn with plain args —
so the presenter is a plain function delegating to the captured
original.

What the presentation means for such a type: SCI implements it as
its map type; the map methods the declaration did not give (count,
seq, empty, cons, without) throw "Method not implemented", and
`map?` answers true — the same presentation SCI gives every
IPersistentMap deftype, Eve's own included. A type that offers only
lookup and assoc on the JVM offers only lookup and assoc here;
nothing it does offer changes meaning (see proof 1').

## Proofs (logs under fix/logs/)

- Smoke, bb 1.13.226, this tree: eve.host/eve.atom load, Eve atom
  swap answers {:n 2}; a foreign piecemeal deftype defined after Eve
  loads gets/contains?/map? correctly; Eve sorted-map and vector
  literals unaffected.
- Proof 1' — the F-LRU-BB frontier itself (proof1-lru.log): with Eve
  loaded first, the UNTOUCHED port's `(require 'datascript.lru)`
  succeeds and upstream `datascript.test.lru` passes:
  **2 tests / 20 assertions / 0 failures / 0 errors.**
- Frontier check: `(require 'datascript.db)` now proceeds past
  datascript.lru and stops only at the new finding below.
- Proof 3 — Eve's bb shared suite unregressed (bb-suite-buildbox.log,
  run on j.slaa.us with the runner's classpath showing
  adapters/completion/bb/src ahead of completion/clojure):
  **Ran 297 tests containing 5663 assertions. 0 failures, 0 errors.**
  Baseline was 297 tests (5,659 assertions when first measured on
  the older tree; 5,663 on the shipped 0.9.3 tree — this run matches
  the shipped tree's count exactly).

## FINDING F-IFNOOL — blocks tasked proofs (1) and (2); reported, not absorbed

Behind the lru wall stands a second vanilla JVM assumption:
`datascript/db.cljc` line 13, `(:import clojure.lang.IFn$OOL)`
(vanilla-ds line 11; the class's only use is the `defcomp` macro's
reify at db.cljc:475). Measured on bb 1.13.226:

- `(Class/forName "clojure.lang.IFn$OOL")` SUCCEEDS — the class is
  in bb's image — but SCI's `:import` resolution consults only its
  curated class registry, which lacks it (clojure.lang.RT and
  java.util.ArrayList imports resolve; IFn$OOL does not; a prior
  Class/forName does not prime it; clojure.core/import fails
  identically).
- The registry's runtime extension, `sci.core/add-class!`, takes the
  embedder's ctx ([ctx class-name class]); bb does not expose its
  ctx to classpath code (sci.core is require-able, but sci.impl.*
  namespaces are not registered for user code and
  babashka.impl.deftype exposes only ->scimap).

No presentation in Eve's adapter layer can satisfy this import; the
fix belongs to bb's class registry. This is the upstream-observation
writeup, held in our records per the standing rule (no upstream
filing). Consequences, stated plainly:

- Tasked proof (1) — `(require 'datascript.db 'datascript.core)`
  succeeds — is BLOCKED by F-IFNOOL, not by F-LRU-BB. F-LRU-BB is
  proven at its own frontier (proof 1' above).
- Tasked proof (2) — the flagship Babashka leg lands its worker
  share — is BLOCKED by F-IFNOOL: the flagship driver
  (drivers/contract/ds_flagship.cljc) requires datascript.core,
  which requires datascript.db. It becomes runnable the day bb's
  registry carries clojure.lang.IFn$OOL (or DataScript's import
  changes upstream); no Eve-side work remains for it on this seat.

## Out-of-scope notes

- bb 1.3.190: Eve's own load there still stops at the pre-existing
  0.9.3 floor — "Could not resolve symbol: sci.impl.deftype/hashCode"
  while loading eve.vec/eve.map — before any presenter question
  arises; unchanged by this fix and already a named finding.
- The presenter fires on any piecemeal map deftype compiled after
  eve.host loads, not just DataScript's — that generality is the
  seat's contract, and the pass-through for all other declarations
  is argument-identical.
