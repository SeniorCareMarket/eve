# Specialized EVE Collections

## How EVE Data Works

When you put a regular Clojure value into an eve atom, eve writes it into its domain's slab memory as eve's own
persistent collections (hash map, set, vector, list). When you deref the atom, the value is **materialized** — read
back into the host's own collections — under a pin. Inside `swap!`, the function you pass sees the eve value in place
(as does `@` inside the read region `e/$`); elsewhere, `@` gives you plain data. You don't need to construct eve types directly — just
use normal Clojure code:

```clojure
(require '[eve.alpha :as e])

(def state (e/atom ::state {:users {} :items []}))

(swap! state assoc-in [:users 42] {:name "Alice"})
(swap! state update :items conj "new item")

@state  ;=> {:items ["new item"], :users {42 {:name "Alice"}}}
```

`{:users {} :items []}` is stored as an eve hash map holding an eve hash map and an eve vector, all in the atom's
domain (`doc/atom-domains.md`). From your code's side it is Clojure data.

## When You Need Specialized Collections

The standard eve map, set, vector and list cover most uses. eve also has collections with capabilities those do not:

- **Integer map** (`eve.deftype.int-map`): a 16-way PATRICIA trie keyed by 32-bit integers — merge, merge-with,
  range queries.
- **Sorted set** (`eve.deftype.rb-tree`): a persistent red-black tree — ordered iteration, membership.
- **Sorted map and sorted set with comparators** (`eve.sorted`): Clojure's `sorted-map`, `sorted-map-by`,
  `sorted-set`, `sorted-set-by`, `subseq`, `rsubseq`, plus `rank` and `nth-entry`.

All three are one `.cljc` source for every host. Their nodes live in slab memory: a constructor allocates in the
running `swap!`'s domain, else in this process's own slab. **Build them inside the function you pass to `swap!`.** A
value built outside belongs to another domain and is refused when stored:

```clojure
(e/atom ::x {:data (im/int-map)})
;; throws: "An Eve value allocated in a different slab domain cannot be put in this one; build it inside swap!"
(swap! a assoc :data (im/int-map))
;; throws too — (im/int-map) is evaluated before swap! runs
```

The samples below use `eve.core` (`ec/assoc`, `ec/dissoc`) and `eve.alpha`'s `e/conj` / `e/seq` on eve values: on the
JVM and ClojureScript they are `clojure.core`'s own calls, and on babashka (whose eve types answer eve's protocols,
not `clojure.lang`'s interfaces) they are what reaches the type. `clojure.core/assoc` on an eve int-map works on the
JVM and fails on babashka. Every sample on this page was run on babashka 1.13.226 and on the JVM (Clojure 1.12.0,
JDK 21) on 2026-10-20 with identical output.

```clojure
(require '[eve.alpha :as e]
         '[eve.core :as ec]
         '[eve.atom :as ea]
         '[eve.deftype.int-map :as im]
         '[eve.deftype.rb-tree :as rb]
         '[eve.sorted :as sorted])
```

## Integer Map (PATRICIA Trie)

An adaptation of Okasaki and Gill's "Fast Mergeable Integer Maps" — a 16-way branching PATRICIA trie with 32-bit
integer keys. Useful for integer-keyed lookup tables, sparse arrays, and range queries or merges.

### Basic Usage

```clojure
(def index (e/atom ::index {}))
(swap! index (fn [v] (assoc v :data (im/int-map))))
(swap! index update :data ec/assoc 1 :a)
(swap! index update :data ec/assoc 2 :b)
(swap! index update :data ec/assoc 3 :c)

;; deref materializes: the int-map reads back as a plain map
(get (:data @index) 2)          ;=> :b
(count (:data @index))          ;=> 3
(contains? (:data @index) 2)    ;=> true
```

### Building Several Keys at Once

`(im/int-map k v & kvs)` builds from pairs — inside the swap:

```clojure
(def scores (e/atom ::scores {}))
(swap! scores (fn [v] (assoc v :board (im/int-map 1 100 2 85 3 92))))
(swap! scores update :board ec/assoc 4 78)
(swap! scores update :board ec/dissoc 2)
@scores  ;=> {:board {1 100, 3 92, 4 78}}
```

### Negative Keys

Integer maps take the full 32-bit key range, negative keys included:

```clojure
(def temps (e/atom ::temps {}))
(swap! temps (fn [v] (assoc v :temps (im/int-map -10 "cold" 0 "freezing" 25 "warm"))))
(get (:temps @temps) -10)   ;=> "cold"
```

### Merge

```clojure
;; right wins on conflict
(swap! scores update :board (fn [m] (im/merge m (im/int-map 3 :replaced 5 :new))))
@scores  ;=> {:board {1 100, 3 :replaced, 4 78, 5 :new}}

;; or resolve conflicts with a function
(def counts (e/atom ::counts {}))
(swap! counts (fn [v] (assoc v :counts (im/int-map 1 10 2 20))))
(swap! counts update :counts (fn [m] (im/merge-with + m (im/int-map 2 200 3 300))))
@counts  ;=> {:counts {1 10, 2 220, 3 300}}
```

### Update and Range

```clojure
(swap! counts update :counts im/update 1 inc)

;; range: the entries with keys in [lo, hi], inclusive — on the swap's value, not on the atom
(swap! counts (fn [v] (assoc v :low (im/range (:counts v) 1 2))))
@counts  ;=> {:low {1 11, 2 220}, :counts {1 11, 2 220, 3 300}}
```

