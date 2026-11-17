# The Eve release suite

The bounded middle battery a release candidate runs on every clj\* host:
bigger than a smoke test, far smaller than the full corpus (the daily
molotov). One run targets **10–20 minutes total**, each host leg capped at
**60–120 seconds**.

## Run it

```sh
scripts/release-suite.sh              # all legs
scripts/release-suite.sh --legs jvm,bb,jolt
scripts/release-suite.sh --strict     # NOT-RUN legs fail the run (release gate)
```

Per-leg logs and the summary land in `release-suite/results/`. The script
prints its provenance (tree, date, tool versions) at the top of every run,
and its exit code tells the truth: non-zero if any leg fails, times out, or
errors (or, under `--strict`, is not run).

## What it tests

One shared property namespace, `test/eve/release_suite_test.cljc`, runs in a
single process per host through the tree's own per-host runner
(`bb test:<host> eve.release-suite-test`; `clojure -M:jvm-test -n
eve.release-suite-test` on the JVM). Each area asserts a property of a
surface the release exercises and prints `AREA <name> OK` as it completes,
so a leg killed by its cap still shows which areas ran:

| area | property |
|---|---|
| seams | `eve.math` sqrt; the `eve.edn` reader (incl. exact number tokens) |
| mem-atom | in-memory atom: 200 swaps land, reset/deref agree |
| disk-atom | mmap domain: write, close, reopen — value and `.root` survive |
| collections | 1,000-entry map / 500-vector / 100-set built and read inside swaps |
| codec | `eve.net.codec` wire format: deterministic, round-trips |
| crypto | SHA-256 and HMAC-SHA-256 against the published vectors |
| net-atom | network atom over the in-process broker: a swap commits at epoch 1, the root key is the root blob's SHA-256, a second client reads the value |
| proc-io | `eve.process`: exclusive create wins once, file existence, clock advances, entropy length |
| thread | `eve.thread` promise delivery + a registered-task future |

The http serve area lives in its own namespace
(`test/eve/release_suite_http_test.cljc`) and runs as a separate leg per
host, so a host whose roots carry no http-server adapter fails only that
leg: serve on an ephemeral port, GET answers 200 with the handler's body, a
handler throw answers 500, an unknown path answers 404 (the uniform serve
contract the adapter campaign proved per target).

## Legs and caps

jvm 120 s · bb 90 s · nbb 90 s · lpy 120 s · jolt 90 s · cljr 120 s ·
glj 90 s (needs `bb glj-eve` built once) · clje 120 s · jank 120 s ·
vs 120 s (needs `eve.release-suite-test` required by
`test/eve/runner/vs_main.vs`). Each core leg also has an `-http` twin at the
same cap, except jank and vs.

A leg whose toolchain is missing or too old (bb < 1.12.214, no nbb, no
`target/glj-eve`, …) reports **NOT-RUN** with the reason — never a fake
pass, never a fake fail. When any leg is NOT-RUN, the run ends with an
install section: the commands for the missing runtimes, written for the
platform the script is running on (macOS/Homebrew or Linux), using the
tree's own documented routes (`doc/hosts/*.md`) where it has them.

Not wired yet: cljs/node, browser, scittle, cherry — their runs are page or
shadow-compile routes, not one-process runner invocations; they join when
someone gives them the same one-namespace entry the other hosts have.
