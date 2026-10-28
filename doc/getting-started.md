# Getting started

eve runs on eleven hosts from one shared source. A host is `src/` plus that host's adapter roots, the row of
[`hosts.edn`](../hosts.edn) that names it. This page starts you on the JVM, on babashka, and (in-tree) on ClojureScript.
The other hosts are covered in [platform-support.md](platform-support.md).

Every sample here was run as written on 2026-10-20 ET, on babashka 1.13.226 and on the JVM (OpenJDK 21.0.12, Clojure
1.12.0), against this tree. Those are the only runtimes these instructions were checked on.

## Prerequisites

| For | What ran here |
|---|---|
| the JVM | JDK 21 (21.0.12 tested; `adapters/thread/jvm` starts virtual threads, a JDK 21 API) and the Clojure CLI with Clojure 1.12.0 |
| babashka | babashka 1.13.226 |
| ClojureScript on Node | Node 24.22.0 and npm. Disk domains also need eve's N-API addon (`native/mmap_cas.cc`), compiled on `npm install` by a C++20 toolchain (Python, `make`, a C++20 compiler) |

## The JVM

eve's `deps.edn` default `:paths` are `src` plus the JVM's adapter roots (the same list as `hosts.edn :jvm`). A
project depends on the `eve/` directory:

```clojure
;; deps.edn of your project
{:deps {eve/eve {:local/root "/path/to/atom.broker/eve"}}}
```

`clojure -M first_atom.cljc` in such a project ran the first sample below [ran]. A git dependency on atom.broker
takes the same directory with `:deps/root "eve"` (not run here). Only the JVM's roots come with the dependency.
**A dependency's aliases never reach its consumer**, so a project on any other host lists that host's roots itself
(next sections).

## babashka

The classpath is `src` plus `hosts.edn :bb`'s roots. From `eve/`, `bb.edn`'s `:paths` are exactly that, so
`bb file.cljc` works there. From anywhere else, `bb classpath bb` prints the same classpath with absolute paths:

```bash
bb -cp "$(cd /path/to/eve && bb classpath bb)" file.cljc
```

## ClojureScript (in this tree)

