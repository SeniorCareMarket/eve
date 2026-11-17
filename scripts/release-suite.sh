#!/usr/bin/env bash
# THE RELEASE SUITE — the bounded middle battery for an Eve release candidate.
#
#   scripts/release-suite.sh [--legs jvm,bb,jolt] [--strict]
#
# Bigger than a smoke, far smaller than the full corpus: ONE shared property
# namespace (test/eve/release_suite_test.cljc — in-mem atoms, disk atoms,
# collections, wire codec, crypto, network atoms, proc/io, thread) plus the
# http serve area in its own namespace (test/eve/release_suite_http_test.cljc),
# run on every clj* host through the tree's own per-host runner.
#
# Budgets: each leg is capped (60-120 s, see LEGS below); the whole run
# targets 10-20 minutes across all hosts. A cap can be overridden per leg
# with CAP_<LEG> in the environment (e.g. CAP_JVM=420 on a slow box) — caps
# are sized for release hardware; a throttled dev cell needs the override. A leg whose toolchain is missing
# (or too old) is NOT-RUN, never a fake pass and never a fake fail; --strict
# makes NOT-RUN fail the run (the setting a release gate uses).
#
# Verdicts come from the leg's own output: PASS requires exit 0 AND the
# runner's success marker (:summary with :fail 0 :error 0, or the JVM
# runner's "0 failures, 0 errors"). The script's exit code tells the truth:
# 1 if any leg FAILs, TIMEs OUT, or ERRORs (or, with --strict, is NOT-RUN).
#
# Results land in release-suite/results/<leg>.log with a summary.txt.
# Provenance (tool versions, tree, date) is printed at the top of every run.
set -u
cd "$(dirname "$0")/.."
ROOT="$PWD"
RESULTS="$ROOT/release-suite/results"
mkdir -p "$RESULTS"
CORE_NS="eve.release-suite-test"
HTTP_NS="eve.release-suite-http-test"

# On this dev box Maven Central is unreachable from JVM tooling; the seeded
# local repo config lives here when present. Absent elsewhere: no effect.
if [ -z "${CLJ_CONFIG:-}" ] && [ -d "$HOME/workspace/eve-env/cljconfig" ]; then
  export CLJ_CONFIG="$HOME/workspace/eve-env/cljconfig"
fi

LEGS_FILTER=""
STRICT=0
while [ $# -gt 0 ]; do
  case "$1" in
    --legs) LEGS_FILTER=",$2,"; shift 2;;
    --strict) STRICT=1; shift;;
    *) echo "unknown arg: $1" >&2; exit 2;;
  esac
done

OS="$(uname -s)"
ARCH="$(uname -m)"
echo "== release suite =="
echo "tree: $ROOT"
echo "platform: $OS $ARCH"
echo "date: $(date '+%Y-%m-%d %H:%M:%S %Z')"
for tool in clojure java node; do
  if command -v "$tool" >/dev/null 2>&1; then
    echo "tool: $tool $($tool --version 2>&1 | head -1)"
  else
    echo "tool: $tool MISSING"
  fi
done
BBV=""
if command -v bb >/dev/null 2>&1; then
  BBV="$(bb --version 2>/dev/null | awk '{print $2}')"
  echo "tool: bb $BBV ($(command -v bb))"
else
  echo "tool: bb MISSING"
fi
# Eve refuses bb < 1.12.214; every non-JVM leg is driven by a bb task.
BB_OK=0
if [ -n "$BBV" ] && [ "$(printf '%s\n%s\n' "1.12.214" "$BBV" | sort -V | head -1)" = "1.12.214" ]; then
  BB_OK=1
fi

# ClojureCLR's apphost needs DOTNET_ROOT when the .NET runtime lives in a
# keg-only prefix (Homebrew's dotnet@8) instead of a standard install
# location. Resolve it here so nobody has to export it by hand.
if [ -z "${DOTNET_ROOT:-}" ]; then
  for cand in /opt/homebrew/opt/dotnet@8/libexec /usr/local/opt/dotnet@8/libexec /opt/homebrew/opt/dotnet/libexec /usr/local/opt/dotnet/libexec "$HOME/.dotnet" /usr/local/share/dotnet /usr/lib/dotnet; do
    if [ -d "$cand/host/fxr" ]; then
      export DOTNET_ROOT="$cand"
      echo "note: DOTNET_ROOT=$cand (found the .NET runtime there)"
      break
    fi
  done
