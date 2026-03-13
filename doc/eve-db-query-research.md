# Eve-DB Query Layer: Ecosystem Research

> Research into in-memory Clojure data querying tools for building eve-db's query layer.

---

## Context

Eve provides shared-memory persistent data structures (maps, vectors, sets, lists) backed by SharedArrayBuffer or mmap. For eve-db, we need efficient querying over data stored in Eve atoms — deep, indexed, arbitrary-shape querying without requiring users to restructure their data into datoms or triples.

The scaling story is handled by porting to Lustre for horizontal scaling. This document covers the **query** story.

---

## The Library You Were Thinking Of: `wotbrew/idx`

**Repo**: https://github.com/wotbrew/idx
**Dep**: `com.wotbrew/idx {:mvn/version "0.1.3"}`
**License**: MIT
**CLJS**: Yes

This is the one — it takes Clojure data in **any shape** (maps, sets, vectors), wraps it transparently, and auto-builds secondary indexes that you can query arbitrarily. The data stays in its original shape. No normalization, no datoms, no schema.

### How It Works

Two modes:

```clojure
;; Manual: declare indexes upfront
(def users (idx/index users-coll :id :idx/unique :name :idx/hash :created-at :idx/sort))

;; Auto: indexes built and cached on first query
(def users (idx/auto users-coll))
```

Indexes are **maintained incrementally** — `conj`, `assoc`, `dissoc` all keep indexes in sync.

### Query API

```clojure
(idx/lookup users :name "alice")       ;; hash index → all matches
(idx/identify users :id 42)            ;; unique index → single element
(idx/ascending users :created-at)      ;; sorted index → ordered seq
(idx/descending users :created-at)     ;; reverse sorted

;; Composite / nested
(idx/lookup users (idx/match :role "admin" :active true))
(idx/identify users (idx/path :address :zip) "94105")
```

### Property Protocol

Indexable by: keywords, functions, nested paths (`idx/path`), composites (`idx/match`), predicates (`idx/pred`), and function composition (`idx/pcomp`). Anything implementing the `Property` protocol.

### Why This Fits Eve-DB

- **Zero reshaping**: wraps existing Eve collections as-is
- **Transparent**: indexed collections print/behave identically to unwrapped ones
- **Incremental**: index maintenance on mutation, not full rebuild
- **CLJS-native**: works in the same environment as Eve
- **Drop-in**: `(idx/unwrap coll)` strips indexes, giving back the original collection
- **Composable**: can layer on top of Eve's HAMT maps and persistent vectors

### Limitations to Consider

- Index creation is O(n) on first use (~4ms for 10k items for hash index)
- Function indexes require same fn instance for identity
- Auto mode can accumulate unused indexes (memory pressure)
- Modification slower than unwrapped collections due to index maintenance
- Thread safety: auto mode uses mutable caching (non-destructive races)

---

## The Relational Companion: `wotbrew/relic`

**Repo**: https://github.com/wotbrew/relic
**Dep**: `com.wotbrew/relic {:mvn/version "0.1.7"}`
**License**: MIT
**CLJS**: Yes

Same author as `idx`. Where `idx` is collection-level indexing, `relic` is a full relational programming system. Implements the "Out of the Tar Pit" functional relational model.

### How It Works

```clojure
(require '[com.wotbrew.relic :as rel])

;; Databases are plain Clojure maps
(def db (rel/transact {} [:insert :Customer {:name "bob"} {:name "alice"}]))

;; SQL-style queries as Clojure vectors
(rel/q db [[:from :Customer] [:where [= :name "bob"]]])

;; Materialized views — incrementally maintained
(rel/mat db [[:from :Customer]
             [:join :Order [= :Order/customer-id :Customer/id]]
             [:agg [] [:total [rel/sum :Order/amount]]]])
```

### Key Features

- Queries are data (vectors of operators): `:from`, `:where`, `:extend`, `:join`, `:left-join`, `:agg`, `:sort`, `:limit`
- **Incremental materialized views**: define a query, it stays up-to-date as data changes
- Automatic indexing driven by a dataflow graph
- Constraints as declarative queries
- Clojure functions usable directly in query expressions
- 60fps reactive UI integration

### Why This Fits Eve-DB

Best for when data is naturally relational (tables of entities). Could serve as the high-level query API that sits above `idx`-level indexing, providing joins, aggregations, and materialized views.

---

## Datalog Over Arbitrary Nested Data: `alandipert/intension`

**Repo**: https://github.com/alandipert/intension
**Dep**: `[alandipert/intension "1.1.1"]`
**License**: EPL 1.0

### How It Works

Converts arbitrary nested maps/vectors into path-value tuples, then queries via DataScript Datalog:

