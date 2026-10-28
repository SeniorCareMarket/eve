> **Historical record** — the network-atom lane's Phase 4 report (the EVE network domain, JVM then bb), 2026-10-17. It describes eve as it stood then, not eve 0.9. Its binding ruling — synchronous deref and swap on every host — is carried by the user guide.
> Current authority: `doc/network-atoms/README.md` (the user guide), `WIRE.md`, `ADAPTERS.md`.

# Phase 4 report — the EVE network domain, JVM then bb (in progress)

2026-10-17, ET. Branch `claude/eve-network-atoms-impl`. Plan: `eve/doc/network-atoms/IMPL-PLAN.md` §6 (units 4.1–4.7,
§6.0 the shape of `eve.net`, §9 A11). Every claim is tagged [ran] (this box, this commit) or [read].

The rule every unit here is held to (main, 2026-10-16: eve/CLAUDE.md "eve is portable"): the shared code names no host —
no reader conditional keyed by a host, no host interop, no host predicate — and `eve/scripts/check-host-leak` holds
the count. `eve.net` adds none: its numbers (SHA-256, XXH3, IEEE 754 doubles, two's-complement i64), its text (UTF-8)
and its JSON are arithmetic in `.cljc`, and a step that can fail answers a value, so nothing in `eve.net` catches.

## Acceptance [ran]

| unit | command | result |
|---|---|---|
| 4.1 XXH3-128 | `bb -cp src:test -m eve.runner.bb-main test eve.net.xxh3-test`; the JVM: `clojure -Sdeps '{:paths ["src" "test"]}'` with `clojure.test/run-tests` | bb 4 tests, 716 assertions, 0 failures (e15c533); the JVM with json and graft: 13 tests, 768 assertions, 0 failures |
| 4.2 the codec (G1) | the same two runners on `eve.net.codec-fixture-test` | bb: 5 tests, 114 assertions, 0 failures; the JVM (with `eve.net.json-test`): 9 tests, 136 assertions, 0 failures |
| 4.3 wire + blobs | `bb … test eve.net.wire-test` | bb: 4 tests, 26 assertions, 0 failures (the JVM run follows the chain) |
| 4.4 the atom | `bb … test eve.net.remote-atom-test` | bb: 7 tests (T-4.8 encrypted blocks among them), 0 failures; all eve.net tests together: 29 tests, 963 assertions, 0 failures before G4 (the JVM run follows the chain) |
| 4.4 G4 | `bb … test eve.net.crypto-test` | bb: 3 tests, 15 assertions, 0 failures; with wire and the atom: 14 tests, 79 assertions, 0 failures |
| the host rule | `python3 eve/scripts/check-host-leak` | OK: conditionals 510, predicates 36, host-files 14 (the baseline: `eve.net` adds nothing) |

`clojure -M:jvm-test` could not fetch an artifact through this box's proxy; the JVM runs name the same namespaces with
plain source paths.

## 4.2 — `eve/src/eve/net/codec.cljc`

A value ⇄ its VSv2 blocks in the kernel's own layouts (§0.6): the map / set root (`0xED`, a set's key XOR 0xEE…), the
bitmap node (the 5-bit slice of the key's XXH3-128 at each shift, `hamt.vs` `mask_hash`: a slice one key has is a
data entry, a slice two share a child node — the canonical shape whatever the insertion order), the vector (the tail
node, full 32-element leaves, a trie of occupied slots only; shift 0 for a one-leaf trie), the list (a node per element,
its 32-byte band inline), the value blocks (a string `STRING_LEAF`, a keyword `kwlf`, a symbol a leaf led by 0x01, a
char `chlf`), the inline scalars (nil, true, false, an INT32, a program DOUBLE, a program LONG: the user model, §9
A9 (j)). Map keys: `[0x01][text]` for strings, keywords (`:` and the name) and symbols, `[0x00][the f64]` for numbers.

