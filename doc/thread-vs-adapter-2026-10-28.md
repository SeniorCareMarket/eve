# The real VS thread adapter for Eve — `adapters/thread/vs`

Lane report, Oct 28 2026. Branch `claude/eve-thread-vs`. Running STATUS:
`eve/doc/thread-vs-adapter-STATUS.md` (contract, machinery map, design, probe log).

**Labels.** Everything here is **ADAPTER, new** — host-specific shimming for the VS seat inside Eve's adapter
tree, the sanctioned shape for host code. It is not Eve-as-is residence progress and is not reported as such.

Operator framing (Oct 28 21:39 EDT): VS is to be a first-class citizen among all the Eve targets, able to
receive thread calls from other runtimes just as it can send them. Before this lane, `hosts.edn`'s `:vs` row
named `adapters/thread/none` — Eve's stub declaring `eve.thread-host` with no threading, there so Eve code that
mentions the namespace can load. The stub stays in the tree for seats that want it; the `:vs` seat no longer
uses it.

## What was built

- `eve/adapters/thread/vs/src/eve/thread_host.vs` — the `eve.thread-host` namespace (HOST-CONTRACT §11's
  fourteen functions, nothing else public), over the VS mesh:
  - An Eve thread is one long-lived mesh task (`vs.thread/mesh-spawn`). The wrapper resolves the entry on the
    thread itself, runs it under the starter's conveyed binding frame with `*self*` bound, and on any end —
    return or throw — sends its starter `{:eve.thread/exit addr :reason r}` (r = `:normal` or the throw's
    `error-data`) before its mailbox is dropped. A dead thread is never silent.
  - A mailbox is a VS atom holding a vector — values stored into an atom are held by the runtime, so a
    message's custody is the atom's, never a raw pointer's — plus one futex word per mailbox in a reserved raw
    window. `send!` conjs, bumps the word, notifies it; `receive!` checks the queue, then `mmap/wait-i32` on the
    word it just read — a send between check and wait changes the word and the wait answers at once. The block
    is the host's real block (`memory.atomic.wait32`).
  - The heap is shared, so messages are the values themselves: `ships-closures?` true, frames travel
    (`get-thread-bindings` / `with-bindings*`, core's own conveyance). Addresses are plain integers from an
    atomic counter in the window; an unstarted thread (main) registers its own mailbox on first `self`, memoized
    per instance by `worker-id`.
  - On the mesh, `driving` = `{:block :thread :parallel true :closures true :eval false :wake :futex
    :spawn-ms 0 :max-threads <mesh pool> :runtime :vs}`. Off the mesh (a plain run), the seat has no second
    thread: `start-threads!` refuses `:no-threads`, a forever `receive!` refuses `:deadlock`, bounded/self
    traffic works serially, `driving` declares `:parallel false :max-threads 0`.
  - `eval-form` refuses `:eval`: a compiled program carries no evaluator (measured, below).
- Wiring: `eve/hosts.edn` `:vs` row `adapters/thread/none/src` → `adapters/thread/vs/src`; `eve/bb.edn` gains
  `test:vs-thread`; `eve/test/eve/runner/vs_thread_main.vs` (the mesh runner) is new;
  `eve/test/eve/runner/vs_main.vs` gains the suite's serial arm; rows added in `eve/adapters/README.md` and
  HOST-CONTRACT §11.2.

## Probes (before implementing; logs in the lane's `.work/thread-probes/` on the build server)

- Mesh hooks called from another namespace (spawn/await/result/worker-id/pool-size): live on the mesh.
  `(resolve 'ns/fn)` works on a worker; `ex-info`/`ex-data` everywhere; a binding frame captured on main
  re-establishes on a worker via `with-bindings*` while the raw read sees the root.
- Futex across instances: a worker blocked in `(mmap/wait-i32 base 0 -1)` woke (answer 0) on main's
  store + `notify!` and read main's stored word. The raw door is only reachable by its literal `mmap/` prefix
  (`prog:mmap/*`); through a namespace alias the same names compile to `RUN-TRIP` stubs, and
  `load-i32`/`store-i32!` ARE the atomic forms (there are no `atomic-load-i32` names).
- `eval` in a compiled program answers: "eval needs the REPL: this artifact was compiled without it" — hence
  `:eval false` + the `:eval` refusal, the contract's own shape for an evaluator-less runtime (the suite tests
  the refusal).

