# flat-io — the byte-level seam under eve's flat layer

The contract: `doc/flat/FLAT-IO.md`. One namespace, `eve.flat-io`, a different file on each host's classpath
(adapters/README.md); eve's flat code (`eve.flat.chunk`, `eve.flat.column`, `eve.flat.sio`) reaches a chunk's bytes
through nothing else.

| root | state |
|---|---|
| `portable` | the inbuilt root (flat lanes U2a, 2026-11-17): chunk.cljc's byte layer moved here with its `:clj` / `:bb` / `:glj` / `:jank` / `:cljs` arms, plus the contract's additions. On every `hosts.edn` row. |
| `jvm`, `js` | the fast roots (lane U2b): stubs on this branch; hosts.edn's TODO names where they go. |
| `vs` | the ValueScript root (lane U3, wave 2): a stub. |
| `test` | the conformance test every root answers — `eve.flat-io-conformance-test`. |

Run the conformance test over the root a host's classpath carries (from eve's root):

```
clojure -M:flat-io-test                                    # the JVM (deps.edn)
bb test:flat-io                                            # babashka (bb.edn)
clojure -M:node-test:flat-io-node -m shadow.cljs.devtools.cli compile flat-io-test \
  && node target/flat-io-test/main.js                      # Node (shadow-cljs.edn :flat-io-test)
```

A fast root is tested by putting it before `portable` on that classpath (on the JVM, Clojure's loader takes a `.clj`
before a `.cljc` of the same namespace wherever each sits; how a ClojureScript build resolves a `.cljs` beside a
`.cljc` of one namespace is for the collation to measure — the simplest row lists one root or the other).
