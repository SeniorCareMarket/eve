# DIGEST SPIKE — eve.digest wired into the stream send (Nov 12 2026)

John's direction: the Merkle hash of everything is already in the
tree (eve.digest, Phase 3) and present on the read walk — spike
connecting it to the send pipeline. Built personally in this tree
(claude/flat-reengine), on top of the rebuilt sender.

## What was built

- eve.digest gains one public entry, `latch-digest-words!`: stamp a
  root's digest from words computed elsewhere, under the same
  write-once latch discipline as a locally computed digest;
  already-latched words are never rewritten (a mismatch answers
  false and leaves the latched words standing).
- eve.flat.stream/send-stream gains `:digests true` (default off —
  wire byte-identical to before, flag byte 0):
  - At each block's ENTER, a guarded read of its latched digest
    (eve.digest/cache-block + flags + words; never computes).
  - A root whose digest was already sent in this stream is aliased
    to the first index and its whole subtree is PRUNED — never
    fetched, never scanned, never descended.
  - Emitted roots' digests collect into a trailer after the
    segments (header flag bit 0; u32 count, then per entry a u32
    stream index + the 8 i32 digest words).
- receive-stream! parses the trailer and stamps each placed root's
  digest cache from it (latch-digest-words!), answering
  :digests-stamped. The checked door is unaffected and was on for
  every receive measured below.

## Verdict (hatch, same-JVM A/B, min-of-3 sends)

Repeats corpus — a vector of 200 independently built copies of one
50-entry map (distinct blocks, equal content; all 200 row digests
latched):

| | blocks | wire bytes | send | receive |
|---|---|---|---|---|
| digests off | 3,409 | 341,693 | 179.7 ms | 204.2 ms |
| digests on | **225** | **7,015** | **8.4 ms** | **9.1 ms** |

199 subtrees deduped; exactly 1 digest shipped (the table carries
only roots actually emitted); 1 root stamped on receive; placed
value EQUAL to the original under the checked door.

Records-10k, nothing latched: wire identical (1,817,243 B both
ways); send 1,187.5 → 1,305.5 ms — the per-block descriptor lookup
tax (~10% on this box) with zero gain. Refinement named: only roots
can carry digests, so gate the lookup to header blocks instead of
every block.

Records-10k, all 10,000 row digests latched (all distinct): wire
1,817,243 → 2,177,247 B (the trailer is exactly 4 + 36 x roots);
send 1,090.0 → 1,541.3 ms; receive stamps all 10,000 roots, value
equal. Shipping non-repeating digests is a real size/time tax; the
counterweight is that the receiving domain's roots arrive
pre-latched (their first equality/hash is free) and the domain
accumulates a content index by receiving.

## Suites

eve.flat.stream-test green. eve.digest-cache-test green alone
(5 tests / 34 assertions). eve.flat.transfer-test: the
transfer-in-process free-count assertion (line 156) and the
cross-process test flip with box load, on this tree and on HEAD:
spike tree failed at load ~15-20 three times and passed at load
~10 twice — the calmest run is 150 pass / 0F / 0E including
cross-process. HEAD passed both its (calmer) runs. Classified as a
load-sensitive flake in the test's accounting assertions, not a
spike regression: with :digests off the new code paths are inert
(read-verified), and identical code flips verdicts with load alone.
HEAD at load 20 was not run — stated plainly.

## Trust modes (built Nov 13, on John's direction)

There are now two modes, as there should be: if you own both
atoms you do not pay for rehashing; bytes from outside are
verified before anything is latched. receive-stream! takes
:digest-trust:

- :trusted — stamp the shipped digests as-is.
- :verify — the DEFAULT for raw receives: recompute each digested
  root's digest from its placed content (eve.digest/digest-words-of,
  nothing latched by the computation) and stamp only on a match;
  a mismatch refuses the whole stream (:digest-mismatch) and the
  placed blocks are freed by the existing failure path.
- :off — ignore the trailer.

stream-transfer! — the atom-to-atom door, where both ends are the
owner's — now sends with :digests true and receives with
:digest-trust :trusted: the owned path runs the digest machinery
end to end.

Priced on records-latched (10,000 digested roots): trusted receive
235.6 ms, verify receive 789.8 ms — verification costs ~55 us per
root (a codec encode + SHA-256 of the row), exactly the rehashing
the trusted mode exists to skip. On the repeats corpus the verify
premium over trusted is 4.2 -> 5.5 ms (one root).

## The kind gate (Nov 13)

The first modes run exposed the lookup tax at full strength on a
calm box: records-plain send 586 -> 1,096 ms with digests on
(per-block descriptor reads). Digests live only on collection
roots, and a root is always reached as a kind-0 (header-tagged)
block, so the walk now attempts the lookup only for kind-0
blocks — HAMT interiors, strings and scalar blocks skip it.
Repeats dedup is intact (199 pruned; rows are reached as kind-0),
and the records-plain tax fell to 384 -> 450 ms (~2 us per
remaining lookup). A root reached by a non-0 kind would be missed
silently — a lost dedup, never a wrong one: latched-digest is
nil-safe and the trailer only ever carries roots actually sent.

## Suites with the modes wired

eve.flat.transfer-test 150 pass / 0F / 0E (transfer! now runs
digests-on + trusted on every call); eve.flat.stream-test +
eve.digest-cache-test 64 pass / 0F / 0E.