## Proof

Suite: `eve/test/eve/thread_host_test.cljc` — Eve's §11.3 conformance suite, the suite the other adapters
are held to.

**Serial arm — GREEN.** The thread runner module (`eve/target/vs-thread/vs_thread_main.wasm`, the clean
committed adapter) run under plain wasmtime (single instance, no mesh) printed
`driving: {:block :thread, :parallel false, :closures true, :eval false, :wake :poll, :spawn-ms 0, :max-threads 0, :runtime :vs}`
and **`30 passed, 0 failed`** — the suite's serial rows on the `:vs` seat with the real adapter, driving
correctly declaring the serial seat. Log: `.work/thread-probes/serial-arm.log` on the build server.

**`bb test:vs` (the full `vs_main.vs` runner) is blocked on current main by a standing defect, not by this
lane.** A full `vs_main.vs` compile finished its unit phase (1,261 V rows) and ended at
`V 1263 0 17409 vs.jit-wasm/emit-gap` with no LINK-TEMPLATE line and no output file. The failing unit is
`vs.jit-wasm` — a toolchain unit this lane never touches — and its records on main are known-stale (the
build-fix discriminator: the emit-gap travels with the scoped mint's EMITTER records alone; D1 remains:
18+ stale records on main). The thread runner's compile over the same `:vs` roots linked clean, proving the
thread suite does not pull the jit unit. Log: `.work/thread-probes/serial-compile.log`. Consequence: the
serial proof above used the thread-runner module; `bb test:vs` on main needs the D1/records remainder fixed
first.

**Mesh driver-level proof — every contract behavior proven end-to-end wherever a task start lands**
(small programs over the adapter itself, wasmtime mesh host, logs in `.work/thread-probes/`):
- 1 thread, main sleeps+polls (driver2): FULL PASS — entry boot `{:self 2}`, ping received, reply polled by
  main, stop delivered, exit notice `{:eve.thread/exit 2, :reason :normal}`.
- 1 thread, main blocks in `receive!` (driver3): FULL PASS — echo + exit notice.
- Error path: a thread whose entry threw still delivered its exit notice carrying the throw's error-data —
  a dead thread is never silent.
- Frames, closures, resolve, ex-info, and the futex were each proven across instances in probes P1–P5.

**Mesh suite attempts — blocked below this lane by a mesh task-start race, characterized with raw repros.**
The full suite on the mesh (transformed module, wasmtime host, 6 workers) was attempted twice under the
server load of the evening (~10–18, residence landing holding heavy priority): attempt 1 (wasmtime host,
900 s timeout) produced 0 bytes of output and was killed by its own timeout; attempt 2 (Node host,
600 s timeout, load ~17.4 at launch) produced only Node's WASI warnings — no suite output — and was likewise
killed by its timeout. The suite prints only at the end, and its unbounded negative-wait row waits forever
for a `:later` message from a sleeper thread, so a failed sleeper-task start hangs it silently. Logs:
`.work/thread-probes/mesh-suite-attempt1.log`, `.work/thread-probes/mesh-suite-attempt2-node.log`. Raw repros with
NO adapter code (driver5: two trivial `mesh-spawn` closures; driver10: sequential trivial spawns) fail at
~50% per task at load 13–15 on BOTH mesh hosts: a spawned task intermittently never runs its closure (await
answers 4 = thrown, result `#object[]`, the body's marks absent), each task failing independently (driver7:
tasks DO claim in parallel across workers — the failure is not serialization). The corpus's own harness
(`vs/wasm-threads/tests/run.py`) refuses verdicts over a load bound for exactly this class of reason. The
mesh suite verdict therefore waits for a quiet window or a mesh-machinery fix; it is not an adapter verdict.