### Iteration

Deref gives a plain map, so every sequence operation works on it:

```clojure
(let [m (:board @scores)]
  (seq m)            ;=> the entries
  (reduce-kv + 0 m)  ;=> the sum of keys and values
  (into {} m))       ;=> a regular Clojure map
```

## Sorted Set (Red-Black Tree)

An Okasaki-style persistent red-black tree. Each node lives in slab memory; an insert path-copies (persistent,
immutable). Useful for sorted iteration and ordered membership.

### Basic Usage

```clojure
(def tags (e/atom ::tags {}))
(swap! tags (fn [v] (assoc v :tags (rb/sorted-set))))
(swap! tags update :tags e/conj "clojure")
(swap! tags update :tags e/conj "javascript")
(swap! tags update :tags e/conj "python")
(swap! tags update :tags e/conj "clojure")  ;; duplicate — no-op

;; deref materializes the tree as the host's own sorted set
@tags  ;=> {:tags #{"clojure" "javascript" "python"}}
```

### Building With Elements

```clojure
(def priorities (e/atom ::priorities {}))
(swap! priorities (fn [v] (assoc v :queue (rb/sorted-set 5 3 8 1 4 7 9 2 6))))
@priorities  ;=> {:queue #{1 2 3 4 5 6 7 8 9}}
```

### Reading the Tree in Place

`rb/member?` and the tree's own seq work on the eve value — inside `swap!`, or inside `e/$`, where `@` answers the
atom's value in place, held until the region exits, and only the region's result is materialized:

```clojure
(e/$ (rb/member? (:tags @tags) "python"))              ;=> true
(e/$ (vec (e/seq (:tags @tags))))                      ;=> ["clojure" "javascript" "python"]
```

### Any Comparable Type

Sorted sets order by `compare`, so keywords, strings and numbers all work:

```clojure
(def kws (e/atom ::kws {}))
(swap! kws (fn [v] (assoc v :kw-set (rb/sorted-set :z :a :m :f :b :q))))
(e/$ (vec (e/seq (:kw-set @kws))))              ;=> [:a :b :f :m :q :z]
```

## Sorted Maps and Sets With Comparators (`eve.sorted`)

`eve.sorted` is Clojure's sorted collections in eve memory: `sorted-map`, `sorted-map-by`, `sorted-set`,
`sorted-set-by`, `subseq`, `rsubseq`, and `rank` / `nth-entry` (each O(log n): every node counts its subtree). Its tree
is a treap whose priorities are a function of each key's bytes, so the same keys give the same tree on every host,
whatever order they arrived in. A comparator is a function value with no eve bytes: a collection built with one is
readable in this process and refused by a shared (disk) domain (`src/eve/sorted.cljc` ns docstring).

```clojure
(def sm (e/atom ::sm {}))
(swap! sm (fn [v] (assoc v :by-id (sorted/sorted-map 3 :c 1 :a 2 :b 5 :e))))

(e/$ (vec (e/seq (:by-id @sm))))                              ;=> [[1 :a] [2 :b] [3 :c] [5 :e]]
(e/$ (vec (sorted/subseq (:by-id @sm) >= 2)))                  ;=> [[2 :b] [3 :c] [5 :e]]
(e/$ (vec (sorted/subseq (:by-id @sm) > 1 <= 3)))              ;=> [[2 :b] [3 :c]]
(e/$ (vec (sorted/rsubseq (:by-id @sm) < 5)))                  ;=> [[3 :c] [2 :b] [1 :a]]
(e/$ (sorted/rank (:by-id @sm) 3))                             ;=> 2
(e/$ (sorted/nth-entry (:by-id @sm) 0))                        ;=> [1 :a]

;; a comparator — a boolean predicate is read as Clojure reads one
(swap! sm (fn [v] (assoc v :desc (sorted/sorted-set-by > 1 5 3))))
(e/$ (vec (e/seq (:desc @sm))))                                ;=> [5 3 1]

(swap! sm (fn [v] (assoc v :by-len (sorted/sorted-map-by (fn [a b] (compare (count a) (count b)))
                                                         "ccc" 3 "a" 1 "bb" 2))))
(e/$ (vec (e/seq (:by-len @sm))))                              ;=> [["a" 1] ["bb" 2] ["ccc" 3]]

;; outside any atom a sorted collection lives in this process's own slab
(vec (e/seq (sorted/sorted-set 3 1 2)))                        ;=> [1 2 3]
```

Also in `eve.sorted`: `find` (the entry at a key), `sorted-coll?`, `shape` (the canonical tree as nested
`[key left right]`), and the transient `dissoc!` / `disj!`.

## Transparency — Standard Data Structures

For most code you need none of these. Regular Clojure maps, vectors, sets and lists are stored as eve collections
when placed in an atom:

```clojure
(def app-state (e/atom ::app {:counter 0 :users {} :items [] :flags #{:active}}))

(swap! app-state update :counter inc)
(swap! app-state assoc-in [:users :alice] {:role :admin})
(swap! app-state update :items conj "new")
(swap! app-state update :flags conj :verified)
@app-state  ;=> {:flags #{:active :verified}, :items ["new"], :users {:alice {:role :admin}}, :counter 1}
```

The specialized collections are for what plain maps and sets do not offer: integer-keyed range queries and
merge-with, sorted iteration, comparators, rank.
