# eve-chat — the aegis chat from a Clojure REPL

The aegis chat (atom.broker's `src/aegis/chat`: a broker that evaluates every write's claim in the clear, a web page
whose client encrypts every message under the room's key) used from a REPL, on any of eve's dialects, in the same room
as the people on the web page. Messages, polls, votes, the private room, announcements, scratch spaces and the audit
log are REPL commands; what a REPL member posts, the web page shows, and the other way round.

```
bb eve-chat local                                  # atom.broker's local broker + page, seeded; prints invite links
bb eve-chat repl --host jvm 'http://127.0.0.1:8790/#room=…&user=carol&key=…'
bb eve-chat hosts                                  # the dialects, and which this machine runs
```

Run from `eve/`. `local` needs atom.broker around eve (it starts `src/aegis/hosts/relay.py` with broker.wasm on 8787
and `src/aegis/hosts/chat_page.py` on 8790, after `atom-broker/scripts/populate-chat-local.mjs` seeds
`build/chat-local/` once). Open one member's link in a browser and another's in a REPL.

## In the REPL

```clojure
(chat/join! "http://127.0.0.1:8790/#room=…&user=carol&key=…")  ; the page's own invite link (the launcher does this)
(chat/say "hello from the JVM")       (chat/pm "members only")    (chat/announce "…")
(chat/msgs)  (chat/msgs 50)  (chat/pms)  (chat/announcements)
(chat/new!)                           ; what arrived since you last looked
(chat/tail! 60)                       ; poll for a minute, printing as messages arrive
(chat/poll! "Lunch?" ["pizza" "sushi"])   (chat/polls)   (chat/vote! 1 "pizza")
(chat/members)  (chat/add-member! "m-dave" "<64-hex Ed25519 public key>")  (chat/remove-member! "m-dave")
(chat/scratch! "notes")  (chat/scratches)  (chat/scratch-post! "ss-…" "a guest's note")
(chat/audit! "ban" "mallory")  (chat/audit-log)
(chat/whoami)  (chat/status)  (chat/help)
```

Every command prints what it did — `✓ post-message (post-message, ed25519)`, or `✗ denied: claim/not-a-member` with
the broker's reason — and answers a small value. Times print as UTC `hh:mm`.

## What it is

Four portable namespaces in `eve/src/eve/aegis/chat/`, written once against eve's host interface (no reader
conditionals; `eve/scripts/check-host-leak` holds them to it):

