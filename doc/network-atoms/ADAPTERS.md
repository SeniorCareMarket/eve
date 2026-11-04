# The network atom's host seams: what a Clojure-like supplies, and what eve does

> **Status sections updated 2026-10-20 for the 0.9 release prep** (§1, §4.3, §7): glojure is a `hosts.edn` host
> (`http/go`, `region/glojure`, `types/glojure`); an `http` root exists on the JVM, babashka, ClojureScript, nbb,
> basilisp, ClojureCLR, clojerl, glojure and the browser (`http/browser`, synchronous XHR, added later in the release
> prep) — only jolt and ValueScript have none, so those two cannot open a network atom. §2–§5 (the minimal network set, the measured cost of portable crypto, the organization, the
> open defects) are the design rationale and stand. The user guide is `doc/network-atoms/README.md`; the per-host
> adapter set is `hosts.edn` (tabulated in `doc/adopter-tiers.md` §5.1).

2026-10-17, ET. The question: *"We want a full set of adapters for the network atom side of things so that
hosts can provide a minimal number of low level primitives, plug them into our adapters, and everything will just work
on their platform with network atoms. Analyze and find those seams."* and *"That should include support for all the
crypto stuff."* The organization is eve's (`adapters/README.md`): an interface namespace per **feature**, one
implementation per host **capability** under `adapters/<feature>/<impl>`, a host the list of its roots in `hosts.edn`.

Provenance tags: **[ran]** executed on this box this session; **[docs]** the platform's documented API, not run here;
**[unknown]** needs a probe on that runtime.

## 1. Where it stands

What Phase 4 built, and what it did not:

| | done | not done |
|---|---|---|
| protocol | the whole client in portable cljc (`eve.net.*`): codec, snapshot, graft, wire, presign batches, blob fetch with the address check, upload with the HEAD dedup, leases, retry and backoff, HKDF and the per-block key | the watch (Phase 5.1) |
| `http` | `eve.http/platform` → `{:request f}`, synchronous on every host; `http/java` (JVM, bb), `http/node` (Node, nbb: fetch on a worker, `Atomics.wait`), `http/python` (basilisp) [ran, all three] | ClojureCLR, clojerl, jolt, glojure, the browser; concurrency (§4.2) — since built: `http/clr`, `http/beam`, `http/go`, `http/browser`, and `request-all` (§6); still none on jolt and ValueScript (2026-10-21) |
| `crypto` | `eve.crypto`: `sha256`, `hmac-sha256`, `aes256-ctr`; `crypto/java` (JVM, bb) and `crypto/portable` (everyone else), every adapter held to the portable code by `crypto_test.cljc` [ran] | XXH3, random bytes and Ed25519 are not in the feature (§4.1); no native adapter for Node, basilisp, ClojureCLR or clojerl, so they run the portable code — all since built (§6) |
| `process` | `now-ms`, `sleep-ms!` on all five process adapters (java, node, clr, python, beam) [read] | — |
| `encoding` | `utf16-units` on all five encoding adapters (the JS one added this session, after `(int c)` read 0 on ClojureScript) [ran on nbb, bb, JVM] | — |

So the seams were found for the request and three digests, not for the rest of the crypto, not for concurrency, and
not for the hosts this box does not have.

## 2. The minimal set a host must supply

Four things a host cannot borrow from portable code, because each one is the outside world:

| primitive | feature | why no portable default exists |
|---|---|---|
| a synchronous HTTP request | `http` | I/O. Synchronous by design: *"Deref and swap will not be returning promises on any clojure-like impl."* |
| the wall clock, and a blocking sleep | `process` | time (already on every host) |
| random bytes from the operating system | `process` (`random-bytes`, beside the clock) | entropy: a new domain's group key (GAK) and any nonce a client mints. It lives in `process` so that `crypto/portable` stays complete on every host: `eve.crypto/random-bytes` reads it there. The backoff jitter's seed may stay the clock |
| a string's UTF-16 units | `encoding` | a character's code is a host fact: `(int c)` is 0 on ClojureScript (measured, §1) |

