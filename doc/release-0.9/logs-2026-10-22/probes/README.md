# Probes of 2026-10-21 ET (the final pass)

Small programs that measure figures the docs quote and no committed command measures. Each ran once in the final pass
on the final tree, one at a time; its log is `../f-probe-*.log` (or the leg named in its header comment).

| probe | measures | run as (from eve/) |
|---|---|---|
| `jvm_varhandle.clj` | the JVM region's volatile get and CAS through its spread handles; a get by `invokeWithArguments` | `java -cp "$(clojure -Spath)" clojure.main jvm_varhandle.clj` |
| `lpy_costs.lpy` | basilisp's dotimes iteration, an empty / variadic call, a closure per call, `(str "a" "b")`, a 32-byte `hex`, four CPU loads on four threads, a 40,000-element RRB vector | `basilisp run -p <hosts.edn :lpy roots, last first> -p src lpy_costs.lpy` |
| `lpy_interleave.lpy` | basilisp's own `interleave` over 3,000 elements | `basilisp run lpy_interleave.lpy` |
| `clje_costs.clje` | clojerl: an eve long-array's aset / aget (NIF calls); the binding frame's vars, size and a round trip carrying it | `clojerl -pa src <each hosts.edn :clje root> doc/release-0.9/logs-2026-10-22/probes -m clje-costs` |
| `glj_costs.glj` | a protocol call through glojure's own protocols against a fn call | `target/glj-eve/glj-eve glj_costs.glj` |
| `sab_cap.clj` | a Chromium renderer's SharedArrayBuffer cap: fixed 1 GB buffers, growable 1 GB-maximum ones beside them, 256 MB ones in a worker | `bb sab_cap.clj` |
| `rss_timeline.py` | a process tree's RSS and thread count each second (nbb's isolates) | `python3 -I rss_timeline.py bb test:nbb eve.thread-net-test` |
| `na1.cljc` | doc/network-atoms/README.md's first sample, saved as written | `bb -cp "$(bb classpath bb)" na1.cljc <dir>`; `clojure -M na1.cljc <dir>` |
| `bb_swap_args.clj` | babashka's `swap!` on an eve atom with 6–9 extra arguments | `bb -cp "$(bb classpath bb)" bb_swap_args.clj` |
| `leak_probe_oneop.clj` | bench/eve/leak_probe.clj's sorted set over 1,280 swaps, two persistent operations a swap against one | `java -cp "$(clojure -Spath -M:jvm-test)" clojure.main leak_probe_oneop.clj` |
| `node_http_server.cljs` | eve's http-server feature on Node (change list 4.3, run by no test): `serve!` on a free port, a POST answered as text, a GET answered as bytes (`:body-as :b64`), then `:stop` | `nbb --classpath adapters/http-server/node/src node_http_server.cljs` |

Runs that exited non-zero, and why (`../runs.tsv`):

- `f-probe-sab-cap` (1:17 AM ET): exit 1 after its result was printed — removing Chromium's profile directory raced the
  browser's own exit (`DirectoryNotEmptyException`). The result line is the measurement: 15 fixed 1 GB buffers, then
  refused; 400 growable ones beside them; a worker 3 × 256 MB more.
- `f-probe-clje-costs` (1:17 AM ET), exit 0 but no measurement: clojerl took the file after `-pa`'s paths as one more
  code path and opened a REPL. Run by `-m` since (2:18 AM ET); its first `-m` run (2:18 AM ET) stopped at a pattern
  clojerl rejects (`receive*` takes a tuple as `#erl[from m]`).
- `f-probe-glj-costs` (1:17 AM ET): exit 1, glojure has no `deftype` of its own; a record since.
- `f-mac-ffmpeg-fallbacks` (1:14 AM ET): exit 1 at the check's last step — `ffprobe` was given two files; the encodes
  before it had succeeded. Re-run with one `ffprobe` per file.
- `f-ab-node-f65b23153-1` (12:56 AM ET): exit 1 after 8 s with no output, cause not recorded; main's tree was A/B'd in
  rounds 2 and 3 (`f-ab-node-f65b23153-2`, `-3`), the other two trees in three rounds each.

Runs in `../runs.tsv` with no log here: `clje` (two load failures, 10:09 and 10:13 PM ET, before `cbe95556`),
`codec-isolation-B`, `leak-probe-long` and `perf-jvm-2`. `eve/.gitignore` ignores `*.log`, these four were never
force-added, and they were lost with the first box (2026-10-22, ~2:05 AM ET). Their rows stand.
