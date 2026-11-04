#!/usr/bin/env bash
# tally.sh — passes/attempts per scenario over repeated runs of the cooperative battery (run.sh). One green run is
# never a result: this runs every selected deftest N times, each run of each
# deftest in its own JVM (run.sh coop.orchestrator/<test>), and counts the runs that reported no FAIL and no ERROR.
# One JVM per scenario because a corrupted atom can crash the orchestrating JVM itself (SIGSEGV in eve's Unsafe read
# path): in one shared JVM that crash would be charged to every scenario after it.
#
#   test/coop/tally.sh N [test …]          e.g. tally.sh 5                       (every deftest)
#                                                    COOP_SCALE=200 tally.sh 3 contention-jvm-wasm round-trip-node-jvm-bb-wasm
#
# Environment: everything run.sh reads (COOP_SCALE, COOP_WASM_BATCH, …), plus COOP_TALLY_LOG (default
# $COOP_LOG_DIR/tally-<epoch>; one log per test per run). Prints, per run, one line per test (PASS / FAIL / CRASH —
# the JVM died before the runner's `Ran` line, its hs_err in COOP_LOG_DIR), then one `TALLY <test> <passes>/<N>` row
# per test and `TALLY-RUNS <N> <ET start>–<ET end>`. Exit 0 when every test passed every run, else 1.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
N="${1:?usage: tally.sh N [test ...]}"; shift
LOG="${COOP_TALLY_LOG:-${COOP_LOG_DIR:-/tmp/coop-atom-logs}/tally-$(date +%s)}"
mkdir -p "$LOG"
et() { TZ=America/New_York date '+%-I:%M:%S %p ET'; }
if [ $# -gt 0 ]; then tests="$*"
else tests=$(grep -oE '^\(deftest (\^:[a-z]+ )?[a-z0-9-]+' "$HERE/orchestrator.clj" | grep -v '\^:soak' | awk '{print $NF}'); fi
declare -A pass crash
for t in $tests; do pass[$t]=0; crash[$t]=0; done
start=$(et)
for k in $(seq 1 "$N"); do
  for t in $tests; do
    f="$LOG/$t-$k.log"
    COOP_LOG_DIR="$LOG" "$HERE/run.sh" "coop.orchestrator/$t" > "$f" 2>&1
    if ! grep -q '^Ran [0-9]* tests' "$f"; then
      crash[$t]=$(( ${crash[$t]} + 1 )); v="CRASH $(grep -m1 -E '^# +(SIG[A-Z]+|J [0-9]+)|Exception' "$f" | cut -c1-120)"
    elif grep -qE '^(FAIL|ERROR) in' "$f"; then v="FAIL  $(grep -m1 -E '^[0-9]+ failures' "$f")"
    else pass[$t]=$(( ${pass[$t]} + 1 )); v="PASS  $(grep -m1 -E '^Ran ' "$f")"; fi
    echo "run $k $t: $v" >&2
  done
done
ok=0
for t in $tests; do
  echo "TALLY $t ${pass[$t]}/$N$( [ "${crash[$t]}" = 0 ] || echo " (JVM crashed ${crash[$t]}×)")"
  [ "${pass[$t]}" = "$N" ] || ok=1
done
echo "TALLY-RUNS $N $start–$(et) logs $LOG"
exit $ok