Everything else is eve's, in portable cljc, and a host MAY replace it with native code for speed. The KAT suite holds
each replacement to the portable code byte for byte, so a replacement can only make things faster.

## 3. What native code buys (measured)

babashka, 16 KiB, one call each [ran]:

| primitive | portable cljc | `crypto/java` |
|---|---|---|
| SHA-256 | 168 ms | 2.0 ms |
| AES-256-CTR | 194 ms | 3.1 ms |
| Ed25519 verify (200-byte message) | 803 ms | 1.1 ms |
| Ed25519 sign (200-byte message) | 959 ms | 0.9 ms |

The final tree, 2026-10-21 ET (`examples/seam_bench.clj`, median of 5 after 2 warm-ups, the minimal + inbuilt
composition against bb's own row): SHA-256 157.86 /
1.46 ms, AES-256-CTR 186.02 / 3.31, Ed25519 verify 792.94 / 0.73, sign 741.20 / 0.69.

Every stored block is hashed (its key), and with a GAK encrypted, so a host on the portable code pays tens of
milliseconds per kilobyte. Native crypto is not optional for a usable host; the portable code is the reference and the
fallback.

## 4. The proposed organization

### 4.1 One complete `crypto` feature

`eve.crypto` grows to every primitive the network atom and its auth use:

| function | used for | portable default |
|---|---|---|
| `sha256` | block keys, the root key, the address check, HMAC | `eve.net.sha256` (exists) |
| `hmac-sha256` | HKDF (the per-block key) | over `sha256` (exists) |
| `aes256-ctr` | blob encryption (G4) | `eve.net.aes` (exists) |
| `xxh3-128` | the HAMT's key hash in the codec | `eve.net.xxh3` (exists; today called directly, not through the feature) |
| `random-bytes` | GAK generation, nonces | none: `eve.process/random-bytes`, the host's (§2) |
| `ed25519-public-key`, `-sign`, `-verify` | the broker's EdDSA JWTs; federation attestations | `eve.net.ed25519` over `eve.net.sha512` (built, §6) |

Two mechanics change. **Per-primitive fallback**: a native adapter implements what its platform has and delegates the
rest by name to the portable namespaces. For example, CPython's standard library has SHA-256, HMAC and random bytes
but no AES and no Ed25519. Today an adapter must supply all of `eve.crypto` or none of it. **One override
mechanism**: the classpath adapter. The `:sha256` key in `eve.net.blobs`'s config and §6.0's "platform map overrides"
are dropped. The platform map keeps only the effects (`:request`, `:sleep`, `:now-ms`, `:seed`), which tests replace.

### 4.2 Concurrency inside a synchronous call

`eve.net.blobs` sends its presigned GETs, HEADs and PUTs one at a time [read: `blobs.cljc` `fetch-blocks`,
`head-missing`, `put-all`]. Production's reader fetched in parallel (`batch-blob-get` under one `Atomics.wait`, a
32-wide viewport fetch). The `http` feature gains **`request-all`**: a vector of requests in, a vector of answers out,
run concurrently by the host, and still synchronous to the caller, which blocks until every answer is in. Default:
`(mapv request reqs)`. Node: one worker message, `Promise.all`, one `Atomics.wait`. JVM: `sendAsync`, then join.
CPython: a thread pool. The prefetch stays synchronous, as ruled, and gets production's parallelism back.

### 4.3 Per-host adapter set

`✓` exists [ran]; `→` to write, the platform API named [docs] unless tagged; `P` the portable code.