fi

# leg table: name|cap-seconds|preflight(0=ok, else NOT-RUN reason)|command
LEGS=(
  "jvm|120|clojure|clojure -M:jvm-test -n $CORE_NS"
  "jvm-http|120|clojure|clojure -M:jvm-test -n $HTTP_NS"
  "bb|90|bbtask|bb test:bb $CORE_NS"
  "bb-http|90|bbtask|bb test:bb $HTTP_NS"
  "nbb|90|nbb|bb test:nbb $CORE_NS"
  "nbb-http|90|nbb|bb test:nbb $HTTP_NS"
  "lpy|120|basilisp|bb test:lpy $CORE_NS"
  "lpy-http|120|basilisp|bb test:lpy $HTTP_NS"
  "jolt|90|jolt|bb test:jolt $CORE_NS"
  "jolt-http|90|jolt|bb test:jolt $HTTP_NS"
  "cljr|120|cljr|bb test:cljr $CORE_NS"
  "cljr-http|120|cljr|bb test:cljr $HTTP_NS"
  "glj|90|gljbuilt|bb test:glj $CORE_NS"
  "glj-http|90|gljbuilt|bb test:glj $HTTP_NS"
  "clje|120|clojerl|bb test:clje $CORE_NS"
  "clje-http|120|clojerl|bb test:clje $HTTP_NS"
  "jank|600|jank|bb test:jank $CORE_NS"
  "vs|120|vsmain|bb test:vs $CORE_NS"
)

preflight_reason() {
  case "$1" in
    clojure)  command -v clojure >/dev/null 2>&1 || echo "clojure CLI not on PATH";;
    bbtask)   [ "$BB_OK" = 1 ] || echo "bb missing or < 1.12.214 (found: ${BBV:-none})";;
    nbb)      [ "$BB_OK" = 1 ] || echo "bb missing or < 1.12.214 (found: ${BBV:-none})"
              command -v nbb >/dev/null 2>&1 || echo "nbb not on PATH";;
    basilisp) [ "$BB_OK" = 1 ] || echo "bb missing or < 1.12.214 (found: ${BBV:-none})"
              command -v basilisk >/dev/null 2>&1 || command -v basilisp >/dev/null 2>&1 || echo "basilisp not on PATH";;
    jolt)     [ "$BB_OK" = 1 ] || echo "bb missing or < 1.12.214 (found: ${BBV:-none})"
              command -v jolt >/dev/null 2>&1 || echo "jolt not on PATH";;
    cljr)     [ "$BB_OK" = 1 ] || echo "bb missing or < 1.12.214 (found: ${BBV:-none})"
              command -v cljr >/dev/null 2>&1 || echo "cljr (ClojureCLR) not on PATH";;
    gljbuilt) [ "$BB_OK" = 1 ] || echo "bb missing or < 1.12.214 (found: ${BBV:-none})"
              [ -x "$ROOT/target/glj-eve/glj-eve" ] || echo "target/glj-eve not built (run: bb glj-eve)";;
    clojerl)  [ "$BB_OK" = 1 ] || echo "bb missing or < 1.12.214 (found: ${BBV:-none})"
              command -v clojerl >/dev/null 2>&1 || echo "clojerl not on PATH";;
    jank)     [ "$BB_OK" = 1 ] || echo "bb missing or < 1.12.214 (found: ${BBV:-none})"
              command -v jank >/dev/null 2>&1 || echo "jank not on PATH";;
    vsmain)   [ "$BB_OK" = 1 ] || echo "bb missing or < 1.12.214 (found: ${BBV:-none})"
              command -v wasmtime >/dev/null 2>&1 || echo "wasmtime not on PATH"
              grep -q "release-suite" "$ROOT/test/eve/runner/vs_main.vs" 2>/dev/null \
                || echo "test/eve/runner/vs_main.vs does not include eve.release-suite-test yet";;
  esac
}