```clojure
(require '[alandipert.intension :refer [make-db]]
         '[datascript.core :refer [q]])

(def pets [{:name "George" :species "Parakeet" :age 3 :owners ["Frege" "Peirce"]}
           {:name "Francis" :species "Dog" :age 8 :owners ["De Morgan"]}])

(def db (make-db pets))

;; Datalog queries over the nested structure
(q '[:find ?name :where [?e :name ?name] [?e :species "Dog"]] db)
;; => #{["Francis"]}
```

### Why It Matters

Proves you can get Datalog semantics over any-shape data with zero schema. Read-only. Good conceptual model for one possible eve-db query interface.

---

## Full Ecosystem Survey

### Tier 1: Strong Candidates for Eve-DB

| Library | Data Shape | Auto-Index? | Query Style | CLJS? | Fit |
|---------|-----------|-------------|-------------|-------|-----|
| **wotbrew/idx** | Any (maps/sets/vecs) | Yes, on-demand | lookup/sort/unique | Yes | **Primary** — collection-level indexing |
| **wotbrew/relic** | Sets of maps (relations) | Yes, dataflow | SQL-like vectors | Yes | **Complement** — relational queries + materialized views |
| **alandipert/intension** | Nested maps/vectors | Yes, path-based | Datalog (via DataScript) | Likely | **Conceptual model** — Datalog over arbitrary nesting |

### Tier 2: Valuable Components

| Library | What It Does | CLJS? | Notes |
|---------|-------------|-------|-------|
| **noprompt/meander** | Pattern matching + term rewriting over any data | Yes | Powerful for structural transformations; `search`/`find`/`rewrite` |
| **redplanetlabs/specter** | High-perf navigation/transformation of nested data | Yes | 30% faster than `get-in`; composable navigators |
| **lilactown/pyramid** | Normalized entity store with EQL queries | Yes | Good for graph-shaped data with entity identity |
| **wilkerlucio/pathom** | Attribute-based graph resolver + EQL | Yes | Auto-resolves attribute dependencies across data sources |

### Tier 3: Datalog Engines (Require Datom Reshaping)

| Library | Data Model | CLJS? | Notes |
|---------|-----------|-------|-------|
| **tonsky/datascript** | EAV datoms | Yes | The standard; fast; schema-optional |
| **replikativ/datahike** | EAV datoms | Yes* | Immutable, time-travel, pluggable backends |
| **quoll/asami** | Schemaless triples | Yes | Most flexible Datalog engine re: input shape |
| **juji-io/datalevin** | EAV + document paths | No | LMDB-backed, SIMD vector search, cost-based optimizer |
| **xtdb/xtdb v2** | Documents | No | Full SQL + XTQL, bitemporal |

### Tier 4: Normalized Stores

| Library | Notes |
|---------|-------|
| **ribelo/doxa** | Normalized map store + Datalog (via Meander); fast for CLJS; needs entity IDs |
| **den1k/subgraph** | Normalized store + reactive pull queries for re-frame; needs entity IDs |

---

## Recommended Architecture for Eve-DB Query

### Layer 1: Collection-Level Indexing (`idx`-style)

Wrap Eve's persistent collections (EveHashMap, EveVector, etc.) with transparent secondary indexes. This is the foundation — making any Eve collection queryable by arbitrary properties without reshaping.

Key insight from `idx`: indexes as metadata on existing collections, maintained incrementally.

**For Eve specifically**: since Eve collections live in shared memory (SAB/mmap), the index structures themselves could also live in shared memory, giving cross-process indexed queries.

### Layer 2: Relational Queries (`relic`-style)

For users who model data as tables-of-entities (the common case for app state), provide SQL-like query syntax with:
- Joins across Eve collections
- Aggregations
- Incremental materialized views (the killer feature — views that stay up-to-date as the atom changes)

### Layer 3: Deep Structural Queries (optional)

For deeply nested data, provide either:
- `intension`-style automatic path decomposition → Datalog
- `meander`-style pattern matching for structural search/transform
- `specter`-style composable navigators for surgical reads/updates

### The Key Architectural Decision

`idx` doesn't require data reshaping. That's the critical property for eve-db. Users store data in Eve atoms in whatever shape makes sense for their domain. The query layer indexes _over_ that shape transparently.

This is fundamentally different from the DataScript/Datomic approach (reshape everything into datoms) or the Pyramid/Doxa approach (normalize into entity maps). Eve-db should follow the `idx` philosophy: **your data shape is fine, we'll index it**.

---

## Next Steps

1. Prototype `idx`-style indexing over Eve's HAMT maps and persistent vectors
2. Evaluate whether index structures can live in shared memory alongside the data
3. Design the query DSL — `relic`-style SQL vectors are a strong starting point
4. Consider incremental materialized views as a core feature (reactive queries over Eve atoms)
5. Benchmark: index build time and query latency over Eve's mmap-backed collections