| host | http | sha256 · hmac | aes-ctr | xxh3 | random | ed25519 |
|---|---|---|---|---|---|---|
| JVM | ✓ java.net.http | ✓ MessageDigest, Mac | ✓ Cipher | P | ✓ SecureRandom | ✓ Signature "Ed25519" (the public key P: the JCA derives none from a seed) |
| babashka | ✓ (same adapter) | ✓ | ✓ | P | ✓ SecureRandom | ✓ (same adapter) |
| Node (CLJS, nbb) | ✓ fetch on a worker | ✓ node:crypto createHash / createHmac | ✓ createCipheriv aes-256-ctr | P | ✓ getRandomValues | ✓ crypto.sign / verify |
| browser (a Web Worker) | → synchronous XHR (allowed in workers, `responseType` arraybuffer) | P (WebCrypto is promise-only) | P | P | → getRandomValues (synchronous) | P |
| basilisp (CPython) | ✓ urllib | ✓ hashlib, hmac | ✓ libcrypto through ctypes (as `region/ctypes` loads libatomic), else P | P | ✓ os.urandom | ✓ libcrypto's EVP raw keys through ctypes, else P |
| ClojureCLR (.NET 8) | ✓ HttpClient.Send | ✓ SHA256.HashData, HMACSHA256.HashData | ✓ Aes.EncryptEcb over the counter blocks (.NET has no CTR mode) | P | ✓ RandomNumberGenerator | P (none in the box) [ran] |
| clojerl (OTP 25) | ✓ httpc:request (inets) | ✓ crypto:hash, crypto:mac | ✓ crypto:crypto_one_time aes_256_ctr | P | ✓ crypto:strong_rand_bytes | ✓ crypto:sign / verify eddsa |
| jolt (Chez) | [unknown: java.net.http under jolt; else libcurl through jolt.ffi] | [unknown] | [unknown] | P | [unknown] | [unknown] |
| glojure (Go) | ✓ net/http (a future each for request-all) | P on stock glj (no crypto packages in its map) | P | P | ✓ /dev/urandom | P [ran] |

glojure is a measured level-A host that runs eve's shared `.cljc` as is (PORT-TARGETS.md §3.1). It joined
`hosts.edn` on 2026-10-20  with `region/glojure`, `types/glojure` and `http/go`, run as `glj-eve`
(`impls/glojure/build.sh`); its crypto is `crypto/portable`. Its stock `glj` ships `net/http` but none of
`crypto/*` and no `crypto/rand` [ran: glojure v0.7.22's README and `gen/gljimports`], so on stock glj the crypto is
portable and random bytes come from reading `/dev/urandom` through `os`. eve's own `glj` build
(`impls/glojure/cmd/glj-eve`) can add the crypto packages to its package map.

### 4.3.1 The other Clojure-likes eve is ported to

eve's quirky-euler work reaches four more languages through native ports of the memory layer (`eve/impls/`): glojure
and let-go (Go), jank (C++), phel (PHP). eve.net is shared `.cljc`, so a host gets the network atom only where it
runs eve's shared code. The scorecard's P1 column measures exactly that:

| host | runs eve's shared .cljc (P1) | network atom route |
|---|---|---|
| glojure | as is | a hosts.edn host with `http/go` (net/http) and portable or package-map crypto (above) |
| let-go | as is | the same shape as glojure (Go `net/http`), through its custom `lg`; its atom ops take only its own types until the five-line upstream patch lands |
| squint | as is | Node's adapters, but squint makes keywords strings, so it cannot round-trip other hosts' keyword data; the network atom would inherit that |
| jank | fails | none until jank runs the shared code |
| phel | fails | none until phel runs the shared code |

### 4.4 Bytes

eve.net's portable byte form is a vector of integers. Every native adapter converts at its edge (the JVM's `vec*`).
That is correct and portable, but a large value pays for a boxed integer per byte. Measure a 1 MiB deref before
deciding. The alternative is eve's own byte arrays (`codec/byte-array`, `eve.host`'s array names), behind the same
interfaces.

### 4.5 The watch

Phase 5.1's `root-changed` push needs a `ws` feature: `connect`, `send`, a blocking `next` with a timeout, and
`close`. The JVM's is `HttpClient.newWebSocketBuilder` [docs] and Node's is the built-in `WebSocket` (Node 22+)
answered through the same worker [docs]. Without one, the client polls deref (§7.1).

## 5. Defects found while mapping