# The install command(s) for a missing preflight key, for THIS platform.
# Routes are the tree's own (doc/hosts/*.md) wherever the tree documents one.
install_hint() {
  case "$1" in
    clojure)
      if [ "$OS" = Darwin ]; then echo "brew install clojure/tools/clojure"; else echo "install the Clojure CLI from https://clojure.org/guides/install_clojure"; fi;;
    bbtask)
      if [ "$OS" = Darwin ]; then echo "brew install babashka   # need >= 1.12.214"; else echo "curl -sLO https://github.com/babashka/babashka/releases/download/v1.13.226/babashka-1.13.226-linux-amd64.tar.gz && tar xzf babashka-1.13.226-linux-amd64.tar.gz   # need >= 1.12.214"; fi;;
    nbb)
      echo "npm install -g nbb@1.6.214   # doc/hosts/nbb.md; then run 'npm install' in this tree once (builds the native addon). If nbb still isn't found (some node installs keep the global bin off PATH — it still runs under npx), shim it: printf '#!/bin/sh\nexec npx --yes nbb@1.6.214 \"\$@\"\n' > \"\$(npm prefix -g)/bin/nbb\" && chmod +x \"\$(npm prefix -g)/bin/nbb\"";;
    basilisp)
      echo "uv tool install basilisp   # or: pipx install basilisp";;
    jolt)
      if [ "$OS" = Darwin ]; then echo "brew install jolt-lang/jolt/jolt"; else echo "install jolt from https://github.com/jank-lang/jolt (its install script)"; fi;;
    cljr)
      if [ "$OS" = Darwin ]; then echo "brew install dotnet@8 && dotnet tool install -g Clojure.Main --version '1.12.*' && ln -s ~/.dotnet/tools/Clojure.Main /opt/homebrew/bin/cljr   # doc/hosts/clojureclr.md"; else echo "dotnet tool install -g Clojure.Main --version '1.12.*' && ln -s ~/.dotnet/tools/Clojure.Main /usr/local/bin/cljr   # needs a .NET 8 SDK; doc/hosts/clojureclr.md"; fi;;
    gljbuilt)
      if [ "$OS" = Darwin ]; then echo "brew install go   # if Go is missing; then build the host once: bb glj-eve"; else echo "install Go if missing; then build the host once: bb glj-eve"; fi;;
    clojerl)
      if [ "$OS" = Darwin ]; then echo "brew install erlang rebar3 && git clone https://github.com/clojerl/clojerl ~/eve-tools/clojerl && (cd ~/eve-tools/clojerl && make) && ln -s ~/eve-tools/clojerl/clojerl /opt/homebrew/bin/clojerl"; else echo "apt-get install -y erlang rebar3, then clone https://github.com/clojerl/clojerl, run make in it, and put the built clojerl on PATH"; fi;;
    jank)
      echo "install jank from https://book.jank-lang.org (Installation) so 'jank' is on PATH";;
    vsmain)
      echo "not an install: the VS runner does not include the suite yet — add eve.release-suite-test to the requires in test/eve/runner/vs_main.vs (and keep wasmtime on PATH)";;
  esac
}

RUN_START=$(date +%s)
SUMMARY="$RESULTS/summary.txt"
: > "$SUMMARY"
FAILURES=0
FAILED_LEGS=""
MISSING_KEYS=""

