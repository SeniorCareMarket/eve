# aegis — the blind-signature / attestation application

Extracted from Eve's core tree (`src/eve/aegis/`, 10 files, 1,907 lines) by the trunk-consolidation
lanes (audit entry 8; crypto lane census + net lane execution, Nov 14 2026). Namespaces unchanged
(`eve.aegis.*`); only the placement moved: aegis is an application above Eve, consuming the crypto
feature through the crypto adapter's rows (its ed25519/bignum/ell2 libraries are adapter-homed by the
crypto lane — the eve.crypto facade exposes none of the 30+ curve/bignum internals aegis calls, and no
surface was invented to pretend otherwise) and `eve.net.bytes`, the net foundation that stays in core.

- `src/eve/aegis/` — ballot, claims, clsag, commit, h2c, hpke, merkle, regions, rsabssa, session
- `test/eve/aegis/` — the suites + spec/vector support files. NOTE: `pages_v3_test.cljc` here is in
  substance the NETWORK-ATOMS wire acceptance (eve.net.pages + the eve.net.codec sealer against the VS
  kernel, CROSS PASS, cross-repo opt-in via VS_DIR) — it lives at this path because its namespace is
  `eve.aegis.pages-v3-test`; see apps/network-atoms/README.md.

Collation wiring (unchanged by the move, recorded): deps.edn's :jvm-smoke alias and bb.edn's
test:bb-smoke / test:bb-minimal exclusions name eve.aegis.*; impls/scittle's seat runs its namespaces.
