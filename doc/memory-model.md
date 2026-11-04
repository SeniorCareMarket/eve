# eve's memory model

How eve reclaims memory, stated as its two authorities state it, and where eve's code differs from them today.

**The authorities:** ValueScript's `docs/memory-model.md` — the model to implement, line-cited to its sources — and
its `docs/contention-domains.md` — the frame the model sits in. Both were written from eve's own design (its epoch GC)
and VSv1's runtime, which ran it. This page restates them for eve's readers; where the two disagree with anything
below, they win. The statement here is complete enough to read alone.

## The frame: an atom is a contention domain

From `contention-domains.md`:

- An atom's boundary is crossed only by **deref** and **swap** — both transactional, both written in the program text.
- What travels between atoms is **values** (and pointer-values that designate, never grant access). A slab reference
  into a tree is implementation inside one atom's pages and never travels.
- **Storing a foreign value into an atom copies it in**: an atom's tree is wholly its own.
- A design that needs to **count**, **register** or **scan** who holds a reference has let the wall go porous; close the
  door instead. No counters on headers, no hold ledgers, no settle scans — amended 2026-10-20 inside VS's runtime
  domain (one domain whose atoms share references): there THE TALLY, writer-maintained holder counts with deferred
  release, is the one sanctioned count (`memory-model.md` §THE TALLY); across every other domain wall the rule stands.

## The model

From `memory-model.md`'s "The model":

1. **Values are trees of blocks; an update path-copies.** A new root and a copy of the path to the change; everything
   else is shared by offset.
2. **A reader pins an epoch** before walking a tree and unpins after.
3. **A committed write reclaims what it superseded, at the write.** Pin, read, apply, CAS; on success bump the epoch,
   then — still pinned, both trees live — walk (old root, new root), collect the superseded blocks, queue them with the
   epoch, unpin, flush. Nothing else decides what a write frees: no scan of the new value, no reachability question.
4. **The flush frees by time**: entries stamped below the minimum pinned epoch **− 2** (the margin covers the window
   between reading the minimum and freeing).
5. **A lost CAS frees its own tree at once** — it was never published. In eve: the attempt's allocation log,
   "freed when the CAS loses, forgotten when it wins" (`LoggingSlabIO`, `src/eve/deftype_proto/alloc.cljc`).
6. **A free reclaims the descriptor and forgets the blob**: it clears the block's bitmap bit (classes 0–5) or merges its
   overflow descriptor (class 6, `eve.deftype-proto.coalesc`); the bytes stay as they were. Nothing is zeroed; whoever
   is handed the block next overwrites every field it uses.

The walk is a diff of the old tree against the new: a shared subtree is the same offset at the same position and is
never entered; a differing child is recursed into; superseded nodes are pushed after their children.

**What the model does not contain** — none of these is eve's, and none may be added beside the walk: a reachability
scan or mark/sweep over a whole value; a visit budget; an end-of-unit or end-of-event reclaim driven by journals; a
collector of any kind; an arena; a scratch buffer; zeroing.

## What a write leaves allocated

**One walk for every kind** (`eve.retire`, exact: the blocks the old version holds that the new one does not, never
one the new holds — checked against the allocation log by `test/eve/reclaim_test.cljc` and
`test/eve/jvm_atom_retire_test.clj`). A value is blocks pointing at blocks, each block read by its layout — a map's
or a set's HAMT nodes and the values among their entries, a vector's trie (only what its count says it holds) and
tail, a list's cells, an eve array, an eve value array's slots, a user's eve-deftype by the field layout every
eve-deftype registers as it is defined (an int32 field marked `^:eve/pointer` holds a block's offset: an int-map's
children) — save an eve object (0x1E), read as a leaf: the blocks its `:obj` / `:array` fields name are neither
followed nor counted, so they are not released with the object and an object does not keep one alive. The walk pairs
each new block with the old block at its place: a child both hold at the same position is shared and never entered —
a path copy, so an ordinary swap reads only the path it changed. Measured on the JVM (the final pass, 2026-10-21 ET):
one insert into a 5,000-node user tree, the walk 0.16 ms, a whole-value reach 25.0 ms.

**The tally rule** (2026-10-21 ET,: "a count on the shared piece itself,
raised when a write stores an existing piece anywhere — another atom, or a second place in the same value — lowered
when a write drops a pointer to it, freed only at zero through the deferred release"). A piece held at more than one
place carries a count (`eve.tally`: the places beyond the first). A write adds the pointers its new blocks hold and
drops the pointers its dropped blocks held:

- a pointer a new block holds to an existing piece raises it — `(assoc m :b (:a m))`, a value made from `:a` that
  keeps `:a`'s interior, a piece of another atom of the domain;
- a pointer a dropped block held lowers it; a piece whose last holder went is released through the epoch's deferred
  release and its own pointers dropped in turn; a piece another place still holds stays, and the walk goes no further
  under it — whatever is beneath was reached through it, so only the piece stored again carries a count, never the
  blocks under it;
- a new block and the old block at its place that hold the same child at the same position cancel: an ordinary swap
  moves no count;
- new blocks a write stored at two places (`(assoc m :a v :b v)`) are counted before the write publishes.

The count lives in count pages, ordinary slab blocks of the domain found from the root header's word at byte 32
(`ROOT_TALLY_OFFSET`); nothing is allocated until a count first leaves 0. A write whose walk raises a count commits
under the domain's exclusive hold (the one a lock-only host takes for every write; a lock-free host takes it by CAS), so
a raise never meets another write's release; every other write commits lock-free and only lowers. The walk is planned
under the attempt's pin before its CAS; a lost CAS undoes the counts its new blocks were given.

**One write allocates only what its new version keeps.** int-map's leaf split and branch-above, the red-black tree's
balance and delete, and the vector pop that drops a trie level made and dropped nodes inside one operation (never
published, never freed); they no longer do (a red-black insert or delete runs in a scope that frees what the result
does not hold).

**Transients reclaim** (2026-10-21 ET): a transient frees what each of its writes supersedes in
versions it made itself — the walk between two versions only it holds. Each transient allocates through a scope log
over its value's own domain (`alloc/scope-sio`, chained under a swap attempt's log, so a lost CAS still frees what it
kept) and frees only blocks that log made (`alloc/free-owned!`); `persistent!` ends the log. A transient that stores
an existing eve value by pointer stops freeing within itself — the piece may now be held at two places, and the
commit's walk counts it. So a build keeps its tree and the value it began from; the constructors (`(int-map k v …)`,
`(sorted-set x …)`, `(sorted-map …)`, the red-black set's) begin from no tree and keep their tree only. What stays:

- **The value a transient began from**, and a collection value one of its writes replaces (a value read out of the
  transient may still be in use). `(persistent! (reduce conj! (transient (sorted-set)) xs))` leaves the empty set's
  header — one block a build — which the code made; `(apply sorted-set xs)` leaves nothing.
- **A persistent chain the code makes and drops inside a `swap!`** — `(reduce assoc (int-map) ks)` — is the model's
  scope-local temporaries: a winning attempt forgets its log, and nothing else may decide those blocks are garbage
  (no reachability question, no journal). Build with a transient or a constructor.

## What eve's code does today — and where it diverges

A journal domain adds one more pin to that flush gate: its **retention floor**, the epoch its journal can still
recover to. Its advance is flush-advance: each flush records the newest durably appended epoch, the next flush advances
the floor to it, and a checkpoint advances it to the anchor record's epoch — so the flush never frees a block a
recoverable epoch still needs. The journal itself is the host's `eve.journal-file` adapter; the floor and its
advance live in the shared code, in `src/eve/atom.cljc`.

eve's reclamation lives in `src/eve/atom.cljc` (the swap, the retire queue, the flush), the collections' own diff walks
(`eve.retire`; the collections' own `collect-retire-diff-offsets` serve their transients), and the allocator
(`src/eve/deftype_proto/alloc.cljc`). It follows the model's shape — pinned readers, path-copying writers, a retire
queue stamped with the epoch, a flush gated on the minimum pinned epoch, a lost attempt freeing its log, frees that
forget the bytes. These places differ, measured against the code at the 0.9 release preparation (2026-10-20; D2 closed and D6 added 2026-10-21):

| # | The model | eve today | Where |
|---|---|---|---|
| D1 | the flush frees below the minimum pinned epoch **− 2** | frees `epoch < min-safe-epoch`, no margin | `atom.cljc`, `try-flush-retires!` |
| D2 | no reachability question | **closed 2026-10-21** (9.31): the two arms that built a reachability set over the new value (an orphaned nested collection, a change of kind) are gone with the tally — a nested value moved to another key is a raise and a lower, a change of kind the walk's ordinary drop | `eve.retire` |
| D3 | no scratch buffer | ClojureScript's codec keeps three reusable scratch buffers (A and B for a key and its value; a 7-byte pointer buffer) | `adapters/codec/js/src/eve/codec.cljs` |
| D4 | no counters, no liveness scans | each slab header carries an atomic free-block count (`SLAB_HDR_FREE_COUNT`); the minimum pinned epoch is found by scanning the root's 256 worker slots and skipping those whose heartbeat is stale (30 s); a thread's pins nest by a depth count | `src/eve/deftype_proto/data.cljc`, `atom.cljc` (`mmap-min-safe-epoch`, `pin-epoch!`) |
| D6 | counts only as THE TALLY (holder counts beside the blocks; a zero only queues, a later store takes it back, the flush frees only a block still at zero); across other walls, no counters (contention-domains.md §8, D2 as amended) | **since 2026-10-21**: a piece held at more than one place carries a count — the places beyond the first — raised when a write stores it again (a second place in one value, another atom of the domain), lowered when a write drops it; a write that raises one commits under the domain's exclusive hold; a zero releases through the retire queue with no still-zero check at the flush (a raising write holds the domain instead of taking back) | `src/eve/tally.cljc`, `src/eve/retire.cljc`, `atom.cljc` (`swap-attempt!`, `with-held-write*`) |
| D7 | the walk runs after the CAS, still pinned | the walk is planned under the pin before the CAS (`retire/plan`); its raises and lowers apply after it wins (`retire/apply!`) | `atom.cljc` `swap-attempt!` |
| D5 | a foreign value stored into an atom is copied in | an eve collection built in another domain is refused: "An Eve value allocated in a different slab domain cannot be put in this one; build it inside swap! …" (the byte-array codec), "swap!: Eve value was allocated in a different slab domain; construct it with the atom's domain sio (eve.atom/atom-sio)" (the swap) | `adapters/codec/byte-array/src/eve/codec.cljc`, `atom.cljc` |

These are recorded as divergences to reconcile toward the model, not as the model.
Until D5 is reconciled, build eve collections **inside** `swap!`:

```clojure
(swap! a (fn [_] {:ints (eve.deftype.int-map/int-map)}))   ; works on every host
;; (e/atom {:ints (eve.deftype.int-map/int-map)})          ; refused today (D5)
```

## Reading eve values

`@a` materializes the value into the host's own collections under a pin, then unpins: what you hold after a deref is
plain data and never refers into the slab. Lazy, zero-copy access to the stored tree is `swap!`'s function argument and
the read region `(eve.alpha/$ body...)`: inside it `@a` answers the lazy value, the first read of a domain pins it, and
every pin is held until the outermost `$` exits (a read stays readable past a commit in the same region); the region's
result is materialized before the pins go.
