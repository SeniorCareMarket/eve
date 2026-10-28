> **Historical record** — the network-atom lane's Phase 1 report (the simulator runs the real coordinator), 2026-10-16. It describes eve as it stood then, not eve 0.9. Its acceptance rows are VS and broker commands (atom.broker work).
> Current authority: `doc/network-atoms/README.md` (the user guide), `WIRE.md`, `ADAPTERS.md`.

# Phase 1 report — the sim runs the real coordinator

2026-10-16, ET. Branch `claude/eve-network-atoms-impl`. Plan: `eve/doc/network-atoms/IMPL-PLAN.md` §3 (units 1.1–1.5),
amendments §9 A5–A7. Every claim is tagged [ran] (this box, this commit) or [read].

## Acceptance [ran]

| unit | command | result |
|---|---|---|
| 1.1 | `bash test/broker/run coordinator_wire_test` | `PASS coordinator_wire_test — 107 passed, 0 failed` |
| 1.2 + 1.3 | `bash test/broker/run presign_test` | `PASS presign_test — 35 passed, 0 failed` |
| 1.4 | `cd vs && python3 test-hosts/workerd/broker_sim_test.py` | `HOST-TEST: workerd checks=29 pass=29 fail=0` (A1, A2, A6, A7, A8, A9, B-creds; the real client on 7 rows) |
| the battery | `bash test/broker/run` | `BROKER-TEST: files=23 pass=23 fail=0 walled=0` (21 + coordinator_wire_test + presign_test) |
| the dialect | `cd vs && bin/vs test net_wire_dialect_test` | unchanged from Phase 0 (`files=1 pass=1 fail=0`) |

## What landed

* **1.1 the Coordinator on Ring + the host's `sql` family** — `services/atom-broker/src/broker/coordinator/`: `db`
  (one statement per host call, rows by column name, a stand-in hook), `schema` (production's tables, minus the blob
  cache), `atoms` (create / deref / cas-root / delete answering production's maps field for field: `old_key` /
  `new_key`, 409 `current_key` + the stored blobs, 403 `{status denied reason detail}`, create answers the epoch,
  cas-root auto-creates, retired keys queued at the new epoch, `graft_sha` kept), `leases` (release; renew 200 /
  404, TTL 60), `spec` (the X-Eve-Domain-Spec header decoded — a base64 decoder over byte strings — and production's
  shallow write check), `watch` (the `root-changed` push built field for field; `/watch` 400 without an atom id, 501
  until the ws family), `gc` (production's phases over SQL: expire, tombstone below the lowest lease, delete past the
  grace period, confirm / retry, the day's metrics; status, metrics, audit), `core` (the DO's router: statuses,
  `_server_timing`, X-Domain-Id stored, 400 for a non-JSON body, 413 past 16 KiB), `app` (the handler the wrapper
  serves). `broker.blobs.delete`: one presigned DELETE per key through the host's fetch. The test composes the real
  handlers through `vs.cf.worker/make-handler` (what the host sends) over `test/broker/sql_standin.vs`, an interpreter
  of exactly the statements the Coordinator writes.
* **1.2 the Worker role** — `broker.middleware/wrap-auth`: production's env-key path + the `resolver` hook;
  `broker.auth.resolve`: the D1 / JWT / IAM path, composed by the tests; `broker.routes.data` (the mountable table) +
  `broker.routes`; `broker.handlers.data-plane` forwards with X-Domain-Id / X-Eve-Domain-Spec, answers 400 for a
  non-JSON body, relays `/watch`, adds `/renew-lease`; `broker.worker` (the handler) + `broker.main` (the two roles).
  The D1 probe: A6 in the plan (miniflare's shim needs workerd-internal modules; Outcome B).
* **1.3 B-creds on the broker** — `broker.blobs.proxy`: `/presign` `{keys mode ttl}` → `{urls {hex url} ttl}`, keys
  bare `<sha>`, ≤ 100, TTL ≤ 3600; `/credentials` → `{endpoint bucket region expires_in}` — no key id, no secret;
  `presign_test` proves no response body carries the secret. `broker.blobs.presign` (a second SigV4 over namespaces
  that do not exist) and the small-blob proxy routes are deleted.
* **1.4 the stack driver** — `vs/test-hosts/workerd/broker_sim_test.py` builds `broker.main` with
  `build-cf-worker --do-class Coordinator --lib-root services/atom-broker/src`, serves it in workerd (SQLite-backed
  object on disk, outbound to `blobstore.py`, the bindings as text), drives it over HTTP and through the real client
  (`vs/test-hosts/progs/net_client.vs`, `vs.net.coordinator` under `vs-term --bridge`, one op per run). `blobstore.py`
  learned HEAD and DELETE.
* **1.5** — the spike's guests, host, build, demo and tool deleted; rows in `vs/.vs-retired-surfaces`; `spike/README.md`
  "Run it" is the driver's command.
* Library: `vs.cf.worker/do-request`, `fetch-do`, `fetch-do-ring` take `:headers`.

## Deviations (amendments)

* A5: `->` over `str/replace` trips; a unit's requires resolve for its first ns only (`unit_libs.vs`); a require of a
  namespace with no source is a compile error.
* A6: the Worker's auth is the env-key path with the account-backed resolver behind a hook; the routes split; one
  store per deployment from `TIGRIS_*`; `renew-lease` on the Worker.
* A7: no blob cache / `atom_blob` / `branch_blobs`; `graft_sha` stored and echoed; GC per-key DELETEs, a cycle per
  `/gc-trigger` (no periodic alarm yet — Phase 5's host probes); 16 KiB bound kept.

## Measured costs [ran]

* A composed broker test compiles in about two minutes on this box; the 23-file battery is a background run.
* The sim: the two `vs compile`s (main.vs, net_client.vs) are cached by content under `~/.cache/vs-hosts`; a run
  after the first finishes inside the driver's 180 s per-call bound.

## Not done in this phase

* The periodic GC cycle (production's 60 s alarm) — named in A7, owned by Phase 5's host probes (the DO program must
  wait and serve at once).
* The admin routes are not mounted by the deployable Worker (their namespaces name libraries the tree lacks; A6).
