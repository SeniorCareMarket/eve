# Eve's scittle seat — application bundle build

Eve's scittle seat is a hosts.edn row (adapter roots read over HTTP by a
page's loader) **plus this bundle build**. The seat's page loads, in order:

1. `scittle.js` — stock scittle v0.8.33, compiled here from the unmodified
   upstream library (the seat's application build; upstream ships the same
   code as its dist bundle),
2. `eve-scittle-seat.js` — Eve's seat plugin module
   (`src/eve/scittle_seat.cljs`), a dependent module of the same build,
3. the program's `application/x-scittle` source tags (the :scittle row's
   roots).

## Why the seat carries a build at all

Stock scittle's SCI context omits the `cljs.core/MapEntry` class. Eve's
`adapters/encoding/js` instantiates it (`(MapEntry. k v nil)` in
`eve.encoding/map-entry`), so `eve.encoding` — and with it `eve.net.bytes`,
`eve.net.json`, `eve.net.portable-crypto` and `eve.crypto` — cannot load on
the seat. The gap is a property of the context the seat runs, and the
context is the seat's to configure:

- SCI resolves an unqualified `(Type. ...)` form through the context's
  `:classes` map. A SCI *namespace* entry does not do it (measured: merging
  `'MapEntry` into the context's `cljs.core` namespace map does not land —
  the built-in namespace's interns are not mutable through the opts merge;
  `register-plugin!` with `{:classes {'MapEntry <host class>}}` does).
- A plugin must be a module of the **same compilation** as the runtime:
  release builds rename the shared application object's properties, so no
  separately built file (or page script) can name the class or the
  registration fn in the stock dist bundle. Upstream's own plugins
  (promesa, reagent, …) are modules of scittle's build for this reason;
  nbb's context carries its class entries from nbb's own build the same way.
- The registered class is the host's own `cljs.core/MapEntry` (the plugin
  module references the base module's cljs.core), so `map-entry?`, `key`,
  `val` and equality behave exactly as in the compiled ClojureScript build.

The same plugin also supplies the `cljs.reader` namespace. Stock scittle
registers no `cljs.reader` for programs — `(require 'cljs.reader)` throws
"Could not find namespace: cljs.reader" — though the compiled cljs.reader
is in the bundle (the context's own `cljs.core/read-string` entry is a
handle onto it; the two share one tag table, measured). What is missing
is only the namespace registration, and a plugin's `:namespaces` merge
lands for a *new* namespace (unlike the built-in `cljs.core` map above),
so the plugin registers cljs.reader's public vars — `read-string`,
`read`, the tag-parser registrars, the timestamp parsers — compiled,
under their own namespace, plus `*tag-table*` and
`*default-data-reader-fn*` by value (the atoms they hold, so a program's
value-position read is the atom itself, as in the compiled build, and
`register-tag-parser!` mutates that same atom). A program's
`cljs.reader/read-string` is then the compiled build's reader itself,
not a substitute: its results compare `=` to `cljs.core/read-string`'s
on plain values and on the `#uuid` / `#inst` tagged literals, and a
pr-str → read-string round-trip is the identity on values.

No scittle source is modified: `vendor/` holds the pinned upstream trees
(scittle tag `v0.8.33`; sci at the exact sha scittle v0.8.33's deps.edn
pins, `6790624776b09297a22100438f05b6555a4ff56d`), fetched by
`fetch-deps.sh`, and the build's module structure mirrors upstream's
`shadow-cljs.edn` (base module + `:depends-on` plugin modules).

## Build

    ./fetch-deps.sh          # once: pinned upstream sources into vendor/
    clojure -M -m shadow.cljs.devtools.cli release seat

Output: `out/scittle.js` + `out/eve-scittle-seat.js`.

Compiler optimizations are `:simple` (see shadow-cljs.edn; the
`:advanced` release default exceeded this build box's memory — the
two-modules-one-compilation property the plugin mechanism needs does
not depend on the optimization level).
