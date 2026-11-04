# Security

What eve protects, what it does not, and how to report a problem. Every statement here is what the code does at the
0.9 release; the source files are named so you can check.

## Reporting a vulnerability

Report privately to John Newman at john@seniorcaremarket.com. Please do not open a public issue for an unfixed
vulnerability.

## What is encrypted, by default and otherwise

| Data | Default | Opt-in | Source |
|---|---|---|---|
| In-process atoms and domains | **plaintext** (process memory) | — | `src/eve/atom.cljc` |
| Disk (mmap) domains opened by `e/atom`, `persistent-atom`, `persistent-atom-domain` | **plaintext**: no key or cipher option exists on these constructors | — | `src/eve/atom.cljc` |
| Disk domains opened by `eve.keyring/open-encrypted-domain!` | — | slab blocks encrypted at rest (below) | `src/eve/keyring.cljc` |
| Network atom blobs in the object store | **plaintext** unless the config carries `:gak` | `:gak` (a 32-byte group key) | `src/eve/net/blobs.cljc`, `src/eve/net/atom.cljc` |
| The network atom's root snapshot sent to the broker / gateway | **plaintext, even with `:gak`** | — | `src/eve/net/wire.cljc` (`cas-root`'s `root_blob`), `src/eve/net/snapshot.cljc` |

### The key ring (`eve.keyring`): encryption at rest for a disk domain

* A library-level API: `open-encrypted-domain!` answers a slab I/O and the ring, not an atom domain.
* **Key modes** (`:status`): **1 "auto"** (the default) mints a group key from the operating system's entropy and
  **stores it in plaintext in `<base>.keyring`, beside the data** — anyone who can read the domain's directory can read
  the key, so mode 1 protects only against a reader who has the slab files without the `.keyring` file. **2** takes
  your key and stores it. **3** takes your key and stores only its fingerprint; you supply the key on every open.
* **Cipher**: AES-256-CTR under a per-size-class key derived by HKDF-SHA256 from the group key and a domain salt; the IV
  is the block's class, index and a counter.
* **No integrity**: ciphertext can be modified without detection.
* **Keystream reuse**: a block rewritten in place is encrypted again under the same keystream. The construction gives
  confidentiality against **a single copy** of the files; an attacker who sees two versions of a block learns their
  XOR.
* **Metadata stays plaintext**: slab headers, bitmaps, overflow descriptors, the root file with its CAS words, and the
  atom table (member names).
* Direct region access to an encrypted domain is refused.

### Network atoms (`eve.net.atom`)

* Without `:gak`, the object store and the broker see every value.
* With `:gak`, each blob is AES-256-CTR under a per-blob key derived from the group key and the blob's own key:
  **deterministic** — equal plaintexts encrypt equally — and a blob's **address is the SHA-256 of its plaintext**, so
  the store can confirm a guessed value.
* The **root snapshot** the broker receives on every swap carries the top-level keys and every top-level scalar value
  in plaintext, the entry count, and the content key of each top-level collection's root block. The broker therefore
  sees the value's top level even when blobs are encrypted.
* A gateway (`eve.net.gateway`) serving a disk domain sees everything it serves; its docstring states this.
* Credentials travel as `Authorization: Bearer <api-key or token>`; use HTTPS endpoints.

## The cryptographic code

* eve carries **portable implementations** (`src/eve/net/`): SHA-256, SHA-512, HMAC-SHA256, AES-256 / CTR, XXH3-128,
  Ed25519. They are the reference every native adapter is tested against byte for byte
  (`test/eve/net/crypto_test.cljc`, `ed25519_test.cljc`).
* **They are not constant-time**, and say so: "a host's arithmetic and allocation are not constant time: this is the
  reference a native adapter is held to, not a hardened signer" (`src/eve/net/ed25519.cljc`). They have not been
  externally audited.
* **Where the portable code is the only crypto**: jolt, glojure, ClojureScript in a browser (WebCrypto is
  promise-only) and ValueScript (`hosts.edn`: `crypto/portable`). On those hosts signing and encryption run the
  portable code. The JVM, babashka, Node/nbb, basilisp, ClojureCLR and
  clojerl use their platform's native primitives where it has them (`adapters/crypto/*`), delegating the rest
  (XXH3 everywhere; Ed25519 on ClojureCLR) to the portable code.
* **Entropy**: random bytes come from the operating system (`eve.process/random-bytes`) on every host but ValueScript,
  whose generator is **clock-seeded and not cryptographically strong** (`adapters/process/vs`); `crypto/vs` refuses
  `random-bytes`. Do not mint a group key on ValueScript.
* **Ed25519 small-order keys**: `eve.net.ed25519/well-formed?` refuses the eight small-order public-key encodings, and
  every native adapter answers only where it does, so every eve host gives the same verdict. Known divergence: the
  ValueScript kernel's verifier (outside eve) accepts the identity key, so a broker built on it can disagree with an eve
  client for that key (`doc/network-atoms/ADAPTERS.md` §5).

## Memory safety of shared domains

A disk domain is shared memory: every process that can open its files can read and write every atom in it. eve's
reclamation is epoch-based (`doc/memory-model.md`); a process that writes garbage into a domain's files can corrupt it
for every reader. Give a domain's directory the file permissions you would give the data itself.
