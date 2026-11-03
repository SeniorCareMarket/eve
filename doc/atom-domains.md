# Atoms and atom domains

Every eve value lives in an atom, and every atom lives in an **atom domain**: a set of slab regions plus a root
region that holds the domain's atoms. A domain is in-process memory or files on disk that processes of any host map
and mutate together. This page covers both, and the domain API (`eve.atom`). It replaces 0.1.0's
`persistent-atoms.md`.

Every sample below was run as written on babashka 1.13.226 and on the JVM (JDK 21.0.12, Clojure 1.12.0) on
2026-10-20 ET, against this tree (see [getting-started.md](getting-started.md) for the classpaths).

## A domain is itself an atom

A domain's root region has 256 atom slots (`MAX_ATOM_SLOTS`, `src/eve/deftype_proto/data.cljc:333`).

- **Slot 0 is the domain's own value**: its registry of member names, `{"ns/name" slot}`
  (`src/eve/atom.cljc:66`). An `MmapAtomDomain` derefs, swaps and resets slot 0 like any atom
  (`atom.cljc:754-778`).
- **Member atoms are slots 1–255.** An `MmapAtom` is a domain-state and a slot index (`atom.cljc:722`). Creating a
  member claims a free slot by CAS, writes its value, then registers its name with one swap of slot 0. If two
  processes create the same name at once, exactly one registration wins and the loser frees its slot
  (`atom.cljc:859-913`).

So a domain holds at most 255 named atoms. A meta-atom (below) takes a slot of its own.

## In-process and disk domains

| You write | You get |
|---|---|
| `(e/atom v)` | an anonymous atom in this process's default in-process domain |
| `(e/atom ::name v)` or `(e/atom {:id ::name} v)` | a named in-process atom. **Each `:id` gets its own heap domain**, cached by id (`atom.cljc:1065-1085`) |
| `(e/atom {:id ::name :persistent "path/db"} v)` | a named atom in the disk domain at `path/db` (`:persistent true` uses the default domain `./eve/`) |
| `(eve.atom/persistent-atom-domain "path/db")` | the disk domain itself; opens it, or creates it when absent, cached by path |
| `(e/atom-domain v)` | a fresh in-process domain: slot 0 holds `v` (a map, or nil) with the entry `"__atom-domain__/root" 1` added, and member slot 1 holds `v`. It reads no options |

In process, the regions are the heap regions of the host's region adapter (`make-heap-region`: a heap `ByteBuffer`
on the JVM), and on ClojureScript the module's SharedArrayBuffers. On disk they are memory-mapped files
(`open-mmap-region`).

### `persistent-atom-domain`

```text
(persistent-atom-domain base-path & {:keys [capacities lustre?]})
```

- `:capacities` — a map from class index to bytes: each class's initial size.
- `:lustre? true` uses the fcntl byte-range-lock region, for file systems without cross-node atomics.
- Which process **creates** a domain is decided by one atomic exclusive create of `<base>.root`
  (`eve.process/create-exclusive!`). It is never an existence check followed by a create, which two processes opening a
  fresh domain together would both pass (`atom.cljc:797-813`). Every other process joins.
- `join-atom-domain` is the same call under another name.

### A disk domain's files

| File | Size | What it holds |
|---|---|---|
| `<base>.root` | 8,320 bytes | header, 256 worker slots, 256 atom slots (`atom.cljc:55`, `data.cljc:280-339`) |
| `<base>.rmap` | 262,144 bytes | the reader map (`atom.cljc:56`) |
| `<base>.slab0` … `<base>.slab5` | grows on demand | the six fixed size classes: 32, 64, 128, 256, 512 and 1024 bytes |
| `<base>.slab0.bm` … `<base>.slab5.bm` | grows with its slab | each class's allocation bitmap |
| `<base>.slab6` | grows on demand | the overflow class, for blocks over 1024 bytes |
| `<base>.keyring` | — | only for a domain opened through `eve.keyring/open-encrypted-domain!` (see [SECURITY.md](../SECURITY.md)) |

A run of the getting-started disk sample left exactly `db.root db.rmap db.slab0 … db.slab5 db.slab0.bm … db.slab5.bm
db.slab6` [ran].

### Journal durability and the retention floor

