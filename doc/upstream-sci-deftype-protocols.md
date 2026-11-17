# Upstream fix request: SCI deftype cannot implement IEquiv / IHash / IComparable

**Status:** Eve 0.9.3.2 ships **without nbb support**. This is the one #56 host leg
documented unmet for the release. This document is the record for the upstream
maintainer (borkdude / sci) — the wall, the measurement, and the exact change
that unblocks it.

## The wall

Under SCI (as embedded in nbb 1.3.204, stock), a `deftype` can implement only a
subset of the cljs.core protocols. Eve's exact-number types
(`eve/numeric.cljc` — EveBigInt, EveRatio, EveBigDec) are raw cljs deftypes
naming `IEquiv` / `IHash` / `IComparable` / `IPrintWithWriter` in a
`#?@(:cljs [...])` splice. Loading `eve.numeric` under nbb dies at that splice:

```
Protocol not found: IEquiv
```

All three spellings fail identically: `IEquiv`, `cljs.core/IEquiv`,
`clojure.core/IEquiv`. Minimal repro, no Eve code involved:

```clojure
(deftype Foo [x]
  IEquiv
  (-equiv [_ other] (= x (.-x other))))
;; => Protocol not found: IEquiv
```

## The measurement (the discriminator)

Battery: bare deftype against 29 core protocols, stock nbb 1.3.204. Exactly
**8 resolve** — IDeref, IFn, IMeta, IWithMeta, IPrintWithWriter, IReset, ISwap,
ISeq. The other 21 — including IEquiv, IHash, IComparable, ILookup, IAssociative,
ICounted — are unresolvable in any spelling.

That these protocols are genuinely required (not decoration) was also measured:
a bare deftype without them has identity `=`, per-instance `hash`, and a throwing
`compare` — wrong semantics for a numeric type.

## The ask

Register **IEquiv**, **IHash**, and **IComparable** in SCI's deftype protocol
registry, the same way IDeref and IFn are already registered. That is the whole
request; everything downstream is Eve's own work.

## What it unblocks

- `eve.numeric` loads under nbb → Eve loads under nbb → eve.db.ds (DataScript on
  Eve) gains its fourth #56 host. The Nov 7 nbb port work (7 fixes in Eve's nbb
  types adapter) is verified present in the current tree; numeric is a 0.9.3.2
  addition and is the **single** load-blocking site.
- One further nbb item is queued behind this wall and cannot be re-derived until
  it falls: the ds serialize JSON round-trip failure recorded against an older
  tree ("TypeError: a.sa is not a function"). It is **unconfirmed** at the
  current pin — the serialize namespace cannot load while numeric blocks it.

## Provenance

Measured 2026-11-12 at Eve release content d2b52829 (atom.broker
`release/eve-0.9.3.2`), eve.db.ds main 2c45e69c, stock nbb 1.3.204. Full repro
scripts and the 29-protocol battery are in the ds-lanes campaign record
(`nbb-fix/`, incl. REPORT.md) and available on request.
