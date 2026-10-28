#!/usr/bin/env bash
# run.sh — the cooperative disk-atom scenarios with the wasm runtime in the mix (doc/testing.md): the JVM
# orchestrator orchestrator.clj drives eve's JVM (in process), eve's Node worker, nbb (workers/nbb_worker.cljs), bb
# (workers/bb_worker.clj) and the wasm participant (workers/coop_worker.vs, compiled by ValueScript's builder, run by
# its hosts/eve-mmap's vs-eve-host) against one mmap atom. The capstone (mix.cljc: eve's RRB vectors, sorted
# sets and maps mixed in one atom) runs every eve host — the JVM, Node, nbb, bb, ClojureCLR (workers/cljr_worker.clj),
# basilisp (workers/lpy_worker.lpy), jolt (workers/jolt_worker.clj) and clojerl (workers/clje_worker.clje) — at once.
#
#   test/coop/run.sh [test-var-or-ns…]       e.g. run.sh coop.orchestrator/round-trip-node-jvm-bb-wasm
#
# Environment: EVE_DIR (default: the eve tree this script is in) · VS_DIR (ValueScript's tree; default ../vs beside
# eve) · COOP_ROOT (where the bases go: the host's --eve-root;
# default /tmp/coop-atom) · COOP_SCALE (contention ops per writer; default 40) · COOP_WASM_HOST (default: the host this
# script builds) · COOP_LOG_DIR (default /tmp/coop-atom-logs) · COOP_WASM_BATCH (a wasm contention writer's ops per
# process; unset: all in one — a VS program frees no temporaries, so one process's ops are bounded).
# Prepares, once each: eve's native addon and target/eve-test/mmap-worker.js (rebuilt when eve's sources are newer),
# basilisp's compiled namespaces (target/lpycache, cleared and rebuilt when eve's sources are newer: basilisp checks a
# namespace's own source only, not the macros it expanded);
# the host; the worker's .wasm (rebuilt when its sources are newer). eve is used as it stands (its coop-* Node worker
# modes), so nothing is applied. Exit: the test runner's. A JVM crash report (hs_err) goes to COOP_LOG_DIR, not the test
# tree: a corrupted atom can send eve's reads to a wild address.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
EVE="${EVE_DIR:-$(cd "$HERE/../.." && pwd)}"
VS="${VS_DIR:-$EVE/../vs}"
LOG="${COOP_LOG_DIR:-/tmp/coop-atom-logs}"
ROOT="${COOP_ROOT:-/tmp/coop-atom}"
WORK="$EVE/target/coop/wasm"
export PATH="/opt/node24/bin:$PATH"
mkdir -p "$LOG" "$ROOT" "$WORK"
die() { echo "coop-atom run: $*" >&2; exit 2; }

# eve: as it stands (no patches); the addon, the Node worker
cd "$EVE" || die "no eve checkout at $EVE"
grep -q 'ROOT_HOLD_OFFSET' src/eve/deftype_proto/data.cljc && grep -q '"coop-sem-step"' test/eve/runner/mmap_worker_main.cljs \
  || die "$EVE has no ROOT_HOLD_OFFSET or no coop-sem-step worker mode: not the eve these scenarios are written for"
command -v nbb > /dev/null || die "no nbb on PATH (npm install -g nbb): the nbb participant runs eve's sources under it"
command -v cljr > /dev/null || die "no cljr on PATH (ClojureCLR's Clojure.Main): the ClojureCLR participant runs eve's sources under it"
command -v basilisp > /dev/null || die "no basilisp on PATH (pip install basilisp): the basilisp participant runs eve's sources under it"
command -v jolt > /dev/null || die "no jolt on PATH (github.com/jolt-lang/jolt, on a threaded Chez Scheme 10): the jolt participant runs eve's sources under it"
command -v clojerl > /dev/null || die "no clojerl on PATH (github.com/clojerl/clojerl): the clojerl participant runs eve's sources under it"
# eve's NIF for clojerl (target/clje/eve_clje_nif.so), built here once rather than by the first workers at once
clojerl -pa src -pa adapters/core/beam/src -e "(require 'eve.clje-nif) (prn (eve.clje-nif/load!))" > "$LOG/clje-nif.log" 2>&1 \
  && grep -q '^:ok' "$LOG/clje-nif.log" || die "eve's clojerl NIF did not build or load ($LOG/clje-nif.log)"