A disk domain opened with `:journal?` also carries `<base>.journal`, written through the host's
`eve.journal-file` adapter (`adapters/journal-file/`): the shared code in `src/eve/atom.cljc` owns the sequence —
lock, sweep, append, force — and the adapter owns only the file, its lock and its forces. Each record is one epoch
and the atom table that epoch published, so recovery installs the newest valid record whole. The domain keeps a
**retention floor**: the epoch the journal pins against reclamation. It is set at recovery, advanced at checkpoint
— where truncation rewrites the newest valid record as the journal's sole anchor record and moves the floor to the
anchor's epoch — and advanced by **flush-advance**: every flush records the newest durably appended epoch, and the
next flush moves the floor to it. Blocks a still-recoverable epoch needs are therefore never reclaimed out from
under the journal.

## The domain API

```clojure
(require '[eve.atom :as ea] '[eve.alpha :as e])

(def base (first *command-line-args*))
(def d (ea/persistent-atom-domain (str base "/db")))        ; open, or create when absent

;; member atoms: slots of the one domain, by name
(def users (ea/lookup-or-create-mmap-atom! d "app/users" {}))
(def jobs  (ea/lookup-or-create-mmap-atom! d "app/jobs" []))
(swap! users assoc :ada {:role :admin})

(println @(ea/domain-lookup d :app/users))                  ; {:ada {:role :admin}}
(println (ea/registered-name jobs))                         ; app/jobs
(println @d)                                                ; the domain is an atom too: its registry of names

;; a meta-atom: coordination state kept beside an atom, in the same domain
(def m (ea/meta-atom users {:owner "worker-1"}))
(println @m (ea/registered-name m))                          ; {:owner worker-1} eve.meta/app/users

;; versions and seals
(def v0 (ea/version users))
(swap! users assoc :bob {:role :user})
(println (= v0 (ea/version users)))                         ; false: a commit moved it
(println (= (ea/seal users) (ea/seal users)))               ; true: one stored version, byte for byte

;; e/$ — a read region: @ answers the stored value in place, held until the region exits; its result leaves plain
(println (e/$ (e/count @users)))                            ; 2

;; compare-and-set! on every host (ClojureScript's core one is for its own atoms)
(println (ea/compare-and-set!* jobs [] [:first]) @jobs)    ; true [:first]

(ea/close-atom-domain! d)
```

Output, the same on babashka and the JVM [ran]:

```
{:ada {:role :admin}}
app/jobs
{app/jobs 2, app/users 1}
{:owner worker-1} eve.meta/app/users
false
true
2
true [:first]
```