for entry in "${LEGS[@]}"; do
  IFS='|' read -r name cap pre cmd <<< "$entry"
  if [ -n "$LEGS_FILTER" ] && [ "${LEGS_FILTER/,$name,/}" = "$LEGS_FILTER" ]; then
    continue
  fi
  log="$RESULTS/$name.log"
  capvar="CAP_$(echo "$name" | tr 'a-z-' 'A-Z_')"
  cap="${!capvar:-$cap}"
  reason="$(preflight_reason "$pre")"
  if [ -n "$reason" ]; then
    printf '%-10s NOT-RUN  %s\n' "$name" "$reason" | tee -a "$SUMMARY"
    case " $MISSING_KEYS " in *" $pre "*) ;; *) MISSING_KEYS="$MISSING_KEYS $pre";; esac
    if [ "$STRICT" = 1 ]; then FAILURES=1; fi
    continue
  fi
  start=$(date +%s)
  echo_pid=""
  if [ "$name" = "nbb-http" ]; then
    # Node-family hosts cannot serve + sync-request in one process (the
    # client's Atomics.wait park starves the server's event loop), so this
    # leg's server is the suite's echo main in its own process — the
    # production shape. The test reads EVE_SUITE_HTTP_BASE and skips its
    # in-process serve!. See test/eve/release_suite_http_echo.cljc.
    echo_log="$RESULTS/nbb-http-echo.log"
    nbb --classpath "$(bb classpath nbb):$ROOT/test" -m eve.release-suite-http-echo > "$echo_log" 2>&1 &
    echo_pid=$!
    for _ in $(seq 1 60); do
      grep -q "LISTENING" "$echo_log" 2>/dev/null && break
      kill -0 "$echo_pid" 2>/dev/null || break
      sleep 1
    done
    export EVE_SUITE_HTTP_BASE="http://127.0.0.1:18173"
  fi
  timeout -k 5 "$cap" bash -c "$cmd" > "$log" 2>&1
  rc=$?
  if [ -n "$echo_pid" ]; then
    kill "$echo_pid" 2>/dev/null
    unset EVE_SUITE_HTTP_BASE
  fi
  secs=$(( $(date +%s) - start ))
  areas=$(grep -c "AREA .* OK" "$log" 2>/dev/null || true)
  if [ "$rc" = 124 ] || [ "$rc" = 137 ]; then
    verdict="TIMEOUT (cap ${cap}s, areas OK: $areas)"
    FAILURES=1; FAILED_LEGS="$FAILED_LEGS $name"
  elif [ "$rc" = 0 ] && { grep -q ":summary" "$log" && grep -q ":fail 0" "$log" && grep -q ":error 0" "$log" \
       || grep -q "0 failures, 0 errors" "$log"; }; then
    verdict="PASS (${secs}s, areas OK: $areas)"
  elif grep -q ":summary" "$log" || grep -q "failures," "$log"; then
    verdict="FAIL (${secs}s, areas OK: $areas)"
    FAILURES=1; FAILED_LEGS="$FAILED_LEGS $name"
  else
    verdict="ERROR rc=$rc (${secs}s, areas OK: $areas)"
    FAILURES=1; FAILED_LEGS="$FAILED_LEGS $name"
  fi
  printf '%-10s %s\n' "$name" "$verdict" | tee -a "$SUMMARY"
done

if [ -n "$MISSING_KEYS" ]; then
  echo "" | tee -a "$SUMMARY"
  echo "== to run the NOT-RUN legs, install on this platform ($OS $ARCH) ==" | tee -a "$SUMMARY"
  for key in $MISSING_KEYS; do
    case "$key" in
      bbtask) label="bb";; gljbuilt) label="glj";; vsmain) label="vs";; clojerl) label="clje";;
      clojure) label="clojure";; *) label="$key";;
    esac
    printf '%-10s %s\n' "$label" "$(install_hint "$key")" | tee -a "$SUMMARY"
  done
fi

# A failed leg's log prints HERE, in the run output — nobody should have
# to go dig a log file out of the results directory to see why a leg died.
if [ -n "$FAILED_LEGS" ]; then
  for name in $FAILED_LEGS; do
    echo "" | tee -a "$SUMMARY"
    echo "===== $name log (last 40 lines) =====" | tee -a "$SUMMARY"
    tail -40 "$RESULTS/$name.log" | tee -a "$SUMMARY"
  done
fi

TOTAL=$(( $(date +%s) - RUN_START ))
echo "total wall: ${TOTAL}s (budget: 1200s)" | tee -a "$SUMMARY"
if [ "$FAILURES" = 1 ]; then
  echo "RELEASE-SUITE: FAIL" | tee -a "$SUMMARY"
  exit 1
fi
echo "RELEASE-SUITE: OK (legs that ran passed; NOT-RUN legs listed above)" | tee -a "$SUMMARY"
exit 0
