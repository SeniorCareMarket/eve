# jank probe (J0, 2026-11-04 ET)

What jank 0.1-alpha (jank 0.1-noble from ppa.jank-lang.org, 2026-10-28) loads of eve's shared `.cljc` as written. Run
from `eve/`, jank's module path first the scratch `stub/` (a completion re-rooting jank's `unchecked-*` TODO stubs
and a one-function `eve.encoding`: J1 and J2 replace them with `adapters/completion/jank` and `adapters/encoding/jank`):

```
LC_ALL=C.UTF-8 jank --module-path impls/jank/probe/stub:src:adapters/core/clojure/src:adapters/completion/clojure/src:adapters/int32/portable/src:test \
  run impls/jank/probe/hamt_util_probe.jank
# → Ran 5 tests containing 51 assertions. 0 failures, 0 errors.   (5.3 s)
```

`LC_ALL=C.UTF-8` is not optional: under the box's POSIX locale jank's lexer answers "Unterminated string" to any
non-ASCII byte in a string and "The Unicode character literal is unfinished" to one in a comment or symbol
(`utf8_string.jank`). `core_names.jank` lists the core names eve uses that jank does not resolve; `core_values.jank`
and `core_types.jank` are the value-model and type-form probes (jank has no `defprotocol`, `deftype`, `defrecord`,
`reify`, `extend-type`: `core_types.jank` fails at its first form). The full record is
`docs/port-gap-closure/coordination/reports/eve-jank-SIGNALS.md` (branch claude/eve-jank), the J0 GATE line.