[ -f build/Release/mmap_cas.node ] || { npm install > "$LOG/eve-npm.log" 2>&1 && npx node-gyp rebuild >> "$LOG/eve-npm.log" 2>&1; } \
  || die "eve's native addon did not build ($LOG/eve-npm.log)"
if [ ! -f target/eve-test/mmap-worker.js ] || [ -n "$(find src adapters test -name '*.clj[cs]' -newer target/eve-test/mmap-worker.js | head -1)" ]; then
  npx shadow-cljs compile mmap-worker > "$LOG/eve-shadow.log" 2>&1 || die "shadow-cljs compile mmap-worker failed ($LOG/eve-shadow.log)"
fi

LPYCACHE="$EVE/target/lpycache"
if [ ! -f "$LPYCACHE/.stamp" ] || [ -n "$(find src adapters test/coop -name '*.clj[cs]' -newer "$LPYCACHE/.stamp" -o -name '*.lpy' -newer "$LPYCACHE/.stamp" | head -1)" ]; then
  rm -rf "$LPYCACHE" && mkdir -p "$LPYCACHE"
  LPY_ROOTS=$(bb -e '(doseq [r (:lpy (clojure.edn/read-string (slurp "hosts.edn")))] (print "-p" r ""))')
  PYTHONPYCACHEPREFIX="$LPYCACHE" basilisp run -p src $LPY_ROOTS -p test -c "(require 'eve.atom 'coop.mix)" > "$LOG/basilisp-compile.log" 2>&1 \
    && touch "$LPYCACHE/.stamp" || die "eve did not load under basilisp ($LOG/basilisp-compile.log)"
fi

# the host and the wasm worker
# ValueScript's host build prints the host's path last
HOST="${COOP_WASM_HOST:-}"
if [ -z "$HOST" ]; then
  "$VS/hosts/eve-mmap/build" > "$LOG/host-build.log" 2>&1 || die "vs-eve-host did not build ($LOG/host-build.log)"
  HOST="$(tail -1 "$LOG/host-build.log")"
  [ -x "$HOST" ] || die "vs-eve-host's build named no host ($LOG/host-build.log)"
fi
WASM="$WORK/coop_worker.wasm"
if [ ! -s "$WASM" ] || [ "$HERE/workers/coop_worker.vs" -nt "$WASM" ] || [ "$VS/src/vs/eve_disk.vs" -nt "$WASM" ] \
   || [ "$VS/src/vs/mmap.vs" -nt "$WASM" ] || [ "$VS/src/vs/kernel/wasm/valuescript.wasm" -nt "$WASM" ]; then
  "$VS/bin/vs" compile "$HERE/workers/coop_worker.vs" -o "$WASM" > "$LOG/worker-compile.log" 2>&1 && [ -s "$WASM" ] \
    || die "coop_worker.vs did not compile ($LOG/worker-compile.log)"
fi

# the orchestrator: eve as a local dependency, cognitect's test runner over this directory (coop.orchestrator)
cd "$HERE" || exit 2
export EVE_DIR="$EVE" COOP_DIR="$HERE" COOP_WASM_HOST="$HOST" COOP_WASM_WORKER="$WASM" COOP_ROOT="$ROOT"
sel=()
for t in "$@"; do case "$t" in */*) sel+=(-v "$t") ;; *) sel+=(-n "$t") ;; esac; done
[ ${#sel[@]} -gt 0 ] || sel=(-n coop.orchestrator -e soak)   # the soak runs by name only
exec clojure -Sdeps "{:paths [\"$EVE/test\"]
                     :deps {eve/eve {:local/root \"$EVE\"}
                            io.github.cognitect-labs/test-runner {:git/tag \"v0.5.0\" :git/sha \"48c3c67\"}}}" \
  -J--add-opens -Jjava.base/java.lang=ALL-UNNAMED -J--enable-native-access=ALL-UNNAMED \
  -J-Dio.netty.tryReflectionSetAccessible=true "-J-XX:ErrorFile=$LOG/hs_err_pid%p.log" \
  -M -m cognitect.test-runner -d "$HERE" "${sel[@]}"
