# One type declaration, one EDN surface

`eve.deftype/deftype` and `eve.deftype/eve-deftype` install a reader when
loaded. The default printed form is `#your.ns/Type {:field value, ...}`;
the reader captures the positional factory and reconstructs that type.
As with ordinary Clojure records, round trips require EDN-readable field
values. This does not turn functions, open streams, or cyclic graphs into EDN.
Slab-backed values reconstruct into the current Eve domain.

```clojure
(eve.deftype/deftype Point [x y])
;; (pr-str (->Point 1 2)) => "#your.ns/Point {:x 1, :y 2}"
;; (clojure.edn/read-string ...) or cljs.reader/read-string: no options needed
```

An optional map immediately after the fields declares a custom tag, printer,
and inverse reader. The map is macro syntax, not an evaluated runtime map:

```clojure
(eve.deftype/deftype Point [x y]
  {:tag geometry/point
   :print (fn [p writer opts]
            (eve.encoding/write! writer
              (str "#geometry/point " (pr-str [(.-x p) (.-y p)]))))
   :read (fn [[x y]] (->Point x y))})
```

Alternatively supply `IPrintWithWriter/-pr-writer` once, as existing Eve
collections do. It translates to the host printer, including JVM `print-dup`
when available. Do not supply both `:print` and `IPrintWithWriter`. If a custom
printer does not emit the default field-map representation, provide its inverse
`:read`. A declaration may use a previously declared reader through a closure,
e.g. `:read (fn [data] (my-reader data))`; declaring it first lets the reader
function be defined later without capturing an unbound var value.

The default reader checks that its payload is a map. Missing fields are nil;
unknown keys are ignored (a plain type has no extension map). Registration is
namespace-local by default, replaces the same tag on reload, and preserves
other tags. Existing thread bindings and explicit `:readers` options retain
precedence over the defaults. No global constructor export is needed for EDN.

Record-generating wrappers can pass this declaration to the underlying Eve
`deftype`. If a wrapper prints a different tag or hides fields, it must declare
its public reader too; use `:read (fn [m] (map->Record m))`. The positional
factory is captured at load time, so later rebinding `->Record` does not alter
the default reader. The DataScript `def-eve-record` wrapper lives in DataScript,
not in Eve, and needs to forward these options to preserve its public record
format and extension-map semantics.

Basilisp's printer is registered on the concrete type, ahead of its general
collection printer, so a map-like type's declared tag is no longer discarded.
Target-specific registries live in Eve, not in the user's library.

## Registry ownership and host bridges

Reader functions and printer descriptors live under `:type-io` on the
namespace-qualified entries of `eve.deftype.registry/registry`. The existing
registry now has both its expansion-time layout role and a runtime IO role;
compiled ClojureScript populates its runtime instance when declarations load.
`registry/data-readers` derives the active tag map from those entries. There is
no separate Eve reader-registry atom. Re-registering a tag makes the latest
entry its active reader without discarding earlier types' printer information.

JVM readers consult their ordinary registries. On bb, Eve bridges safe EDN
with derived `:readers` and core reading with its public default-reader hook;
SCI's `*data-readers*` root alone does not cover every reader context. On
Basilisp, safe EDN needs both Var-root and Python-module function updates;
compiled calls read the latter. Its native `[input opts]` and Clojure's
`[opts input]` EDN arities both remain supported. Explicit EDN `:readers` and
core reader bindings take precedence. These backend bridges are installed by
Eve, never by consumer cljc. bb intentionally has no `print-dup` handler.

The default field map is emitted in declaration order, independent of the
host's map iteration order. Clojure's **core code reader** reserves dotted
unqualified tags for class/record constructor syntax; use an EDN reader for
an arbitrary dotted custom tag. This does not affect ordinary namespace/type
tags, or the portable EDN surface used by record wrappers.

## Four-host regression gate

Validated on the same frozen source tree with `eve.type-io-test`,
`eve.deftype.type-id-test`, `eve.map-entry-test`, `eve.deftype.rb-tree-test`,
and `eve.deftype.int-map-test` (all zero failures/errors):

| Host | Tests | Assertions |
|---|---:|---:|
| JVM Clojure 1.12.0 | 65 | 797 |
| bb 1.13.226 | 64 | 796 |
| nbb 1.6.214 | 63 | 788 |
| Basilisp 0.5.1 | 65 | 798 |

The IO tests cover plain types and slab-backed types, live mutable fields,
explicit and dynamic reader overrides, custom and protocol printers, legacy
unqualified EDN tags, and metadata on the existing type registry. Runtime
printers and readers for future slab-backed record wrappers use this same
machinery; no separate record mode is implemented by this change.

Optional installed hosts were attempted, not counted green: jank aborted on
an unavailable `en_US.UTF-8` locale; clje stopped on pre-existing unresolved
`long` in `eve/deftype_proto/data.cljc:275`. ClojureCLR was not installed.