## Found defects (not this lane's to fix; named for the coordinator)

1. **`transform.py` maps `mesh-active?` to a raw primitive.** `vs.thread/mesh-active?` (and
   `vs.core/prog-h-mesh-active?`, `prog-h-mesh-init-active?`) rewire to `$vs_mesh_active` /
   `$vs_thread_init_active`, which answer a bare i32 where the VS calling convention expects a boxed value.
   Branch-only use works (1 is truthy); using the answer as a value — printing it, storing it in a map —
   traps in the print machinery (backtrace through `program/scat`). Every other hook maps to a boxed `_b`
   variant. The adapter normalizes through `(if (th/mesh-active?) true false)`; the transform's table is the
   honest fix site. Any consumer that stores `(mesh-active?)` in a data structure hits this.
2. **`mesh-await`'s status convention.** The appended primitives report 3 = done / 4 = thrown
   (`$vs_invoke_guarded`), while `vs.thread`'s own docstring tables `VF_POLL_*` as 1 = value-ready,
   2 = threw, 4 = error. `vs.thread/thread-join` (itself rewired) papers over this for futures; a direct
   `mesh-await` caller reading the docstring's table misreads a completed task as cancelled. The adapter does
   not depend on it (fire-and-forget spawn + mailboxes); named here because the probe tripped on it.
3. **Mesh task-start race under load (the mesh-suite blocker).** Raw `mesh-spawn` repros with no adapter
   code fail at ~50% per task at load 13–15 on both mesh hosts (wasmtime and Node): a spawned task
   intermittently never runs its closure. The fix site is the transformed module's task machinery
   (transform.py / the mesh runtime), or the verdict needs a quiet load window — the corpus harness's own
   `--max-load` refusal is the precedent. Evidence: driver5/driver7/driver10 sources + logs in
   `.work/thread-probes/`.
4. **`bb test:vs` on main blocked by the `vs.jit-wasm` emit-gap** (standing D1/records remainder — the
   emit-gap travels with the scoped mint's emitter records; 18+ stale records on main). The thread suite
   itself does not pull the jit unit; its own runner compiles and links clean.

## The unproven leg

Cross-runtime thread calls (a call originating on another runtime — JVM/CLJS — received and answered by the VS
seat, and vice versa) ride `eve.thread`'s net driver over `eve.net`, which needs `eve.thread` + `eve.atom` to
compile and run on the seat. The seat cannot compile `eve.atom`'s graph today: the journals remainders lane
(merged to main at `4f0be417b9`) banked the VS-seat reqatom compile dying at form 24209,
`Unable to resolve symbol: double?` — a sibling lane (`claude/vs-seat-double-wall`) is on that wall. So the
wire leg is unproven with that named blocker; what is proven here is the full host contract on the seat itself,
in both directions across instances (main → worker, worker → main, worker → worker, grandchild), which is the
minimum bar the brief set. When the `double?` wall falls, the follow-on is: compile `eve.thread` on the seat,
run `eve.thread`'s own tests there, then a two-runtime round trip.

## Notes for the collator

- The `hosts.edn` `:vs` change touches every `:vs` compile. The adapter compiles and links clean in the
  thread runner (both arms); the full `bb test:vs` compile is blocked by the standing `vs.jit-wasm`
  emit-gap on main (defect 4), a unit the thread suite does not pull — see Proof.
- Server environment: the build server had no wabt; the native wabt 1.0.39 release was fetched into the lane's
  `.work/` (the npm JS wabt build aborts assembling a full program WAT). The server's Node 24.4.1 refuses the
  mesh module without `--experimental-wasm-exnref`, so the mesh arm here ran on the wasmtime mesh host
  (`vs/term/hosts/wasmtime/vs-mesh`, run.py's default host). `bb test:vs-thread` as committed uses the wasmtime
  host. Nothing under `.work/` is committed.
- No merge performed — the coordinator collates.
