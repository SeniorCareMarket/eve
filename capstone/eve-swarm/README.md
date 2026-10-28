# eve-swarm — one chat room, eve's dialects and the browser together

The aegis chat room (atom.broker's `src/aegis/chat`, served by `src/chat01`: a Worker and a Durable Object that
evaluate every write's claim in the clear) with a member on each of eve's dialects **and** the web page, all writing
and reading the same room at once. Each dialect runs eve's own client (`eve.aegis.chat.client`, written once against
eve's host interface) as a long-lived **seat**; the browser runs the page's own VS client (`client.wasm`). The swarm
proves the room's mechanics across all of them — messages, the poll and its ring votes, the private room, the
credential-gated announcements, a guest's scratch post under a delegated session, the audit log, leaving the room —
and times every write on every platform.

This is a capstone **spike** (2026-10-26): the stack is the one the browser uses today (chat01 under `wrangler dev`,
the room's pages stored by its Durable Object). Where the plan takes the browser next — blocks to the blob store by
presigned URL, atom.broker only for swaps (`aegis/docs/chat-peer/IMPL-PLAN.md` U2–U3) — the seats follow by the same
client: they share its code path, not a copy of it.

```
bb eve-swarm hosts                                   # the dialects, and which this machine runs
bb eve-swarm seat --host jvm                         # one seat: JSON commands on stdin, `SEAT {…}` answers on stdout

# the swarm (atom.broker's tooling: builds chat01, runs wrangler dev, the seats and the page in Chromium):
python3 -I test/aegis/e2e.py --only SWARM --log-dir <dir>          # SWARM_SEATS=bb,jvm,… picks the dialects
python3 -I test/aegis/e2e_rows/swarm.py serve <dir> bb jvm …      # the stack alone, seeded; <dir>/swarm.json
```

## Identity: each member signs with its own key

Every member is an Ed25519 key the operator registers (the operator door's `member <name> <public key>`); every named
write carries a claim signed with that member's private seed, which the Durable Object verifies against the roster. A
seat is given **its own seed and no other** (its join line); the browser's page gets its own in its invite link's
fragment. Measured against the live room (`.work/swarm/probe/p3.clj`, atom.broker):

| Attempt | The broker's answer |
|---|---|
| post as alice, signed with bb's key | denied `claim/signature` |
| post as bb, signed with a key nobody registered | denied `claim/signature` |
| post as a non-member, her own key | denied `claim/unknown-principal` |
| bb with bb's own key | ok |

Votes are ring signatures over the anonymous group's roster (no one learns who voted, a second vote in the same poll
is refused by its key image); a guest writes a scratch post under a session key the operator delegated, no member key.
What the page does not have yet is a login of its own: its key arrives in the invite link (whoever holds the link is
that member) rather than being generated and kept by the browser — a follow-on, not this spike.

## The seat

`eve.aegis.chat.seat` (`eve/src/eve/aegis/chat/seat.cljc`, shared cljc, no host named) reads one JSON command a line
through `eve.process/read-line` — a row this spike added to all seven process adapters — and answers one line:

```
["join", {endpoint domain atom user room spec_text seed | session}]   → {ok pk user ms}
["say", "public"|"private"|"announcements", text]                      → {ok status reason ms phases}
["poll", question, [options]]  ["vote", question, choice]  ["scratch", title]  ["scratch-post", title, text]
["audit", action, target]  ["add-member"]  ["remove-member"]  ["view"]  ["quit"]
```

`phases` is the client's phase clock (a session holding `:phases`): encrypt, deref, read (the state out of its pages),
plan (the auth: a ring signature), next-state (the record attested and placed), sign (the claim), pages (the new blocks
written), encode (the cas-root body), cas-root (the request).

## The dialects

| Seat | Dialect | Runtime (this box) |
|---|---|---|
| `bb` | babashka | v1.13.227 |
| `jvm` | Clojure on the JVM | Clojure CLI 1.12.0.1530 |
| `nbb` | ClojureScript on Node (nbb) | nbb v1.6.214, Node v24.22.1 |
| `cljs` | ClojureScript compiled for Node (shadow-cljs.edn `:eve-swarm-node`: `clojure -M:node-test -m shadow.cljs.devtools.cli release eve-swarm-node`) | shadow-cljs 3.3.6, Node v24.22.1 |
| `cherry` | cherry (ClojureScript to ES modules, Node; built by `bb eve-swarm:cherry`) | cherry-cljs 0.6.38, Node v24.22.1 |
| `cljr` | ClojureCLR | .NET 8.0.132 |
| `lpy` | basilisp | Basilisp 0.5.1, CPython 3.13.17 |
| `glj` | glojure (glj-eve) | glojure v0.7.22, go1.24.7 |
| `clje` | clojerl (the BEAM) | clojerl `4ad14e5` built from source, Erlang/OTP 25.3 + rebar3 from apt |
| `sci` | SCI on the JVM (org.babashka/sci, a library) | sci 0.15.59, Clojure CLI 1.12.0.1530 |
| `scittle` | Scittle (SCI in a browser page's worker) | scittle 0.8.33, Chrome for Testing 153 (headless) |
| `jolt` | jolt (Chez Scheme) | jolt `4fd288a2` from source, Chez Scheme 10.4.1 (threaded), `/opt/eve-tools` |
| `lg` | let-go (lg-eve) | let-go v1.13.0 with eve's Go package and `assoc-identity.patch` (`bb lg-eve`; go.mod: go 1.27) |
| `cljd` | ClojureDart, compiled to a Dart executable (`cljd/build.sh`; no Flutter) | ClojureDart 0.9.20261002, Dart SDK 3.13.7 (dart-archive's zip) |
| the page | ValueScript (`client.wasm`) | headless Chromium |

ValueScript has no http adapter yet, so the browser's page is the VS member.

### Tried, not seated (2026-10-26)

From ilevd/clojure-like by stars, the dialects picked and what stopped each:

| Dialect | what stops it |
|---|---|
| Squint | keywords are strings: the codec's images, and so every digest a claim signs, differ from every other member's (`doc/network-atoms/ADAPTERS.md`) |
| Phel | phel 0.34.1 lacks core functions eve's shared code calls (`rem`, `mod`, `quot` and more, not counted); `impls/phel` is a stand-in that does not run the shared code |
| jank | not installed: the jank apt repository and its key were refused by this session's permission policy, the GitHub release download answered 403 through the proxy, and a source build needs clang ≥ 22 (Ubuntu here has 20) |
| Ferret | no protocols, no `deftype`, no `try` — the shared code needs all three; its build tangles org files through emacs |
| Janet, Fennel | not Clojure readers: neither loads a `.cljc`, so a seat is a native reimplementation of the client under `impls/<lang>/` — eve's byte format took ~100 lines each in a spike (58/58 rows on Lua 5.4, 57/58 on Janet); ~5,880 lines of eve Clojure stand between that and a seat (`docs/port-gap-closure/coordination/reports/eve-janet-fennel-research.md` in atom.broker) |

### SCI and Scittle: eve's seat interpreted from source

Both run the seat from eve's source tree through SCI, the interpreter babashka and nbb are built on, as a library in
another program — so each needs what babashka and nbb configure SCI with, supplied by the capstone (never by eve's
shared code):

- **`sci`** (`sci/eve_swarm/sci_seat.clj`, deps.edn `:sci-seat`): a JVM main makes one SCI context whose `:load-fn`
  reads eve's `src/` and the babashka host's roots (hosts.edn `:bb`, reader features `:bb` then `:clj`) and runs the
  seat's `-main` on the process's stdin and stdout. The one thing missing next to babashka was the class table: SCI
  resolves a class only by a name its context lists (`{:classes {:allow :all}}` alone left
  `(IndexOutOfBoundsException. …)` in eve's codec unresolved), so the main lists every public class of the JDK packages
  eve uses and of `clojure.lang`. Babashka's adapters ran unchanged after that. Starting takes 5.3 s to the first answer.
- **`scittle`** (`scittle/eve_swarm/`, hosts.edn `:scittle`): a page cannot read a pipe, so the seat is a function —
  `globalThis.eveSeat(json) → json`, `eve.aegis.chat.seat/step` on the page's session — that atom.broker's
  `swarm.py` `PageSeat` calls through Playwright, one command a call, on a thread of its own. The page is cross-origin
  isolated (eve's browser adapters make SharedArrayBuffers) and the seat runs in a dedicated **worker**: a page's main
  thread may not block (`Atomics.wait` throws there, and the client sleeps after a lost race), and a worker's
  synchronous XHR is what eve's browser http adapter is. Scittle loads no files, so `scittle_load.cljs` is its loader:
  a namespace's source fetched from the page's origin and evaluated, a require it reports missing loaded first and the
  file evaluated again (43 eve namespaces, ~2–3 s). Three names eve's JavaScript adapters use and Scittle lacks are
  supplied there, as Scittle supplies `Cons`: cljs.core's `MapEntry`, `ExceptionInfo` and `uuid`, and the namespace
  `cljs.reader` (as `clojure.edn`). A write's signature was 252 ms of eve's portable Ed25519 interpreted; the browser's
  crypto adapter now signs over BigInt (`eve.crypto-bigint/ed25519-rows`, as ClojureCLR's does over BigInteger): 7.8 ms,
  the same 64 bytes on 12 seeds and messages.

## Performance by platform

Two measurements: each seat alone against a room (`.work/swarm/probe/p8.py` in atom.broker: one seat, N posts, a write
after the session's first — the first fetches and reads the whole room), and the SWARM row itself, where every member
writes at once and a write that loses the compare-and-set race is built again (its phases summed over the retries).
SWARM run-6 had seven dialects, a guest and the browser (67 s for the row); **SWARM run-9** (2026-10-26 10:52 AM ET)
fourteen dialects, a guest and the browser — sixteen writers at once, 150 s for the row, every check green. Under that
contention a write's time is mostly waiting: deref and cas-root are queued behind fifteen other writers at one Durable
Object (`wrangler dev`, one isolate), and a slower writer loses more races, each a whole rebuild.

| Dialect | alone, ms a write | SWARM run-6, median ms a write (8 writers) | SWARM run-9, median ms a write (16 writers) | where the rest goes (alone) |
|---|---|---|---|---|
| babashka | 194–218 | 762 | 2,539 | the round trips (deref ~35, cas-root ~120) |
| JVM | 204–367 | 1,493 | 7,763 | as babashka; JIT warm-up on the first writes |
| nbb | 237–263 | 1,931 | 7,770 | as babashka |
| ClojureScript, compiled | 139–212 | — | 6,380 | as babashka |
| cherry | 153–186 | — | 7,490 | as babashka (cas-root ~100, deref ~25) |
| ClojureCLR | 233–358 | 1,800 | 6,957 | as babashka |
| glojure | 308–328 | 3,764 | 8,685 | the read (~120), the interpreter's allocation |
| basilisp | 818–870 | 4,464 | 12,946 | the read (~290) and the pages (~280): basilisp's persistent structures |
| clojerl | 193–214 | 2,653 | 9,243 | as babashka once its atoms were volatiles (below) |
| SCI on the JVM | 142–221 | — | 7,769 | as babashka (bb 143–155 in the same room, the same minutes) |
| Scittle | 299–383 | — | 8,726 | the pages (~50), the read (~40), the claim (~33), interpreted; 518–608 before its signature moved to BigInt |
| jolt | 348–372 | — | 9,699 | sign ~145 (the portable Ed25519: jolt has no crypto binding), cas-root ~110 |
| let-go | 155–170 | — | 9,285 | the round trips (cas-root ~105, deref ~23): crypto, codec bytes and http are Go natives |
| ClojureDart | 180–230 | — | 6,044 | as babashka once its crypto was Dart's (crypto/dart: the signature over BigInt 4–12 ms, 165–200 portable; a vote 417 ms, 1,759 portable) |
| the page (VS) | — | 1,071 | 896 | (aegis/docs/chat-peer: the page's own per-post timeline) |

The alone column was measured on 2026-10-26: glojure, basilisp and clojerl after the last cuts below, babashka, JVM,
nbb and ClojureCLR at `25d6eaa1e7`, the rest as each seat landed (SCI and Scittle in a room of their own, `swarm.py
serve`, each also through every command kind).

Three writes were lost in SWARM run-7, the first run with all fourteen (each fixed where it was made, `8ee280c233`):
the page's ring vote (it passed client.wasm 1,024 random bytes; a ring draws 64 a member, and sixteen members need
1,088 — the client now answers `random <n>` and the page builds the post again), cherry's (cherry read
`clojure.core/mod` as the local `mod` eve.net.edwards binds — no point decoded, no key image; a third compiler patch,
`adapters/completion/cherry/compiler/patches.edn`) and ClojureCLR's (the race lost on eight tries; a write now gets
sixteen, the pause between up to 6.4 s).

What made them so (each measured with the phase clock and the platform's own profiler — Go's pprof through glj-eve's
`GLJ_CPUPROFILE`, cProfile under basilisp's interpreter; `EVE_SEAT_PROFILE=<file> bb eve-swarm seat --host glj|lpy`):

- **The room is not fetched or read again each write.** A seat's `/deref` names the root, spec and pages it holds
  (the page's protocol), so the broker answers `unchanged` or only the pages it lacks; its own accepted write's pages
  are held; `eve.net.pages/read-pages` reads from the last read (a block verified at the same page and offset is taken
  with its subtree, unread) and `eve.net.codec`'s `*checked*` memo decodes and checks only new blocks and map nodes.
  Before: glojure 12–24 s a write in the swarm, every write fetching ~700 KB and re-verifying every block.
- **Bytes stay bytes.** eve's http feature carries `:as :bytes` / `:body-as :bytes` on every adapter (Java, .NET,
  Python, Node, the browser, Go, the BEAM); the client hands eve.codec's output to the socket and the socket's to
  eve.codec. Before: four trips through base64 and a vector of ints per body (basilisp's cas-root 580 ms of client
  time; 150 after).
- **glojure's `nth` over a Go slice is one step** (`eve/adapters/completion/glojure/go/nth-slice.patch`): `(get bs i)`
  walked `i` seq cells — 18.6% of a glojure seat's CPU, and most of the rest was the collector cleaning up after it.
- **`page-cap` starts at its bound** (as VS's tier does): a 32-byte page class walked ~30,000 candidates a write on
  basilisp.
- **A key's XXH3-128 is hashed once, a map node checked once** (`*checked*`, by band and by node and place):
  basilisp spent most of a read re-hashing every key of every changed map node with eve's portable XXH3.
- **A read's state is a volatile, not an atom.** A clojerl atom lives in ETS — every deref copies its whole value into
  the process, every `swap!` copies it out and back — so the per-block memo and accumulators made each clojerl read
  slower than the last (1, 623, 2,305, 5,192 ms) and a view of four messages 9.4 s; eve.host's `volatile!` is the
  BEAM's process value, read in place (193–214 ms a write after).

Where a dialect is still slow it is its own runtime: glojure is an AST interpreter over reflective calls, basilisp's
vectors and maps are Python objects (pyrsistent) with a `functools.singledispatch` on every `seq`. What their
profiles point at next is `eve.net.pages`' block assembly over vectors of ints (`block-bytes`, `sub`): building pages
in the host's byte arrays (eve.encoding) instead.
