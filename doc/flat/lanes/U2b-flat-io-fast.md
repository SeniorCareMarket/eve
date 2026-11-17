# U2b — eve.flat-io: the JVM and JS fast implementations, and the three-host bench (wave 1)

**Repo / worktree / branch:** atom.broker (eve), `/home/user/w/eve-u2b`, local branch `flat-u2b` from
`claude/nice-keller-r7n2fz` at the Phase-0 commit. Read first: `/home/user/atom.broker/CLAUDE.md` (conduct),
`doc/flat/FLAT-IO.md` (the contract), `doc/flat/FORMAT-V1.md` §2–§5, `adapters/README.md`, `doc/adopter-tiers.md`
§4, `doc/flat/lanes/README.md` (your rules). Your partner lane U2a moves chunk.cljc onto the seam and writes the
portable root and the conformance test at the same time; you write ONLY your two roots and your bench.

## The work
1. **adapters/flat-io/jvm/src/eve/flat_io.clj** — every contract function, fast: reads through
   `MethodHandles/byteArrayViewVarHandle` (little-endian short / int / long / float / double views of byte[]) instead
   of per-byte bit assembly; `bytes=` / `bytes-cmp` / `key-cmp` through `java.util.Arrays/mismatch` and
   `Byte/compareUnsigned` (no copy); utf8 through `new String(b, off, n, UTF_8)`; the writer a byte[] with the same
   VarHandle stores; `tuple` / `col` a deftype over (byte[], data-off, n, kind) implementing IPersistentVector,
   Indexed, Counted, Seqable, IReduceInit, IHashEq (equal and hash-equal to the vector of its elements), whose writes
   answer a PersistentVector. No reflection (`*warn-on-reflection*` clean). Primitive-hinted signatures so a call
   site in chunk.cljc stays unboxed.
2. **adapters/flat-io/js/src/eve/flat_io.cljs** — the same over Uint8Array + DataView (one DataView per chunk, kept
   in the chunk object), TextDecoder for utf8 (`fatal: false`, matching the portable decoder's replacement rule —
   check it), i64 as js/BigInt (the contract), tuples over typed arrays where the kind allows a view (i32 / f64 /
   …) implementing cljs's IVector / ICounted / IIndexed / ISeqable / IReduce / IEquiv / IHash.
3. **Your own contract test until U2a's lands**: for every function, random and edge inputs (the edge rows U2a's brief
   lists) compared against a reference you write from the contract's text — fold it into U2a's conformance test at
   collation.
4. **The bench**: race-nk's `eve/flatx.clj` (pack, count / get ×407 / seq walk / format / `=` on the 1BRC result
   shape) as a .cljc run on the JVM and on Node (and bb as the portable host), each function's best of 20, with the
   portable root and with yours; plus a micro-bench of the seam itself (a million u32 / i64 reads, a key-cmp over
   407 keys, a tuple's reduce) per root.

## Tests (targeted, rule 3)
JVM: your namespaces through `clojure -M:jvm-test -n …`; Node: a shadow-cljs build of just your test namespace
(shadow-cljs.edn's :eve-test is the pattern) or nbb if it serves; never the whole battery.

## Done bar
Both roots implement the whole contract; your contract test green on the JVM and Node; the seam micro-bench and flatx
numbers, portable vs fast, per host, in your ledger. (The end-to-end flat suite over your roots is measured by the
coordinator at collation, after U2a's chunk.cljc lands.)
