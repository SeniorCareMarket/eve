# VS thread adapter for Eve — `adapters/thread/vs` — LANE STATUS

Branch: `claude/eve-thread-vs` (from main `4f0be417b9`, verified by `git ls-remote` at dispatch).
Worktree (build server): `~/eve-thread-vs`. Final report: `eve/doc/thread-vs-adapter-2026-10-28.md` (this file is the
running STATUS; the report is written at close).

**Labels.** Everything this lane produces is **ADAPTER, new** — host-specific shimming for the VS seat inside Eve's
adapter tree, the sanctioned shape for host code. It is not Eve-as-is residence progress and will never be reported
as such. Operator framing (Oct 28 21:39 EDT): VS is to be a first-class citizen among Eve's targets, able to
receive thread calls from other runtimes just as it can send them; today `:vs` names the `thread/none` stub.

---

## 1. THE CONTRACT — `eve.thread-host` (HOST-CONTRACT §11, read firsthand)

Source: `eve/doc/unification/HOST-CONTRACT.md` §11 (normative), the `thread/none` stub
(`eve/adapters/thread/none/src/eve/thread_host.cljc`), the JVM adapter (`adapters/thread/java`, 97 lines), the Node
adapter (`adapters/thread/node`). The conformance suite every host's runner must pass is
`eve/test/eve/thread_host_test.cljc` (§11.3).

