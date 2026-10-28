# eve-media — the done bar's evidence (2026-10-20)

`bb eve-media verify` on the release-prep box, at `24c53bc8e` (the capstone's coordinator) / `0cdd0987b` (the
launcher), 5:54–6:09 PM ET: **PASSED**.

- `verify.edn` — the comparison: every row true (each run's checks; the killed transcode at `:attempts 2` with the
  undisturbed run's SHA-256, in both sets; the BEAM the leader when present, another runtime when absent, with the
  same outputs).
- `core.edn`, `core-kill.edn`, `core+beam.edn`, `extended.edn`, `extended-kill.edn`, `extended-no-beam.edn` — each
  run's report (`report.edn` in its run directory): its checks, leader, members, `:ran-on`, the kill row, the
  drop-in row (core), the page's check (core, extended), the output checksums.
- `core-page.png`, `extended-page.png` — the page (`?runtime=0`, a view) once its table showed every file, taken
  over the DevTools protocol by the launcher.

| run | members | wall | leader | notes |
|---|---|---|---|---|
| core | bb, JVM, Node, Go, browser | 160 s | jvm | 11 files with the drop-in (picked up in 0.55 s); the browser's first incarnation lost its lease while joining and was restarted |
| core-kill | bb, JVM, Node, Go | 117 s | bb | the JVM (the leader) killed mid-transcode of f02; f02's transcode `:attempts 2` |
| core+beam | + BEAM | 108 s | clje | |
| extended | + BEAM, jolt, browser | 168 s | clje | the browser joined in 14 s and kept its lease |
| extended-kill | + BEAM, jolt | 171 s | clje | jolt killed mid native transcode of f02; `:attempts 2` |
| extended-no-beam | + jolt | 80 s | jvm | |

**The browser's lease 60 s** (`page.cljs`, after the core run above lost the browser's first incarnation to a 30 s
lease while joining): `bb eve-media verify --only core,extended`, 6:12–6:18 PM ET — **PASSED**, no restart in either
(`verify-browser-60s.edn`, `core-browser-60s.edn` 124 s, `extended-browser-60s.edn` 168 s; `core-browser-60s-page.png`
with the view-only header).