`encodes-every-row-byte-exact`: every one of the corpus's 21 imaged rows (`vs/test/vs/fixtures/vsv2-blocks.edn`, cut by
the kernel) re-encodes to the same root key, the same set of keys and every image byte for byte; `decodes-every-row`:
each decodes value-exact. Refused with `:type :unencodable`: the corpus's `list-mixed` (a list holding a string and a
vector: the kernel's list node carries its value inline, and the kernel refused it too), a string the kernel would
read as a keyword (a leading `:`), a vector as a map key. Round trips beyond the corpus: longs past int32 and past
2^53, -0.0, the smallest subnormal, a UTF-8 string past the BMP, a char, a namespaced symbol and keyword, a
2,000-element vector, a mixed set.

Named limits: a collision node (two keys sharing all 128 hash bits) throws rather than encodes; ClojureScript prints
-0.0 as "0", so a ClojureScript host writes it as 0.0 (which `=` treats as equal) — the CLJS arm is Phase 5's.

## 4.3 — the wire and the blobs, tested

`eve/test/eve/net/stand_in.cljc`: an in-process production broker (the routes and shapes of
`vs/test/vs/net_wire_fixtures.vs` `production-broker`: create, deref with a fresh lease, cas-root 200 / 409 with the
winner's key, epoch, blob and graft / 403, release-reader, no renew-lease route, presign 100 keys a call) and an object
store behind its presigned URLs. `wire_test.cljc` runs `eve.net.wire` and `eve.net.blobs` through `eve.net.run` on a
synchronous platform: every route's answer parsed (a 409 an answer, never a throw; a 401 where an answer is required
throws naming the status); 250 keys presigned in three calls; a value encoded by the codec, uploaded with the HEAD
dedup (a second upload PUTs nothing), every store request keyed by a bare 64-hex SHA, fetched by key with the address
check, decoded equal; a blob with one flipped byte throws naming its key.

## 4.4 — `eve/src/eve/net/atom.cljc`

The network atom in the protocol of `vs/src/vs/net/remote_atom.vs`: the value a map; its root blob a VSNP snapshot (a
collection value the reference by content to its codec key — the test pins it), the entries ordered by their key's
XXH3-128 so every host writes the same blob; deref fetches the tree level by level (`eve.net.codec/child-keys`: one
presign batch and the GETs per level) and keeps fetched blocks; swap! uploads the new blocks, the snapshot and a GRFT
V3 graft, then CASes with the retired keys; 409 backs off with the PRNG's draw and retries (at most 50); 403 throws
once. `remote_atom_test.cljc`: T-4.4 (every PUT keyed by the SHA of its bytes, each once), T-4.5 (a nested value's
blocks stored; a fresh handle derefs it equal), the retired set (the old root and more), T-4.6 (one 409, one backoff
in 5–15 ms, the retry wins), T-4.7 (a 403 throws `:policy-denied` once, nothing written, the handle usable), the bound
(50 attempts, every backoff ≤ 3 s), the lease (held, released by the next deref, `:lost` on production's Worker, none
left after a swap).

G4 — `eve/src/eve/net/crypto.cljc`: AES-256 (the S-box by the generator walk), CTR, HMAC-SHA256, HKDF-SHA256, the
per-block key and IV, encrypt / decrypt with the address check: the contract of `vs.net.blob-crypto`.
`crypto_test.cljc`: FIPS-197 C.3, SP 800-38A F.5.5, RFC 4231 TC1, RFC 5869 A.1 (the vectors `blob_enc_test.vs` holds
VS to), and the per-block key, IV and 200-byte ciphertext of `blob_enc_test`'s own ctx computed by an independent
implementation (Python's `cryptography` HKDF and AES-CTR) — so either side decrypts the other's blobs. T-4.8: with the
domain's `:gak` in the config, `eve.net.blobs` stores every block encrypted under HKDF(gak, nonce = the first 16 bytes
of its key) and decrypts before the address check; the atom test reads it back with the gak and fails without it. It
is off unless the config carries a gak: the VS client (`vs.net.remote-atom`) does not encrypt, and a mixed atom must
be readable by both.

