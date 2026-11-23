# U2a — eve.flat-io: the seam, the portable implementation, chunk.cljc over it, conformance, the lookup (wave 1)

**Repo / worktree / branch:** atom.broker (eve), `/home/user/w/eve-u2a`, local branch `flat-u2a` from
`claude/nice-keller-r7n2fz` at the Phase-0 commit (which adds the empty adapter skeletons and doc/flat/FLAT-IO.md).
Read first: `/home/user/atom.broker/CLAUDE.md` (conduct), `doc/flat/FLAT-IO.md` = the contract (a copy of race-nk's
`lanes/CONTRACT-flat-io.md`), `doc/flat/FORMAT-V1.md`, `adapters/README.md`, `doc/adopter-tiers.md` §4 (the fast
set), and this lane roster's rules (`doc/flat/lanes/README.md`, a copy of race-nk's). Your partner lane U2b
writes flat-io/jvm and flat-io/js against the same contract at the same time — you own everything else in this unit.

## The work

1. **adapters/flat-io/portable/src/eve/flat_io.cljc** — the contract's functions, made by MOVING chunk.cljc's byte
   layer (ru8 … rf64, rvarint, varint-len, ba-copy-of, the IBuf interface/protocol and its `Buf` deftypes, dv-of and
   the BigInt helpers it needs) — moved, not rewritten: its :clj / :bb / :glj / :jank / :cljs arms come with it, so
   every host keeps exactly today's bytes. Add `key-cmp`, `bytes=`, `bytes-cmp`, `tuple`, `col` (portable versions:
   a tuple may be a plain persistent vector built from the reads — the fast roots make it a view).
2. **src/eve/flat/chunk.cljc over the seam**: every byte read and write through `fio/…`; its byte-level reader
   conditionals gone (count them before and after; the handles' surface conditionals — protocol names per host —
   stay, they are not bytes). Same for column.cljc / sio.cljc where they read chunk bytes.
3. **Wiring**: hosts.edn (every host row gets flat-io/portable, or the fast root U2b names — leave a TODO line for
   U2b's roots), deps.edn / bb.edn / shadow-cljs.edn classpaths as the other adapters do, adapters/README.md and
   doc/adopter-tiers.md rows (feature `flat-io`, inbuilt `flat-io/portable`, fast `flat-io/jvm`, `flat-io/js`,
   `flat-io/vs`).
4. **The conformance test**, adapters/flat-io/test (the slab-io/test pattern): every contract function against fixed
   byte strings — the golden chunks in test/eve/fixtures and the FORMAT-V1 edge rows (WIDE and narrow offsets,
   varints at 1 / 2 / 3 bytes, utf-8 of 1–4-byte characters, i64 past 2^53, NaN / -0.0 floats, key-cmp across the
   255 / 256-byte boundary) — run on the JVM, bb and Node, each answer equal to the portable answer.
5. **The lookup** (the 9.8 ms get ×407 on the JVM, race-nk LOG "EVE: flats measured on three hosts"):
   `lookup-node` walks a KMAP's keys linearly and a MAP's shape keys linearly, decoding each key. Make a KMAP lookup a
   binary search with `fio/key-cmp` (keys are in eve key-byte order, FORMAT-V1 §4), and a MAP's a search over its
   shape's sorted symbol codes / key bytes; keep the answers equal (contract_test, chunk_test).

## Tests (targeted, rule 3)

JVM: `clojure -M:jvm-test -n eve.flat.chunk-test -n eve.flat.contract-test …` (the flat namespaces
test/eve/flat/host_suite.cljc lists for :jvm). bb and Node: the :bb and :cljs suites host_suite.cljc names (its
`:runner` strings say how). Record before / after for each.

## Done bar

The flat suite green on the JVM, bb and Node with chunk.cljc over the seam; the conformance test green on all three;
byte-level reader conditionals in chunk.cljc counted before / after; the 1BRC-shape probe (race-nk `eve/flatx.clj`,
copy it into your worktree) on the JVM before / after — pack, get ×407, seq walk, format.