* **`vs.crypto` has no Ed25519.** `broker.auth.jwt` calls `crypto/ed25519-sign` (`jwt.vs:29`) and
  `crypto/ed25519-verify` (`:43`), and VS's `src/vs/crypto.vs` defines neither [read]. The kernel carries an Ed25519
  module (VS's `src/vs/kernel/wasm/ed25519.vs`, `sha512.vs`), so `vs.crypto` needs two rows over it, which means a
  `program_runtime.vs` row pair and a builder landing. Until then the broker's JWT signing calls an unresolved name.
* **The broker's JWKS carries no key** [read]. `broker.auth.jwt/jwks-response` (`jwt.vs:153`) binds
  `ED25519_PUBLIC_KEY` and never writes it: the one JWK has `kty`, `crv`, `use`, `kid` and `alg` but no `x`, so a client
  cannot learn the key it should verify tokens with from `/.well-known/jwks.json`. A client that signs in needs
  `x` (the raw 32-byte key, base64url). Its fix goes with `vs.crypto`'s Ed25519 rows (the first item): nothing fixes
  the secret's encoding while the function it is passed to does not exist. The wire needs nothing new for a token: it already sends `Authorization: Bearer
  <credential>`, and the broker reads a bearer that contains `.` as a JWT (`broker.auth.resolve`, `resolve.vs:21`).
* **The platforms disagree about small-order Ed25519 keys** [ran]. Eight public keys have order dividing 8; under the
  cofactorless check each one verifies a crafted signature for one message in eight (the identity key for every
  message). OpenSSL 3.0.13 (this box's libcrypto) and 4.0.2 (Python's `cryptography`) accept all eight; Node 24's
  OpenSSL 3.5.8 refuses all eight; all three accept a mixed-order key ([a]B + T8). A verify whose answer depends on the
  host breaks a replicated system, so `eve.net.ed25519/well-formed?` refuses the eight encodings, and every native
  adapter answers only where `well-formed?` does. The probe and its cases: `test/eve/net/ed25519_test.cljc`
  (`SMALL-ORDER-CASES`, `MIXED-ORDER-CASE`). **Divergence left open:** the kernel's `ed25519.vs` (and so the broker's
  `vs.crypto`) accepts the identity key — `test/aegis/ed25519_test.vs` pins that — so the broker and an eve client
  answer differently for a small-order key. Aligning the kernel is a change to `ed25519.vs` and its test, not done here.
* **`(int c)` on ClojureScript** (fixed this session): see reports/phase-4.md, "Synchronous on every host".
* **Host facts the new hosts exposed** (each fixed in eve.net's shared code, so no adapter carries a workaround):
  - basilisp: `.indexOf` is a JVM and JavaScript string method (now lookup tables; the `net-interop` gate holds it at
    0); a Python string is code points, so text rebuilt through UTF-16 surrogates broke past U+FFFF
    (`eve.encoding/code-points->string`, and Python's `utf16-units` now answers real UTF-16); basilisp's EDN reader
    misreads exponents (`-2.5e1` reads `-2.5`), so JSON numbers parse by their digits.
  - clojerl: no transients, `long`, `parse-long` or `##Inf` in its core (eve.host's transients and `long`; Infinity
    and NaN by arithmetic, only for a double that holds one, since the BEAM's floats have neither); `subs` raises
    `badarg` on non-ASCII text (the JSON reader walks UTF-16 units instead of host strings); and a clojerl bug:
    `reduce` over a stepped range shorter than its step yields nothing — `(reduce conj [] (range 160 161 32))` is
    `[]` there — which broke XXH3 at lengths 160–240 and base64 of one or two bytes (`eve.net.u64/steps`). That one
    is worth reporting upstream to clojerl.
  - glojure: no `clojure.edn` (the two fixture tests read with core `read-string` there); `ex-message` carries
    the stack after the message; and a glojure bug: `reduce` over the seq of a large map (an `ArrayNodeSeq`) has no
    method, while reducing the map itself works — the codec reduces the map.

## 6. Built so far (2026-10-17)

* **The crypto feature, complete on the hosts this box runs.** `eve.net.portable-crypto` is the reference and the
  fallback: SHA-256, HMAC-SHA256, AES-256-CTR, XXH3-128. `eve.crypto` has all five functions on every adapter:
  `crypto/portable`; `crypto/java` (the JVM, babashka); `crypto/node` (node:crypto: Node, nbb); `crypto/python`
  (hashlib, hmac, and AES through libcrypto by ctypes, else portable: basilisp). XXH3 is portable on all of them, since
  no platform's standard library has it. The codec and the atom hash through `eve.crypto/xxh3-128`, and `eve.net.blobs`'s
  `:sha256` config key is gone.
* **Ed25519 in the crypto feature** (2026-10-17). `eve.net.sha512` (FIPS 180-4 over `eve.net.u64` pairs) and
  `eve.net.ed25519` (TweetNaCl's construction, as the kernel's `ed25519.vs`: 16 limbs of radix 2^16, every product sum
  below 2^47, so ClojureScript's doubles compute the same bits as longs; verify cofactorless, with the kernel's
  refusals and the small-order one of §5). `eve.crypto` has `ed25519-public-key`, `ed25519-sign` and `ed25519-verify` on
  every adapter: native on `crypto/java` (JDK 15+ `Signature "Ed25519"`; the public key is portable, since the JCA
  derives none from a seed), `crypto/node` (node:crypto), `crypto/python` (libcrypto's EVP raw keys by ctypes) and
  `crypto/beam` (OTP's eddsa); portable on `crypto/clr` and `crypto/portable`. `ed25519_test.cljc` holds the portable
  code to RFC 8032 §7.1's five vectors and the aegis test's refusals (copied by script from
  `test/aegis/ed25519_test.vs`); `crypto_test.cljc` holds each adapter to TEST 2 and 3, the refusals, and the portable
  code on a random seed. [ran] crypto_test + ed25519_test, 9 tests / 129 assertions, 0 failures: the JVM, babashka,
  nbb, ClojureCLR, clojerl, glojure; basilisp, crypto_test 6 / 85 (its adapter native, 0.8 s). ed25519_test runs on
  basilisp only when named, as core.rrb-vector's suite does: one portable scalar multiplication takes minutes there
  (the adapter test's portable comparison took 799 s before the random-seed check became the adapter's own round
  trip), and Python's integers are exact, so the bounds the test guards are not at risk there.
* **`random-bytes` in every process adapter:** SecureRandom, getRandomValues, os.urandom,
  RandomNumberGenerator, crypto:strong_rand_bytes. The ClojureCLR and BEAM rows are written but not yet run here (§6
  step 3).
* **`request-all`** in `eve.net.run` (a `:requests` step), `eve.net.blobs` (GETs, HEADs and PUTs `PARALLEL` = 32 at a
  time), and the java, node and python http adapters. Probe [ran]: 16 requests to an endpoint that waits 300 ms
  answered in 312 ms on babashka and 449 ms on nbb, against 302 ms for one.
* **A gate for this class of leak.** basilisp found `.indexOf` (a JVM and JavaScript string method) in
  `eve.net.bytes` and `eve.net.json`. They now use lookup tables, and `check-host-leak` counts `net-interop`: method
  calls in `eve/src/eve/net`, held at 0. The rest of `eve/src` has 264 method calls in 20 files, mostly on eve's own
  types; that wider count is not gated yet.

## 7. Build order

1. ~~The `crypto` feature completed on the hosts this box runs~~ (§6).
2. ~~`request-all`~~ (§6).
3. ~~Install and run the other runtimes here (.NET 8 and cljr, OTP 25 and clojerl, glojure through Go, jolt), then
   their http and crypto adapters, each run against the KATs and the stand-in broker, then the mixed simulation~~
   (done for ClojureCLR, clojerl and glojure: `http/clr`, `http/beam`, `http/go`, `crypto/clr`, `crypto/beam`;
   jolt still has no http root).
4. The browser worker adapter (synchronous XHR, `getRandomValues`, portable crypto), run under Playwright's
   Chromium.
5. ~~Ed25519, portable and native~~ (§6).
6. `ws` (Phase 5.1).
