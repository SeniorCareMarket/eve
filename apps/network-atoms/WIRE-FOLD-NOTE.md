# WIRE.md change note — the codec fold (trunk audit entry 4 × entry 7, Nov 14 2026)

For collation into `doc/network-atoms/WIRE.md` §7 (VSNP). Two wire-visible changes, both inside the
root blob's entry forms; the blob framing (magic, XXH3-128 key order, frame/unframe) is unchanged,
and the broker/store layers are form-agnostic (key → bytes) and untouched.

1. **Scalar entries are the trunk's canonical scalar bytes** (eve.codec/value->eve-bytes), not the
   hand-copied fast-tag forms. Byte forms change for: nil (was [0xEE 0xDB 0], now empty), keyword and
   symbol (dedicated trunk tags 0x07–0x0D), and integers outside int32 (trunk INT64 tag 0x0F; the old
   hand copy used tag 7 with the same 8-byte little-endian payload). int32, double, string, boolean
   and char forms are byte-identical to before.

2. **Collection entries: the reference slot is VERSION 3.** A v3 ref names a _flat-image key_ — the
   hex SHA-256 of the entry value's whole trunk image (one image per entry, stored and fetched as one
   block). A v2 ref named a codec band root (a block graph). Readers refuse v2 refs with
   `:legacy-band-root`; there is no in-place upgrade — a v2 atom's value must be re-committed by a
   writer (deref through a v2-capable reader, reset! through this one). VS peers that write VSNP
   roots need the same migration on their side; the kernel page tier (eve.net.pages) is NOT VSNP and
   is unaffected — pages keep imaging values as kernel seal blocks for kernel peers.

Trust: a fetched entry image is believed iff (a) its SHA-256 is its key (the store's address check,
unchanged) and (b) it is the canonical trunk encoding of the value it decodes to — decode, re-encode,
compare bytes; anything else is refused `:non-canonical`. (This replaces the codec band graph's
MD-2 canonical-tree check for stored values; the wire codec's own check-* family still guards the
kernel page tier.)
