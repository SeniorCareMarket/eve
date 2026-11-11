# Consumer deletion audit for ds-lanes e1

Source audited: atom.broker `lane-m/eve-db-ds-one-eve-impl`,
`a3b0d8bfdd9e7bf1a18ea445573042c01ac41e13`. No DataScript files were edited.

| File:base line | Snippet | Follow-up / deletion |
|---|---|---|
| `src/eve/db/ds/db.cljc:591–598` | `#?(:clj (defmethod print-method Datom ...) :cljr ...)` | Delete the entire conditional. Datom already has one `IPrintWithWriter/-pr-writer` at 507–509; Eve realizes it. Add `{:tag eve.db.ds/Datom :read (fn [v] (datom-from-reader v))}` to its type declaration, declaring `datom-from-reader` before the type. This preserves the existing vector payload. |
| `src/eve/db/ds/query.cljc:57–68` | `#?(:clj (defmethod print-method Relation ...) :cljr ...)` | Move the intended relation representation into ONE portable printer in its `def-eve-record` declaration, then delete the conditional. Decide whether to preserve the JVM-only `#Relation` debugging format or use the ordinary record representation; the latter already exists on the other hosts. A differing payload needs its matching `:read`. |
| `src/eve/db/ds/core.cljc:598–599` | `#?(:cljs (doseq [[tag cb] data-readers] (edn/register-tag-parser! tag cb)))` | Delete after Datom and DB declare their existing tags and inverse readers. DB/FilteredDB print `#eve.db.ds/DB` through `pr-db`; declare `:tag eve.db.ds/DB :read (fn [m] (db-from-reader m))`. Declare that reader before their types. |
| `src/eve/db/ds/core.cljc:611–612` | `#?(:lpy (alter-var-root #'*data-readers* ...))` | Delete under the same migration. Eve owns both the ordinary dynamic reader map and Basilisp's fallback ReaderContext registry. |
| `src/eve/db/ds/core.cljc:9–11` | `#?(:cljs [cljs.reader :as edn] :clj [clojure.edn :as edn] ...)` | This alias is used only for the registration above; remove the whole require branch after its deletion. Preserve public `data-readers` as a compatibility map if callers use it explicitly. |

This is **five conditional sites**, including the now-unused reader require.

`def-eve-record` is defined in DataScript's own `eve_record.cljc`, not in Eve.
It delegates to Eve `deftype`, so every generated type now gets an automatic
field-map reader. However its current printer uses `#namespace.Type` (dot),
while the generic default tag is `namespace/Type` (slash), and its public
factory hides `rec-meta`/`rec-ext`. Update that ONE portable wrapper to pass
`:tag` and `:read (fn [m] (map->Type m))` to the underlying Eve declaration,
and to forward an optional custom printer rather than generating two printer
groups. That preserves extension keys and public record format everywhere.
No per-host wrapper code is necessary. This prerequisite is not a new
host-specific branch and is not permission to delete registrations prematurely.

## Do not count these as solved printing branches

* `db.cljc:511`, `#?(:cljs (goog/exportSymbol "eve.db.ds.db.Datom" Datom))`:
  this exports a public JS constructor. The existing printer does not consult
  it, and the new reader captures a function rather than resolving JS globals.
  Printing/reading needs no export, but deleting it can break external JS API
  consumers; this task does **not** establish that API is unused.
* `query_v3.cljc`: no active host-conditional printing/reader/export site exists
  at the audited tip. Its active conditionals are the collections implementation,
  not printing. Do not claim an extra printing deletion there.
* DB, FilteredDB, AutoTempid, LRU already have portable printers at this tip;
  their comments mentioning old `print-method` forms are not live branches.
* `core.cljc:23` requires the separate pprint integration. It is not a tagged
  literal registry or a deftype printer; do not remove it on this change alone.
