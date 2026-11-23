#!/usr/bin/env bash
# jlog-spike.sh — the jlog spike (doc/perf/eve-mmap-journal-plan.md §5, eve.db.ds):
# writers on the JVM, jolt and Node append to ONE <base>.jlog at once
# (test/eve/jlog_spike.cljc), one of them killed -9 at a random time, then every
# runtime scans the log and scripts/jlog_spike_check.clj judges the exit criteria.
#
#   scripts/jlog-spike.sh
#
# Env: RUNTIMES (writers in order, default "jvm jvm jolt jolt node node"; writer 1
# is the slow one: every 50th append sleeps 150 ms between its write and its
# force, so the others lap it) · SLOTS (N, default 64) · DUR_MS (default 10000) ·
# KILL (1: kill -9 one random writer mid-run, default 1) · OUT (default
# /tmp/eve-jlog-spike/<stamp>) · BASELINE_NS (pwrite+fdatasync median from
# scripts/jlog_syncbench.py on the same filesystem, for criterion 5).
set -uo pipefail
EVE="$(cd "$(dirname "$0")/.." && pwd)"
RUNTIMES="${RUNTIMES:-jvm jvm jolt jolt node node}"
SLOTS="${SLOTS:-64}"
DUR_MS="${DUR_MS:-10000}"
KILL="${KILL:-1}"
OUT="${OUT:-/tmp/eve-jlog-spike/$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$OUT"
log() { echo "[$(TZ=America/New_York date '+%H:%M:%S ET')] $*" | tee -a "$OUT/run.log" >&2; }

free_kb=$(df -Pk "$OUT" | awk 'NR==2{print $4}'); free_in=$(df -Pi "$OUT" | awk 'NR==2{print $4}')
if [ "$free_kb" -lt 1000000 ] || [ "$free_in" -lt 60000 ]; then log "DISK-HOLD ${free_kb} KB, ${free_in} inodes"; exit 3; fi

JCP="$(cd "$EVE" && clojure -Spath -A:jvm-test)"
LCP="$(cd "$EVE" && bb classpath jolt):$EVE/test"
JCWD="$(mktemp -d)"   # jolt reads a deps.edn in its cwd: run it from an empty one
trap 'rm -rf "$JCWD"' EXIT
NODE_JS="$EVE/target/jlog-spike/main.js"
if [[ " $RUNTIMES " == *" node "* ]] && [ ! -f "$NODE_JS" ]; then
  log "building the node driver"
  (cd "$EVE" && npx shadow-cljs release jlog-spike) > "$OUT/node-build.log" 2>&1 || { log "node build failed"; exit 2; }
fi

run() { # rt args... — one process of the driver; exec, so a background run's pid is the runtime's own
  local rt="$1"; shift
  case "$rt" in
    jvm) exec java --add-opens java.base/java.lang=ALL-UNNAMED --enable-native-access=ALL-UNNAMED \
           -cp "$JCP" clojure.main -m eve.jlog-spike "$@" ;;
    jolt) cd "$JCWD" && exec jolt -Scp "$LCP" -m eve.jlog-spike "$@" ;;
    node) EVE_MMAP_ADDON="$EVE/build/Release/mmap_cas.node" exec node "$NODE_JS" "$@" ;;
  esac
}

base="$OUT/log"
rm -f "$base".*
for rt in $(echo $RUNTIMES | tr ' ' '\n' | sort -u); do (run "$rt" golden) > "$OUT/golden.$rt.txt" 2>&1; done
(run jvm init "$base" "$SLOTS") > "$OUT/init.txt" 2>&1 || { log "init failed"; cat "$OUT/init.txt"; exit 1; }
start=$(( $(date +%s%3N) + 12000 ))
log "writers [$RUNTIMES] N=$SLOTS ${DUR_MS} ms from $start; $OUT"
pids=(); w=0
for rt in $RUNTIMES; do
  slow=""; [ "$w" -eq 1 ] && slow=slow
  (run "$rt" writer "$base" "$w" "$start" "$DUR_MS" $slow) > "$OUT/w$w.$rt.log" 2>&1 &
  pids+=($!); w=$((w + 1))
done
nw=$w
if [ "$KILL" = 1 ]; then
  victim=$(( RANDOM % nw ))
  at=$(( start + 1000 + RANDOM % (DUR_MS - 2000) ))
  while [ "$(date +%s%3N)" -lt "$at" ]; do sleep 0.05; done
  # the background subshell exec'd the runtime, so its pid is the runtime's
  kill -9 "${pids[$victim]}" 2>/dev/null && log "killed writer $victim (pid ${pids[$victim]}) at +$(( at - start )) ms"
  echo "$victim" > "$OUT/killed.txt"
fi
for p in "${pids[@]}"; do wait "$p"; done
for rt in $(echo $RUNTIMES | tr ' ' '\n' | sort -u); do (run "$rt" scan "$base" "$nw") > "$OUT/scan.$rt.txt" 2>&1; done
# solo: one writer per runtime alone on a fresh log (criterion 5's per-append cost, no contention)
for rt in $(echo $RUNTIMES | tr ' ' '\n' | sort -u); do
  sb="$OUT/solo-$rt"; rm -f "$sb".*
  (run jvm init "$sb" 1024) > /dev/null 2>&1
  (run "$rt" writer "$sb" 0 "$(( $(date +%s%3N) + 3000 ))" "${SOLO_MS:-4000}") > "$OUT/solo.$rt.log" 2>&1
  [ -n "${KEEP:-}" ] || rm -f "$sb".*
done
bb "$EVE/scripts/jlog_spike_check.clj" "$OUT" "$SLOTS" "${BASELINE_NS:-}" | tee "$OUT/verdict.txt"
[ -n "${KEEP:-}" ] || rm -f "$base".*
