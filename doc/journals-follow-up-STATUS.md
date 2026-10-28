# journals-follow-up — STATUS

Lane opened 2026-10-28 (John's order, after the journals composition
merged to main). Base: origin/main @ 13c7e845e58 (verified by
ls-remote). Branch: claude/journals-follow-up. Worktree:
~/workspace/wt/journals-follow-up.

## The gap (measured, spike report §phase-3 census)

ClojureCLR reads .cljc with features #{:cljr}, Basilisp with #{:lpy}
— re-verified this lane with the census featprobe on both hosts
(:took-cljr / :took-lpy; a bare :clj arm falls to :default). Every
journal host op in eve/src/eve/atom.cljc is spelled #?(:clj … :cljs …)
with no :cljr/:lpy arm, so on those seats the defns compile empty:
journal-open! returns nil, journal-with-lock never calls f,
journal-flush!/recover!/anchor! are no-ops. A CLR seat opened with
:journal? true creates no .journal file at all (census-cljr domain
listing: root+slabs+rmap, no journal). The phase-3 runner therefore
seats cljr/lpy in J cells as "Jn" non-journal participants.

## Items

1. **CLR journal append port** — :cljr arms for journal-record-bytes,
   journal-scan, journal-with-lock, journal-flush!, journal-recover!,
   journal-open!, journal-record-bytes-of, journal-anchor!,
   journal-close! in eve/src/eve/atom.cljc; byte format identical
   (EVJ1, big-endian, CRC-32/ISO-HDLC). Then seat cljr as "J" (not
   "Jn") in crash cells.
2. **Basilisp journal append port** — same, :lpy arms (os/fcntl/zlib
   via Python interop, mirroring the :cljs arms' inline js/require).
3. **VS-seat journal writer** — re-check the translate-protos
   deftype wall on the current shipped pair (builder 02c86f56a24efd4e,
   installed on main by 755132c991a). If it stands: bank the minimal
   repro on current main and report. If it moved: build the writer
   via the coop route and prove it in a crash cell.
4. (If 1–3 land) fast-disk phase-3 cell on j.slaa.us.

## Verification bar

- Crash-suite cells per host touched: per-host recorder (J1/J10,
  counter + kb64) → JVM crash reader on materialized images
  (cross-host byte-format proof), totals from the suite's own
  "# totals:" line.
- JVM suites stay 47 tests / 419 assertions / 0 failures.
- Lock exclusion measured cross-process (JVM FileChannel.lock is
  OFD fcntl on this box): the CLR/lpy journal lock must exclude a
  JVM holder and vice versa, or the port does not count.

## Log

- 2026-10-28 lane open: base verified, worktree + branch created,
  journal surface mapped (all host ops live in eve/src/eve/atom.cljc,
  lines ~413–870), reader features re-measured on both hosts.