Named limits: a plain Clojure value records no reads, so a winner's graft never spares the backoff (P7's read Bloom is
the VS kernel's); the atom is a plain map of plain values, read whole — the lazy local view over eve's heap domain
(`eve.net.slab`, fetch-on-miss at the boundary) is the unit's remaining item.

## eve.net as eve's adapters (2026-10-17)

`claude/quirky-euler-ruv0pr` (headed for main, merged here) organizes eve by feature: an interface namespace per
feature, an implementation per host capability under `eve/adapters/<feature>/<impl>`, a host the list of its adapter
roots (`eve/hosts.edn`). eve.net joins it with two features:

| feature | interface | implementations |
|---|---|---|
| http | `eve.http/platform` → `{:request f}` (eve.net.run's request contract: synchronous on every host) | `adapters/http/java` (java.net.http): the JVM, babashka; `adapters/http/node` (fetch on a worker thread, the caller on Atomics.wait): ClojureScript on Node, nbb; `adapters/http/python` (urllib): basilisp |
| crypto | `eve.crypto`: `sha256`, `hmac-sha256`, `aes256-ctr` | `adapters/crypto/java` (MessageDigest, Mac, Cipher): the JVM, babashka; `adapters/crypto/portable` (`eve.net.sha256`, `eve.net.aes`): every other host |

`eve.net.platform` composes `eve.http` with `eve.process` (main's process feature: the clock and a blocking sleep);
`eve.net.atom/remote-atom` takes it by default. The e2e client names no HTTP API any more — it is the adapter's.
AES moved out of `eve.net.crypto` into `eve.net.aes` (the portable adapter's cipher); `eve.net.crypto` keeps HKDF and
the per-block key over `eve.crypto`; eve.net's digests (codec keys, the address check, the root key) are `eve.crypto`'s.

Receipts [ran]: babashka with its `hosts.edn` roots (the Java adapters): every eve.net test, 34 tests, 1,018
assertions, 0 failures — `the-host-adapter-against-the-portable-code` (SHA-256, HMAC with long and short keys,
AES-CTR at 0..1000 bytes) among them; with `adapters/crypto/portable` in place of the Java one: codec, wire, atom and
crypto, 21 tests, 250 assertions, 0 failures. `check-host-leak`: OK at the merged branch's baseline (294 / 0 / 11).

## Synchronous on every host (operator, 2026-10-17)

A refactor that made every remote-atom operation one step, so a Node host could answer deref and swap! with a promise
(`d97a1724`), was reverted on the operator's ruling, verbatim: *"But in CLJS we depend on those blocking semantics to
produce clojure like threading semantics. Deref and swap will not be returning promises on any clojure-like impl. So
don't do that. And our prefetching machinery depends on the sync prefetch."* What it changed:

* `eve.net.run/run` is the synchronous loop only (the `:then` / `:resolve` arm deleted); the platform's `:request`
  answers synchronously on every host, and its docstring says why.
* `adapters/http/node` (`eve.http` for ClojureScript on Node and nbb) blocks the way production's
  `old/eve.atom/src/eve/future_fetch.cljc` did: the fetch runs on a worker thread, the caller parks on
  `Atomics.wait`, the answer returns through `receiveMessageOnPort`. Probe [ran, nbb against a local echo server]: six
  requests in order in 181 ms — a JSON POST with the bearer header, a base64 PUT body, a 256-byte GET answered as
  base64, a 409, a HEAD, and a refused port answered `{:error …}`.
* Node is the mixed run's fourth client (`broker_sim_test.py --mixed`: VS, the JVM, babashka and Node each swap! 20
  times, each derefs 80; Node reads VS's nested value and VS reads Node's). The e2e client is `client.cljc` now.

Running eve.net on Node for the first time found defects the JVM and babashka could not show, fixed at their producer:
`(int c)` of a character is 0 on ClojureScript (its characters are one-character strings), so `eve.net.bytes/utf8`
encoded every character as 0 and `eve.net.json`'s writer escaped every character as `\u0000` — 45 of 925 assertions
failed on nbb (SHA KATs of text, xxh3 of text, every codec key, every wire body). Both now read a string's
UTF-16 units from `eve.encoding/utf16-units`, which the JS encoding adapter gained (the JVM, CLR, Python and BEAM
adapters had it). The two tests that read fixtures with `slurp` read them through the host's file read, as eve's
other shared tests do. One fixture row (`vec-of-kinds`) holds a character: on a host with no character type it reads
as a one-character string and re-encodes as a string leaf, so its byte-exact encode is checked where characters
exist and its decode everywhere.

Receipts [ran], every eve.net test (json, graft, xxh3, codec fixtures, wire, remote atom, crypto):

| host | tests | assertions | failures |
|---|---|---|---|
| nbb 1.6.214 (Node 24) | 34 | 1,016 | 0 |
| babashka | 34 | 1,019 | 0 |
| JVM (Clojure 1.12) | 34 | 1,019 | 0 |

`check-host-leak`: OK at 294 / 0 / 11.

`adapters/http/python` (`eve.http` for basilisp, over urllib) is synchronous by nature. Probe [ran, basilisp 0.5.1
against the same echo server]: the same six requests in order in 72 ms, the 409 answered as a response (urllib raises
it as an HTTPError carrying the response) and the refused port as `{:error "<urlopen error [Errno 111] Connection
refused>"}`.

Open: the http adapters of ClojureCLR, clojerl and jolt — their runtimes are not on this box; until each has one,
eve.net.platform does not load there (eve.net's other namespaces do: they take a platform map).

## The adapter set across the Clojure-likes (2026-10-17)

The operator asked for a full set of network-atom adapters, "so that hosts can provide a minimal number of low level
primitives, plug them into our adapters, and everything will just work", including "all the crypto stuff". The seam
analysis and the per-host matrix are `eve/doc/network-atoms/ADAPTERS.md`. What a host supplies: a synchronous request
(`eve.http`), the clock, a blocking sleep and OS random bytes (`eve.process`), and a string's UTF-16 units and their
inverse (`eve.encoding`). Everything else is eve's portable cljc, and native crypto adapters replace the portable code
for speed.

Runtimes installed here this session [ran]: .NET SDK 8.0.131 with ClojureCLR 1.12.6 (`dotnet tool install -g
Clojure.Main`), Erlang/OTP 25 with rebar3 3.19 and clojerl at `4ad14e5` (built from source), glojure v0.7.22
(`go install`), and jolt's and Chez Scheme 10.4.1's sources (jolt runs on a threaded Chez built from source).

eve.net tests per host [ran] (json, graft, xxh3, codec fixtures, wire, remote atom, crypto):

| host | http adapter | crypto adapter | tests | assertions | failures |
|---|---|---|---|---|---|
| babashka | http/java | crypto/java | 37 | 1,046 | 0 |
| nbb (Node 24) | http/node | crypto/node | 37 | 1,043 | 0 |
| ClojureCLR 1.12.6 | http/clr | crypto/clr | 37 | 1,046 | 0 |
| clojerl 0.9 (OTP 25) | http/beam | crypto/beam | 37 | 1,043 | 0 |
| basilisp 0.5.1 (CPython 3.13) | http/python | crypto/python | 37 | 1,042 + 4 | 0 — the run's 4 failures were the character row's detector (a regex basilisp reads differently), fixed to a substring check; that test's rerun: 6/133/0 |
| glojure v0.7.22 | http/go | portable | 37 | 974 | 1 error — glojure's reduce over a large map's seq, fixed in the codec (the codec test then 6/136/0) |

`request-all` on each host [ran: 16 requests to a 300 ms endpoint]: babashka 312 ms, nbb 449 ms, ClojureCLR 324 ms,
clojerl 310 ms, glojure 308 ms (a future each) — every one concurrent. jolt's adapters (libcurl and libcrypto through
jolt's FFI) are written and wait on a threaded Chez Scheme 10.4.1 build, after the heavy jobs.