An adapter is **one file defining exactly the namespace `eve.thread-host`, fourteen functions, and nothing else**
(the JVM docstring's words; helpers are private). **No algorithm lives in an adapter** — pools, descriptors,
conveyance and drivers are `eve.thread`'s (`src/eve/thread*.cljc`, host-independent), written against this seam.

### The fourteen functions

| fn | contract |
|---|---|
| `parallelism` | how many threads can make progress at once (host fact) |
| `self` | this thread's address; a value, `=`-comparable, equal only to itself. A thread that was never started gets an address the first time it asks. |
| `start-threads! [n entry boot opts]` | start n threads, each running `((resolve entry) (assoc boot :self addr))`; entry is a fully-qualified symbol naming a var in a loaded namespace. opts: `:kind`, `:name`, `:requires` (namespaces the thread must load where heaps are separate). Answers the addresses in start order; n = 0 → `[]`; **never blocks the caller**. |
| `send! [addr msg]` | msg: any non-nil value; never blocks; answers nil. To self: legal, delivered in order. To an ended thread: dropped. Closures/host objects travel only where `ships-closures?`; across heaps a message is data and anything else is **refused at the sender**. |
| `receive! [ms]` | the next message from this thread's mailbox, or `:eve.thread/timeout`. ms < 0 waits forever, 0 polls, > 0 bounds the wait. The block is the host's real block (futex/condvar/park), not a poll loop. |
| `stop-threads! [addrs]` | asks threads to end by sending each `:eve.thread/stop`: a request, never a kill. Safe on ended/unknown addresses. |
| `ships-closures?` | can a closure be a message (shared heap)? |
| `driving` | a map: `:block` (`:thread` / `:cheap` / `:never`), `:parallel`, `:closures`, `:eval`, `:wake` (`:futex` / `:poll`), `:spawn-ms`, `:max-threads` (nil = unbounded, 0 = none), `:runtime` (`:jvm` / `:node` / …) |
| `attempt [thunk]` | runs thunk; `[true v]` or `[false e]` — the portable try, for code that must run where the host's own try is macro-less |
| `error-data [e]` | any exception → `{:class … :message … :data …}`, its ex-data under `:data` |
| `capture-frame` | the calling thread's dynamic-var bindings as a value, where `ships-closures?`; else nil |
| `call-with-frame [frame f]` | call f with frame's bindings in force; identity where heaps are not shared |
| `var-symbol [v]` | the fully-qualified symbol naming var v (for conveying "run this var" as data) |
| `eval-form` | OPTIONAL (14th): evaluate a conveyed form; where the runtime cannot, it is refused loudly, never silently ignored. |

### Standing messages and refusals

- `:eve.thread/stop` — the stop request. `:eve.thread/timeout` — receive!'s no-message answer.
- **Exit notice:** every started thread, on ending for any reason, sends its starter
  `{:eve.thread/exit addr :reason r}`; r = `:normal`, or the `error-data` of what it died of, or `:killed` where a
  host can only kill. **A dead thread is never silent.**
- Refusals are ex-infos whose data carries `:eve.thread/refused` ∈ `{:host-object :eval :no-threads :deadlock
  :cannot-block}`: a closure/host object across heaps → `:host-object`; `eval-form` where there is no evaluator →
  `:eval`; `start-threads!` where there is no second thread → `:no-threads`; `receive!` forever where nothing can
  ever arrive → `:deadlock`; blocking the one thread a single-threaded runtime cannot spare → `:cannot-block`.
- An address's mailbox dies with its thread: a thread that ends drops what it never read.

### What the conformance suite holds every adapter to (`eve/test/eve/thread_host_test.cljc`)

Contract answers (self/parallelism/ships-closures?/driving); self-send in order; receive timeouts (0 = poll
< 50 ms; an 80 ms wait reads 70..5000 ms; a negative wait — with threads: a `:later` message lands after a 60 ms
sleep; without threads: a `:deadlock` refusal); start-n (3 distinct addresses, echoes arrive as the given
addresses, n = 0 → []; without threads: `:no-threads` refusal); send-and-receive data equality (including
`:big 4503599627370496`, unicode, sets, nested values) and 100 messages in order; stop → one exit notice per
thread, reason `:normal`; attempt/error-data; binding frames (closures = true: a frame captured under `*conf*` = 10
is re-established on this thread AND on another thread via a `:frame` op; a plain `:call` sees the root 1;
closures = false: the frame is nil); closures-in-messages (true: the closure arrives and runs there; false:
send! refuses `:host-object`); eval-form per the declared `:eval`; a dead thread is never silent (die-at-once
notice carries `:message "die"` and `:data {:who :conformance}`; a send to the dead is dropped, not an error);
**round trips: 100 round trips each carrying the captured frame as a value, median batch of 5 ≤ 100 ms**;
grandchild (a thread starts a thread — every pair of threads directly reachable; the grandchild's end reaches its
starter, which forwards). Test opts: `{:requires '[eve.thread-host-test] :name "eve-conformance"}`; per-answer
wait bound 30,000 ms.

**How the two real adapters do it (shape references).** JVM: one `Thread` per eve thread, a
`LinkedBlockingQueue` mailbox per address in a global atom registry, `poll` with the ms budget, bindings conveyed
via `binding-conveyor-fn`, exit notice sent by the worker wrapper before the mailbox is unregistered. Node: one
worker per thread, integer addresses, a SharedArrayBuffer doorbell word per thread (`Atomics.wait`), messages as
EDN data over per-pair MessagePorts (heaps separate → `ships-closures?` false).

## 2. THE VS SIDE — machinery map (read firsthand in this tree)

- **The mesh** (`vs/wasm-threads/`): a VS program runs as N wasm instances over ONE shared memory. Instance 0
  (main) runs `_start`; workers 1..N enter at `vs_worker_loop`. The TCB (task array: 4,095 slots × 16 B) sits in
  shared memory; `transform.py` turns a compiled program into its `.threads.wasm` (+ `.tcb.json`) by rewiring the
  mesh hooks **by name** — both the public hooks and their `*` leaves (B-11, 2026-10-27: the inliner copies small
  defn bodies into later callers, so the public hooks in `vs/src/vs/thread.vs` are one call each to a leaf defined
  last; the rewire reaches the leaves, and the transform FATALs for listed callers whose hook was inlined away).
  Hosts: `node vs/wasm-threads/mesh.mjs <m.threads.wasm> --workers N` and the wasmtime host
  `vs/term/hosts/wasmtime/vs-mesh` (same protocol). Workers adopt the **globals image** main publishes at its first
  spawn; they do not replay `_start`.
- **`vs.thread`** (334 lines): the in-program hooks — `mesh-active?`, `mesh-pool-size`, `mesh-spawn` (post a
  closure to a worker, answer a slot handle; −1 when every slot is currently full; the closure is a slab value and
  any worker runs it), `mesh-await`, `mesh-result`, `mesh-claim`, `mesh-rethrow`, `worker-id` (0 on main, ≥ 1 on
  workers). `thread-future` is built on spawn and **conveys the caller's dynamic bindings** to the worker
  (`get-thread-bindings` / `with-bindings*`, and the frame fns exist in `vs.core`: `binding-conveyor-fn`,
  `push-thread-bindings`, `pop-thread-bindings`). The pool-state atom is a slab value — "on the mesh every thread
  reads the one main made" — so top-level atoms in a program are shared by all instances. Serial placeholders:
  `mesh-active?` → false, `mesh-pool-size` → 1, `mesh-spawn` throws, `worker-id` → 0.
- **Futex primitives in programs** (`vs/src/vs/kernel/wasm/program_runtime.vs`, the `mmap` door): plain loads and
  stores at any alignment; sequential-consistency atomics at natural alignment (`load-i32`, `store-i32!`,
  `cas-i32!`, `add-i32!`, i64 variants); `(mmap/wait-i32 a expected ms)` = `memory.atomic.wait32` — ms may be
  fractional, negative waits without bound; answers 0 woken / 1 not-equal / 2 timed-out; `(mmap/notify! a n)` wakes
  at most n waiters, answers the count woken. `(mmap/reserve! n)` claims whole ~1 MB slab regions for raw use and
  answers the first usable address (0 on failure). In-repo idiom: `eve/adapters/region/vs/src/eve/region.vs` (a
  reserved scratch word; wait-i32 as a nap).
- **The existing VS adapters are `.vs` files** (`eve/adapters/process/vs/src/eve/process.vs` etc.): they
  `(:require [vs.thread :as th])`, use `System/currentTimeMillis` for now-ms, `Thread/sleep` for sleeps, exceptions
  via `(catch :default e …)`. `eve.process` gives me `now-ms` and `sleep-ms!`.
- **Availability of pieces on the seat:** `resolve`, `ex-info`/`ex-data` (as `prog/ex_info` in core), var metadata
  (`(:name (meta v))` yields the var's symbol per core.vs), `binding`, dynamic vars, `future` — all present in VS
  core. Whether a compiled program carries an evaluator for `eval-form` is a probe (below), not an assumption.
- **`bb test:vs`** (eve/bb.edn) compiles `test/eve/runner/vs_main.vs` with the installed `vs` over the hosts.edn
  `:vs` roots + `test/`, runs it under plain `wasmtime run` (threads + shared-memory flags, ONE instance), and
  requires output `^\d+ passed, 0 failed$`. It runs NO thread tests today (eve.thread does not run on the seat
  yet). The mesh proof needs the transform + a mesh host — a separate runner, like `vs/wasm-threads/tests/run.py`
  drives its own programs.

### Contract → VS machinery

| contract op | VS backing |
|---|---|
| thread | one long-lived mesh task per Eve thread: `start-threads!` resolves the entry on the starter, creates the mailbox, and `mesh-spawn`s a wrapper closure; the wrapper binds `*self*`, runs `(entry (assoc boot :self addr))`, and on exit (normal or throw) unregisters the mailbox and `send!`s the exit notice to the starter (the JVM wrapper's order) |
| address | a long from an `mmap/add-i32!` counter in a reserved meta window (below); the starter's own address is 1 by the same allocation, so starter and started addresses live in one space — the exit notice's `:eve.thread/exit` key and the suite's address equality both work on plain values |
| mailbox | **a VS atom holding a vector** (values stored into atoms are held by the runtime — custody-safe by construction; a raw-pointer ring buffer in mmap memory was rejected: stored values there would be held by nothing, the Oct 28 tally-custody lesson), plus one futex word per mailbox |
| `send!` | `swap!` conj onto the mailbox atom, then bump the mailbox's futex word and `notify!` it. Never blocks. Closures are slab values and `mesh-spawn` itself posts closures across instances → `ships-closures?` **true** |
| `receive!` | check the queue; if empty, snapshot the futex word and `wait-i32` — a send between check and wait bumps the word, so the wait answers not-equal at once (no missed wakeup); the deadline is kept with `eve.process/now-ms`. Off-mesh: poll, or sleep-then-timeout for a bounded wait, `:deadlock` refusal for a forever wait (the `thread/none` serial semantics) |
| `self` | the wrapper binds a dynamic var `*self*` for started threads. Main / any unstarted context lazily registers its own mailbox, memoized per instance in a shared atom map keyed by `(th/worker-id)` — top-level atoms are slab-shared, so a naive self-cache would hand every instance main's address |
| frames | `capture-frame` = `get-thread-bindings`; `call-with-frame` = `with-bindings*` — core's own binding conveyance, the same machinery `thread-future` uses |
| `var-symbol` | `(symbol (str (ns-name (:ns (meta v)))) (str (:name (meta v))))` |
| `attempt` / `error-data` | `try` / `(catch :default e …)`; `error-data` = `{:class (str (type e)) :message (ex-message e) :data (ex-data e)}` |
| `driving` | on the mesh: `{:block :thread :parallel true :closures true :wake :futex :spawn-ms 0 :max-threads (th/mesh-pool-size) :runtime :vs}` (each resident Eve thread occupies one worker, so the pool size is the honest cap); off the mesh: `{:parallel false :max-threads 0 …}` with `start-threads!` refusing `:no-threads` |
| `eval-form` | **probe pending** — whether the shipped compiler-in-program evaluates a conveyed form. Declared only as measured; if it cannot, the fn refuses `:eval` loudly (the contract's own shape) |
| `:requires` opt | ignored, as the JVM adapter effectively does: every mesh instance runs the same program image; the entry's namespace is loaded by the program, not per-thread |

The futex window: at namespace load the adapter `(mmap/reserve! …)`s one window; word 0 is the address counter,
and each mailbox carves an 8-byte slot (futex word + pad) by index. The window's address is a top-level def —
defined on main during `_start`, before any spawn, and carried to workers in the adopted globals image (the same
pattern as thread.vs's pool-state atom).

### Cross-runtime leg — honest scoping

"Receive thread calls from other runtimes" at the wire level rides `eve.thread`'s own net driver over `eve.net`,
which needs `eve.thread` + `eve.atom` to compile and run on the VS seat. As of this dispatch the seat **cannot
compile `eve.atom`'s graph**: the journals remainders lane (merged to main `4f0be417b9`) banked the VS-seat
reqatom compile dying at form 24209, `Unable to resolve symbol: double?` — a sibling lane (`claude/vs-seat-double-wall`)
is on that wall now. So: **same-seat, multi-instance proof (main ↔ workers, worker ↔ worker, grandchild) is this
lane's provable bar; the cross-runtime wire leg is named unproven with that reason unless the wall falls first.**
Within the seat, send AND receive are both exercised by the conformance suite itself (a worker's replies are
received by main; main's sends are received by workers; the grandchild's starter is a worker).

## 3. OPEN PROBES (before the design is final — REPL-first, seconds not scripts)

- P1. Cross-namespace mesh hooks: a program whose own namespace calls `th/mesh-active?` / `th/mesh-spawn` /
  `th/worker-id` — transform + mesh run: hooks live on workers (not inlined to placeholders).
- P2. `(resolve 'ns/fn)` evaluated on a worker (inside a spawned closure) returns the var; calling it works.
  Var metadata shape (`:ns`, `:name`) in VS.
- P3. `ex-info` / `ex-data` round-trip in a `.vs` program; `(throw (ex-info …))` caught by `(catch :default …)`.
- P4. `get-thread-bindings` / `with-bindings*` across a `mesh-spawn` (frame row semantics).
- P5. `mmap/reserve!` + `wait-i32` / `notify!` across instances: a worker blocks in receive!, main's send wakes it.
- P6. `eval-form`: does the compiled program carry an evaluator (`eval` in core) that runs a conveyed form?

## 4. PROOF PLAN

1. The conformance suite `eve.thread-host-test` on the `:vs` seat in BOTH seat modes:
   - serial: add `eve.thread-host-test` to `test/eve/runner/vs_main.vs` (the `bb test:vs` runner) — exercises the
     no-threads rows (contract answers, self-send, refusals) plus everything not needing a second thread.
   - mesh: a new runner `test/eve/runner/vs_thread_main.vs` + a new bb task `test:vs-thread` in `eve/bb.edn`:
     compile with the installed `vs`, `transform.py`, run under a mesh host with ≥ 5 workers (the suite's peak is
     4 resident threads + main), expect `N passed, 0 failed`. The runner prints `driving` and the task's check
     requires the mesh arm to show `:parallel true` — a run that silently fell back to serial semantics does not
     count as a mesh pass.
2. Round trips both directions are inside the suite (main→worker echo, worker→main replies, worker→worker
   `:ping`, grandchild). The run logs are the evidence; counts quoted from them in the report.
3. Cross-runtime leg: unproven, named above with its blocker, unless the `double?` wall falls first.

Wiring in scope: `eve/hosts.edn` `:vs` row `adapters/thread/none/src` → `adapters/thread/vs/src` (the none stub
stays in the tree); `eve/adapters/README.md` thread row; HOST-CONTRACT §11.2 seat table gains a `thread/vs` row.
Caution: the hosts.edn change touches every `:vs` compile (`test:vs`, `test:vs-http`, `test:vs-serve`, the
journals reqatom compile) — the adapter must compile cleanly in all of them; it requires `vs.thread`, which pulls
the mesh hooks into any program that loads `eve.thread-host`.

Venue: server `~/eve-thread-vs`; the residence pair landing (sibling lane) holds heavy-leg priority — compiles
here are seconds-to-minutes; the mesh conformance run is light. Contention labeled where it touches a number.

## 5. LOG

- Oct 28 ~21:40 EDT — branch + worktree created on the server at main `4f0be417b9` (ls-remote verified). Contract
  (§11), none stub, JVM + Node adapters, conformance suite, `eve.thread`, `vs.thread`, mesh design/hosts,
  `program_runtime.vs` mmap door, existing `.vs` adapters — all read firsthand. STATUS written (this file).
  Probes P1–P6 next.

## 6. PROBE RESULTS (Oct 28 ~22:00 EDT, server, logs in .work/thread-probes/)

All probes run: serial (plain wasmtime, as bb test:vs runs) and on the mesh (transform.py + the wasmtime mesh host
vs/term/hosts/wasmtime/vs-mesh; native wabt 1.0.39 fetched to .work/wabt-native — the JS wabt build aborts
assembling a full program WAT, and the server Node 24.4.1 needs --experimental-wasm-exnref for mesh.mjs, so the
wasmtime host is the mesh arm here).

- P1 ✓ mesh hooks from another namespace: spawn/await/result/worker-id/pool-size all live on the mesh.
  **mesh-active? is value-unsafe**: transform.py maps it (and prog-h-mesh-init-active?) to the RAW primitives
  /, which answer a bare i32 where the VS calling convention expects a boxed
  value — branch-only use works (1 is truthy), but printing/storing the answer traps in the print machinery
  (backtrace through program/scat). Every other hook maps to a boxed _b variant. The adapter normalizes:
  (if (th/mesh-active?) true false). FOUND DEFECT, transform-side; named in the report for the coordinator.
  Also: mesh-await answers the appended primitives status convention (3 = done, 4 = thrown), not vs.thread's
  VF_POLL table — the adapter never awaits (fire-and-forget spawn + mailboxes), so it does not depend on this.
- P2 ✓ (resolve sym) works on main and on a worker; var meta {:ns #object[Namespace ..] :name sym}.
- P3 ✓ ex-info/ex-data/ex-message on main and workers.
- P4 ✓ get-thread-bindings + with-bindings* across a spawn: frame value re-establishes :bound on the worker;
  raw read on the worker sees the root; main unaffected.
- P5 ✓ the raw mmap door across instances: worker blocked in (mmap/wait-i32 base 0 -1), main stored 41 and
  notify!\d — worker woke (wait answered 0) and read 41. Door vocabulary is ONLY the literal mmap/ prefix
  (prog:mmap/*): reserve!, load-i32, store-i32!, add-i32!, cas-i32!, wait-i32, notify!. Two traps for the
  unwary, both hit and diagnosed: (a) calling these through a vs.mmap namespace alias compiles the call to a
  RUN-TRIP stub (vs.mmap the namespace does not define them); (b) there are no atomic-load-i32/atomic-store-i32!
  names — load-i32/store-i32! ARE the seq-cst atomic forms.
- P6 ✗ eval in a compiled program: "eval needs the REPL: this artifact was compiled without it". The adapter
  declares :eval false and eval-form refuses :eval — the contract's own shape, and the suite tests the refusal.

Design confirmed as written in §2. Writing the adapter now.
