#!/usr/bin/env bash
# Durability spike — 4-process contention perf leg, Node host.
# Usage: run-contention-node.sh <bench-js> <variant> <n-per-worker> <sync-every> <out-file>
# Workers are released together via a .go file after all have joined the
# domain; makespan = max worker loop elapsed (as printed by each worker).
set -u
BENCH_JS="$1"; VARIANT="$2"; N="${3:-100}"; SYNCEV="${4:-10}"; OUT="$5"
ROOT="${BENCH_DIR:?}/contention-node-$VARIANT"
rm -rf "$ROOT"* 2>/dev/null
mkdir -p "$BENCH_DIR"
node "$BENCH_JS" cinit "$ROOT" > /dev/null || { echo "cinit failed" >&2; exit 1; }
PIDS=()
for w in 1 2 3 4; do
  node "$BENCH_JS" cworker "$ROOT" "$VARIANT" "$N" "$SYNCEV" "$ROOT.elapsed.$w" &
  PIDS+=($!)
done
# wait for all four ready markers (worker joined + warmed), max ~180 s
for i in $(seq 1 180); do
  C=$(ls "$ROOT".ready-* 2>/dev/null | wc -l)
  [ "$C" -ge 4 ] && break
  sleep 1
done
touch "$ROOT.go"
FAIL=0
for p in "${PIDS[@]}"; do wait "$p" || FAIL=1; done
VALS=$(for f in $(ls "$ROOT".elapsed.* 2>/dev/null | sort); do cat "$f"; echo; done)
MAX=$(echo "$VALS" | sort -g | tail -1)
node "$BENCH_JS" cread "$ROOT" > "$ROOT.read.txt" 2>&1
READV=$(grep -o ':value [0-9-]*' "$ROOT.read.txt" || echo ':value ?')
TOTAL=$((4 * N))
OPSEC=$(awk -v t="$TOTAL" -v ms="$MAX" 'BEGIN{printf "%.1f", t/(ms/1000)}')
{
  echo "variant=$VARIANT n-per-worker=$N workers=4 fail=$FAIL"
  echo "worker-elapsed-ms: $VALS"
  echo "makespan-ms: $MAX"
  echo "total-swaps: $TOTAL  aggregate-ops-sec: $OPSEC"
  echo "final: $READV (expected :value $TOTAL)"
} | tee "$OUT"