| Var | What it does |
|---|---|
| `(lookup-or-create-mmap-atom! domain "ns/name" init)` | the member atom by string name, created holding `init` when absent |
| `(domain-lookup domain :ns/name)`, `(domain-lookup domain k not-found)` | the member atom by keyword, or `not-found`. On hosts whose types take `ILookup`, `(:ns/name domain)` too |
| `(registered-name a)` | the name `a` is registered under (nil for the domain itself) |
| `(meta-atom a)`, `(meta-atom a init)` | a's meta-atom: the member registered as `"eve.meta/" + a's name`, created on first ask (exactly one process creates it). Its traffic never touches a's slot, so a's value, version and registry entry stay as they were (`atom.cljc:929-962`) |
| `(version a)` | `[domain-epoch root-pointer]`, epoch read first. Equal readings name one value of `a`; unequal readings say nothing (`atom.cljc:964-972`) |
| `(seal a)` | `{:ptr :name-hash :blocks :digest}`: an FNV-1a digest of every block a's stored version owns, read under a pin. Equal seals mean one version, byte for byte (`atom.cljc:976-1004`) |
| `(eve.alpha/$ & body)` | a read region: inside body, `@` of an eve atom answers its stored, slab-backed value, and every domain read stays pinned until the outermost `$` exits; body's result is materialized as it leaves. A `$` inside another joins it (`alpha.cljc:37`, `atom.cljc:556-568`, `read_region.cljc`) |
| `(read-pinned a f & args)` | `(f value & args)` with the stored value, under a pin released when f returns: eve's own bounded reads. **f's result must not keep the value** (`atom.cljc:696`) |
| `(compare-and-set!* a old new)` | CAS on every host |
| `(atom-sio a)` | the domain's slab I/O, to build a value in that domain outside `swap!` |
| `(close-atom-domain! d)` | releases this process's worker slot and stops its heartbeat. The domain's atoms are invalid afterwards |
| `(shared-atom? x)` | true for an eve atom or domain |

### Reads: deref materializes, except inside `$`

`@a` returns the value as the host's own collections, read under a pin (`atom.cljc:570-583`). Inside `eve.alpha/$`,
`@a` returns the stored eve value instead, lazy and slab-backed: `(e/$ (e/get (e/get @users :ada) :role))` reads one
branch of the atom and materializes nothing else. The region pins each domain it reads the first time it reads it and
holds every pin until the outermost `$` exits, so everything read in it stays readable, even past a `swap!` in the
same region; the region's result is materialized (`d/eve->clj`, a lazy seq realized) before the pins go, so no
slab-backed value leaves it. A network atom's deref inside `$` keeps its lease the same way, and the region releases
every lease it took as it exits (`net/atom.cljc`). Inside `swap!`'s function the value is slab-backed too, and must
not escape (see the 0.9 limitation on `swap!`'s return value in
[api-guide.md](api-guide.md#known-limitations-in-09)).

**Build eve collections inside `swap!`.** A collection such as an int-map, sorted map or RRB vector allocates where
it is built: in the running swap's domain, or else in this process's own slab. Storing one built elsewhere is
refused: "An Eve value allocated in a different slab domain cannot be put in this one; build it inside swap! (or with
the atom's domain sio, eve.atom/atom-sio)" [ran].

## Many processes on one domain

Run one script in two processes on the same path:

```clojure
;; counter.cljc — run it in two processes at once, on the same path
(require '[eve.alpha :as e])

(def path (first *command-line-args*))
(def c (e/atom {:id :demo/counter :persistent path} {:n 0}))
(dotimes [_ 200] (swap! c update :n inc))
(println @c)                                     ; the last process to finish prints {:n 400}
```

```bash
cd eve
bb counter.cljc /tmp/demo/db & bb counter.cljc /tmp/demo/db; wait
```

Ran with two babashka processes and with one JVM process plus one babashka process, started together on one fresh
path. The process that finished last printed `{:n 400}`, and a later reader saw `{:n 400}` [ran]. Every host in
`hosts.edn` uses the same on-disk format.

How writers coordinate:

- **Hosts with hardware atomics on a mapped file** (the JVM, Node through eve's N-API addon, ClojureCLR, basilisp,
  jolt, clojerl, glojure) CAS the atom's root pointer: lock-free — except a write that stores an existing piece at a
  second place (it raises a count, `eve.tally`): that write takes the domain's exclusive hold by CAS and waits for
  `ROOT_INFLIGHT` to drain (`with-held-write*`).
- **babashka** has no atomic read-modify-write on a mapped file. Its region (`adapters/region/jvm-nio-lock`) takes a
  `FileChannel` byte-range lock, and it writes under the domain's **exclusive hold**. The lock-free hosts count each
  write into `ROOT_INFLIGHT` and back off while `ROOT_HOLD` is set (`atom.cljc:178-295`). babashka is a full
  participant; its writes exclude the others' while they run. **One caveat for 0.9**: a lock-free process killed
  inside a write never takes its count back out of `ROOT_INFLIGHT`, and the next write that takes the hold — any
  babashka write, or any host's write that raises a tally count — then holds the domain waiting for it for ever,
  every other writer waiting behind the hold (measured for babashka). Where a
  lock-free writer of a shared domain can die — a crash, a `kill -9` — let babashka read the domain and
  write it through a gateway (`eve.net.gateway`, a network atom), as `capstone/eve-media` does.
- **Worker slots.** Each process that opens a domain claims one of 256 worker slots (`MAX_WORKERS`,
  `data.cljc:294`) and writes a heartbeat into it every 10 s (`atom.cljc:414`). A slot whose heartbeat is older than
  30 s (`HEARTBEAT_TIMEOUT_MS`, `data.cljc:314`) is stale: its pinned epoch no longer holds back reclamation, and a
  hold it left behind is cleared.

How a write reclaims the blocks it superseded is the subject of [memory-model.md](memory-model.md).

## Not in 0.9

- **Watches and validators.** No eve atom or domain implements them; `add-watch` on one throws.
- **`atom-domain` options.** `:sab-size` and `:max-blocks` (0.1.0) are gone; the function accepts options and reads
  none.
- The 0.1.0 SharedArrayBuffer atom (`eve.shared-atom`) is no longer what `e/atom` returns.
