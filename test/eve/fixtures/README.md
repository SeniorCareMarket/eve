# Test fixtures

Each file says in its first lines what made it. `vsv2-blocks.edn` and `vsv2-blocks-256.edn` — the G1 corpus as
ValueScript's kernel cuts it into blocks, after and before the narrowing, which `eve.net.codec` must decode and
re-encode byte-exact (`test/eve/net/codec_fixture_test.cljc`, `codec_compat_test.cljc`, `xxh3_test.cljc`) — are
copies of ValueScript's (`vs/test/vs/fixtures/`, cut by `vs/scripts/gen-net-fixtures.vs`), so eve's tests read nothing
outside `eve/`; until 2026-10-22 they were symlinks into `vs/`. When ValueScript cuts them again, copy the new files
here.