| Namespace | What |
|---|---|
| `eve.aegis.chat.g2` | the web page's message encryption, byte for byte: the room key `HKDF-SHA256(room secret, "aegis.chat.room-key.v2", atom id)`, AES-256-GCM under an AAD binding the atom, the message id and the author, and the HMAC-SHA256 hiding commitment the broker stores beside the ciphertext. Held to a vector computed independently with Node's crypto (`eve/test/eve/aegis/chat_test.cljc`). |
| `eve.aegis.chat.records` | the chat state's one keyword-keyed shape (the spec's paths; its genesis is `eve.aegis.claims/genesis`) and each action's record, as the web client builds them (`src/aegis/chat/broker_client.vs`) |
| `eve.aegis.chat.client` | a session on blocks (ExtCID v3): `/deref`'s eve-wire body, its pages read into the state value (`eve.net.pages`, every block verified); the record attested (EPOCH, SPEC_ID, EVALUATOR, GROUP_ROOT, PRINCIPAL_COMMIT in its header and its digest); the spec branch's claim over the two state digests (`eve.aegis.claims`: Ed25519-signed for a named write; a vote's ballot blind-signed by the group's issuer, or a ring signature; a guest's post under a delegated session key); the new state written as pages (only its new blocks, the root stamped) and `POST /cas-root` as eve-wire bytes; a lost CAS race rebuilt and retried. Each record is decrypted once per session, and a room re-read under an unchanged root key reuses the last read. |
| `eve.aegis.chat.repl` | the commands above, over one session held in an atom |

Transport is eve's http feature (synchronous on every host that has one), randomness the crypto feature's, the clock
the process feature's. The REPL client never sees anything the web client does not: the broker holds pages of blocks —
ciphertext, commitments, attested headers — and signed claims, never a room key.

## The dialects

| Dialect | REPL | Crypto (the rows a message uses) |
|---|---|---|
| babashka | `bb … repl` (`--init`) | the JVM's adapter: javax.crypto AES/GCM, MessageDigest, the JCA's Ed25519 signature; the public key over BigInteger (the JCA derives none from a seed) |
| Clojure (JVM) | `clojure -M -i … -r` | the same |
| nbb | `nbb -e … (nbb.repl/repl)` | Node's crypto (OpenSSL) |
| ClojureCLR | `cljr -i … -r` | .NET's AesGcm, SHA256/SHA512.HashData; Ed25519 RFC 8032 over System.Numerics.BigInteger (`eve.net.edwards/ed25519-rows`: .NET has none) |
| basilisp | relayed into `repl_loop.lpy`, basilisp's reader and evaluator in a plain loop (`basilisp repl` takes no init, and its prompt_toolkit front end never ends at a prompt off a terminal) | hashlib / hmac; AES-256-GCM, AES-CTR and Ed25519 from the system's OpenSSL libcrypto by ctypes |
| glojure | relayed: glj-eve prints no prompt off a terminal and exits at its first uncaught exception, so each line goes under `eve.thread-host/attempt`, with a sentinel ending each answer | Go's crypto/aes, crypto/cipher, crypto/sha256, crypto/sha512, crypto/ed25519, crypto/ecdh, compiled into glj-eve through eve's Go host package (`impls/glojure/host/crypto.go`) |
| clojerl | `clojerl -pa … -i … -r` | OTP's crypto (OpenSSL): crypto_one_time_aead, hash, mac, eddsa |
| ValueScript | — | the web page's own client, `client.wasm`, compiled from `.vs`: AES-256-GCM in the kernel's aead module. A compiled VS program has no sockets under WASI, so no REPL client. |
| jolt | — | no http adapter yet (hosts.edn's `:jolt` row has none). |

Every native row is held to eve's portable reference rows and to RFC 7748 / RFC 8032 / NIST SP 800-38D vectors by
`eve/test/eve/net/crypto_fast_path_test.cljc` on each host. What each costs is `eve/test/eve/aegis/chat_bench.cljc`
(`bb test:<host> eve.aegis.chat-bench`).

The end-to-end rows are `test/aegis/e2e_rows/b5.py` (atom.broker's `test/aegis/e2e.py`): each installed dialect's
client posts through the real broker and client.wasm reads it, and the other way round (`DIALECT bb->VS`, `DIALECT
VS->bb`, `DIALECT <host>`), one command each through `test/aegis/e2e_rows/b5_dialect.cljc`. nbb cannot run the client
on blocks yet: eve.host has no byte array on ClojureScript, which nbb's eve.codec reads (atom.broker's
`aegis/docs/extcid-v3/HEADER-RESTORE-STATUS.md`, lane B5). The measured table and that run are in atom.broker's report,
`aegis/docs/repl-chat/REPORT.md`. Its data (the log, every REPL's transcript, the screenshots, the bench rows) is in
`docs/port-gap-closure/coordination/reports/repl-chat-data/`.

## Limits

- One room secret per atom (the web page's design): the private room limits who may *write* there, not who can read
  it. Read-scoped regions are a separate spike (atom.broker `aegis/docs/read-regions-spike/REPORT.md`).
- `tail!` polls `/deref` every 2 s and blocks the REPL while it runs; there is no push channel.
- On glojure, the launcher's relay wraps each line it forwards, so type one whole form per line there.
- clojerl 0.9.0's REPL reader refuses a non-ASCII character typed at its prompt (`Couldn't unread to
  erlang.io.PushbackReader: :badarg`): write it as an escape there, `(chat/say "caf\u00e9")`. It reads and prints UTF-8.
- The invite link carries the member's Ed25519 seed in its fragment, as the web page's does: whoever holds the link
  is that member.
