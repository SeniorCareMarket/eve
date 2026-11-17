# network-atoms — the eve.net application

The network-atom server application (passive broker, gateway, store role, network-atom client and its
wire formats), extracted from Eve's core tree (`src/eve/net/`) by the trunk-consolidation lane for audit
entry 7 (Nov 14 2026). Namespaces are unchanged (`eve.net.*`); only the placement moved: the application
sits above Eve as a higher-level consumer, getting what it needs through the atom API (`eve.atom`) and
the adapter feature namespaces (`eve.http`, `eve.process`, `eve.http-server`, `eve.crypto`,
`eve.encoding`, `eve.codec`) — the composition `eve.net.platform` already performs.

Layout (mirrors net-local/ and capstone/ in the same repo):

- `src/eve/net/` — atom, blobs, broker, broker-disk, codec (the VS-kernel wire adapter, reclassified —
  see below), crypto (the G4 blob-encryption module over eve.crypto), gateway, graft, pages, platform,
  presign, run, snapshot, store, wire (15 files)
- `test/eve/net/` — broker-test, broker-disk-test, codec-compat-test, codec-fixture-test, crypto-test,
  fold-pricing-test, graft-test, json-test, remote-atom-test, seen-set-test, snapshot-test, wire-test,
  stand-in (test support)

## The codec fold (audit entry 4, executed with this extraction)

Eve-internal storage no longer images values with the wire codec's band/block graph. `eve.net.atom`
stores each top-level entry as ONE trunk image (eve.codec/value->eve-bytes) keyed by its hex SHA-256;
swap deltas are image-digest comparisons; a fetched image is believed iff its SHA-256 is its key and
it re-encodes to itself (`:non-canonical` otherwise). `eve.net.snapshot`'s scalar forms ARE the trunk
bytes and its collection refs are version 3 (flat-image keys) — see WIRE-FOLD-NOTE.md. The atom's
handle is a host-deftype (NetAtom) answering the trunk atom contract (deref / swap! / reset! /
compare-and-set!); watches are the trunk watch build's surface, not re-implemented here.

`eve.net.codec` remains, reclassified in its own docstring as the VS-KERNEL WIRE codec: encode-rec /
decode-rec and the record readers serve eve.net.pages' kernel page tier (attested by
eve.aegis.pages-v3-test), the sealer/attest family and check-* guard that tier. Its one atom-side
reader, rec-delta, is deleted. eve.net.pruned-swap-test priced the old imaging and is retired; its
claims are re-stated over entry images in fold-pricing-test.

Left in core, deliberately: the portable crypto primitives (the crypto lane adapter-homes them at
collation) and the shared substrate `eve.net.bytes` / `eve.net.json` / `eve.net.u64` (app foundation,
consumed by core and aegis). Known core-side consumers of this app: `eve.thread.net` /
`eve.thread.server` (the thread subsystem's network tier) — disposition banked in the lane's
STATUS.md (inversions I-1/I-2).
