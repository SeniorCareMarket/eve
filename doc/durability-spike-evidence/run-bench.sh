#!/usr/bin/env bash
# =====================================================================
# Eve 0.9 durability spike — one-command benchmark runner.
#
#   bash eve/doc/durability-spike-evidence/run-bench.sh
#
# Runs the JVM single-host matrix (variants A / B / J1 / J10 / J100 /
# J1000 x counter / ~1 KB / ~64 KB swap! loops, with per-flush latency
# lines for the J variants) and then, if Node is present, the Node
# matrix. Prints everything to stdout AND to run-bench-results.txt
# beside this script — paste that file's contents back.
#
# Requirements:
#   - JDK 17+ (21 tested).   macOS:  brew install openjdk@21
#   - Clojure CLI tools.     macOS:  brew install clojure/tools/clojure
#   - Node 20+ (optional; only for the Node matrix).
#                            macOS:  brew install node
#   First run downloads Clojure deps from Maven Central (one-time).
# Overrides: BENCH_N_COUNTER / BENCH_N_KB / BENCH_N_KB64 set iteration
# counts; SKIP_NODE=1 skips the Node matrix; ROUNDS=n repeats the JVM
# matrix n times.
# =====================================================================
set -u
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
EVE_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
REPO_DIR="$(cd "$EVE_DIR/.." && pwd)"
SCRATCH="$SCRIPT_DIR/.run-bench-scratch"
RESULTS="$SCRIPT_DIR/run-bench-results.txt"
export BENCH_DIR="$SCRATCH/bench-roots"
mkdir -p "$BENCH_DIR"
: > "$RESULTS"
exec > >(tee -a "$RESULTS") 2>&1

echo "# Eve durability spike bench — $(hostname) — $(date)"
echo "# machine: $(uname -srm)"
echo "# eve tree: $EVE_DIR"
echo
echo "## Setup detected"
if [ -z "${JAVA_HOME:-}" ] && [ -x /usr/libexec/java_home ]; then
  export JAVA_HOME="$(/usr/libexec/java_home 2>/dev/null || true)"
fi
java -version 2>&1 | head -1 || echo "MISSING: java — brew install openjdk@21"
clojure --version 2>&1 || echo "MISSING: clojure — brew install clojure/tools/clojure"
node --version 2>&1 || echo "node: not found (Node matrix will be skipped)"
echo

CLJ_FLAGS="-J--add-opens=java.base/java.nio=ALL-UNNAMED -J--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
BENCH_PATHS="[\"$EVE_DIR/bench\"]"
run_single() { # eve-dir variant
  (cd "$1" && clojure $CLJ_FLAGS -Sdeps "{:aliases {:t {:extra-paths $BENCH_PATHS}}}" -M:t \
     -e "(require 'eve.durability-bench) (eve.durability-bench/-main \"single\" \"$2\")" 2>&1)
}

# Variant A's code is pristine main; this branch carries the fix. Get
# main's eve/ as a detached worktree when git allows; otherwise fall
# back to variant C (fix present, per-commit force off) as baseline.
A_DIR=""
WT="$SCRATCH/a-tree"
if git -C "$REPO_DIR" rev-parse --git-dir >/dev/null 2>&1; then
  git -C "$REPO_DIR" fetch -q origin main 2>/dev/null || true
  if git -C "$REPO_DIR" worktree add --detach "$WT" origin/main >/dev/null 2>&1 \
     || git -C "$REPO_DIR" worktree add --detach "$WT" main >/dev/null 2>&1; then
    A_DIR="$WT/eve"
  fi
fi
cleanup() {
  if [ -n "$A_DIR" ]; then git -C "$REPO_DIR" worktree remove --force "$WT" >/dev/null 2>&1 || true; fi
}
trap cleanup EXIT

export BENCH_N_COUNTER="${BENCH_N_COUNTER:-2000}"
export BENCH_N_KB="${BENCH_N_KB:-500}"
export BENCH_N_KB64="${BENCH_N_KB64:-200}"
ROUNDS="${ROUNDS:-1}"

echo "## JVM singles (us/op per row; J variants add :flush-latency rows)"
echo "counts: counter=$BENCH_N_COUNTER kb=$BENCH_N_KB kb64=$BENCH_N_KB64, rounds=$ROUNDS"
if [ -n "$A_DIR" ]; then echo "baseline: variant A from origin/main worktree"; else echo "baseline: variant C (origin/main worktree unavailable)"; fi
echo
for r in $(seq 1 "$ROUNDS"); do
  if [ "$r" -gt 1 ]; then ORDER="J1000 J100 J10 J1 B A"; else ORDER="A B J1 J10 J100 J1000"; fi
  for v in $ORDER; do
    case $v in
      A) if [ -n "$A_DIR" ]; then run_single "$A_DIR" A; else run_single "$EVE_DIR" C; fi;;
      *) run_single "$EVE_DIR" "$v";;
    esac
  done
done
echo
echo "## Node singles"
NODE_JS="$EVE_DIR/target/durability-bench/main.js"
if [ "${SKIP_NODE:-0}" = "1" ]; then
  echo "skipped (SKIP_NODE=1)"
elif ! command -v node >/dev/null 2>&1; then
  echo "SKIPPED Node matrix: node not found — brew install node"
elif ! command -v npx >/dev/null 2>&1; then
  echo "SKIPPED Node matrix: npx not found (comes with node)"
else
  (cd "$EVE_DIR" && npm install --no-audit --no-fund 2>&1 | tail -2)
  BUILD_LOG="$SCRATCH/shadow-build.log"
  echo "building the CLJS bench (shadow-cljs release durability-bench)…"
  if (cd "$EVE_DIR" && npx shadow-cljs release durability-bench > "$BUILD_LOG" 2>&1) \
     && [ -f "$NODE_JS" ]; then
    echo "bench build OK: target/durability-bench/main.js"
    for v in A B J1 J10 J100 J1000; do
      (cd "$EVE_DIR" && node target/durability-bench/main.js single "$v" 2>&1)
    done
  else
    echo "SKIPPED Node matrix: the CLJS bench build failed or produced no output."
    echo "--- last 15 lines of the build log ---"
    tail -15 "$BUILD_LOG" 2>/dev/null || echo "(no build log)"
    echo "--- (JVM results above are complete; this skip loses only the Node rows) ---"
  fi
fi
echo
echo "# done — paste the contents of $RESULTS"