ClojureScript's roots are `hosts.edn :cljs`. This tree builds with them through `deps.edn`'s `:node-test` alias and
`shadow-cljs.edn`'s builds. `npm install` builds the addon and `npm run test:compile` compiles the test bundles
(`target/eve-test/all.js`). A ClojureScript **consumer** must put the `:cljs` roots on its own `:paths`, because the
alias that carries them is eve's own and does not travel with the dependency. The build runs (`node
target/eve-test/all.js all`: 482 tests, 0 failures, 2026-10-21); a consumer project was not run in the 0.9
preparation.

## Your first atom

```clojure
(require '[eve.alpha :as e])

(def counter (e/atom ::counter {:count 0}))   ; in-process, named
(swap! counter update :count inc)
(println @counter)                              ; {:count 1}
(reset! counter {:count 10})
(println (compare-and-set! counter {:count 10} {:count 11}) @counter)   ; true {:count 11}
```

`e/atom` is `eve.atom/atom` on every host. An in-process named atom gets a domain of its own. Deref returns the value
as the host's own collections.

## A disk domain

```clojure
(require '[eve.alpha :as e])

(def base (first *command-line-args*))          ; a directory; the domain's files are base/db.*

(def p (e/atom {:id ::counter :persistent (str base "/db")} 0))
(println (swap! p inc))                          ; 1 on the first run, 2 on the next ...
(e/close-atom-domain! (eve.atom/persistent-atom-domain (str base "/db")))
```

Run twice with the same directory, it printed `1` and then `2`. The directory then held `db.root`, `db.rmap`,
`db.slab0`–`db.slab5`, their bitmaps `db.slab0.bm`–`db.slab5.bm`, and `db.slab6` [ran]. A process may also open the
domain itself, `(eve.atom/persistent-atom-domain path)`, and look its atoms up by name. See
[atom-domains.md](atom-domains.md).

## Two processes, one atom

```clojure
;; counter.cljc — run it in two processes at once, on the same path
(require '[eve.alpha :as e])

(def path (first *command-line-args*))
(def c (e/atom {:id :demo/counter :persistent path} {:n 0}))
(dotimes [_ 200] (swap! c update :n inc))
(println @c)                                     ; the last process to finish prints {:n 400}
```

```bash
bb counter.cljc /tmp/demo/db & bb counter.cljc /tmp/demo/db; wait
```

Run with two babashka processes, and with a JVM process and a babashka process together, it ended at `{:n 400}`
[ran]. The two need not run on the same host: every host in `hosts.edn` reads and writes the same files.

## Eve collections

`eve.alpha` turns plain Clojure maps, vectors, sets and lists into eve's own when they are stored in an atom. The
specialized collections (int-maps, sorted maps and sets, RRB vectors, typed arrays) you build yourself, **inside
`swap!`**, so they allocate in the atom's own domain:

```clojure
(require '[eve.alpha :as e]
         '[eve.atom :as ea]
         '[eve.core :as ec]
         '[eve.deftype.int-map :as im]
         '[eve.sorted :as sorted])

(def s (e/atom ::colls {}))

;; build eve collections inside swap!: they allocate in the atom's own domain
(swap! s (fn [_] {:ints (im/int-map 3 :c 1 :a) :by-name (sorted/sorted-map "b" 2 "a" 1)}))

;; inside swap! the value is eve's own: eve.core's vocabulary works on every host
(swap! s (fn [v] (ec/assoc v :ints (ec/assoc (ec/get v :ints) -10 :neg))))

(println @s)                                       ; deref materializes host collections
(println (e/$ (ec/get (ec/get @s :ints) 3)))       ; inside e/$, @ answers the slab-backed value in place: :c
```

Output on both babashka and the JVM [ran]:

```
{:ints {-10 :neg, 1 :a, 3 :c}, :by-name #eve/SortedMap{a 1, b 2}}
:c
```

Inside `swap!` and `$` the value is eve's own type. On hosts whose types carry the host's collection interfaces (the
JVM, ClojureScript, nbb, ClojureCLR, jolt, basilisp, clojerl), `clojure.core`'s `get`, `assoc` and `update` work on it
directly. That was run here on the JVM; for the others it follows from their coll adapter
([ADOPTER-TIERS.md §2.3](release-0.9/ADOPTER-TIERS.md)). On babashka, glojure and ValueScript, whose eve types answer eve's own protocols (`adapters/coll/protocols`),
use `eve.core`'s functions as above; they work everywhere. An eve collection built outside `swap!` cannot be stored
into an atom yet ([the 0.9 limitations](api-guide.md#known-limitations-in-09)).

## Threads

```clojure
(require '[eve.thread :as t]
         '[eve.thread.fold :as tf]
         '[eve.process])

(println @(t/future (+ 1 2)))                           ; 3
(println (doall (t/pmap inc [1 2 3])))                  ; (2 3 4)
(println (tf/fold + (vec (range 1000))))                ; 499500
(println (t/=>> (range 10) (map inc) (filter even?) (reduce +)))   ; 30

(def p (t/fixed-pool 2))
(println (t/with-pool p @(t/future :on-p)))             ; :on-p
(println (t/deref (t/future (eve.process/sleep-ms! 50) :late) 10 :timeout))
(t/shutdown! p)
(t/shutdown-default-pools!)
```

It printed `3`, `(2 3 4)`, `499500`, `30`, `:on-p` and `:timeout` on babashka and the JVM [ran]. `eve.thread` is one
executor service over each host's threads (`eve.thread-host`). See [api-guide.md](api-guide.md#evethread).

## Next

- [api-guide.md](api-guide.md): the user API, namespace by namespace
- [atom-domains.md](atom-domains.md): domains, member atoms, meta-atoms, versions, seals, many processes
- [network-atoms/README.md](network-atoms/README.md): atoms at a broker or a gateway
- [platform-support.md](platform-support.md): the eleven hosts and what each lacks
- [data-structures.md](data-structures.md), [collections.md](collections.md), [obj.md](obj.md): the collections
- [../SECURITY.md](../SECURITY.md): what eve encrypts, and what it does not
