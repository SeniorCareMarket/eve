#!/usr/bin/env bash
# jlog-crash.sh — kill -9 crash tests of a disk domain on the commit log
# (eve-mmap-journal-plan §6.3; test/eve/jlog_crash.cljc), per runtime and per pair.
# Each iteration: a fresh domain (:log :jlog), writers start together; odd
# iterations kill -9 ONE random writer at U(0.5, 5) s (the others run on and
# close; the reopen joins a domain a dead slot still marks live), even ones
# kill -9 ALL writers at once and age their slots (the reopen recovers). Then
# every runtime of the configuration reopens and checks;
# scripts/jlog_crash_check.clj judges every iteration.
#
#   scripts/jlog-crash.sh
#
# Env: CONFIGS (default "jvm jolt node jvm+jolt jvm+node jolt+node jvm+jolt+node";
# a single runtime runs two writers) · ITERS (per configuration, default 50) ·
# DUR_MS (each writer's run, default 5000) · SLOTS (default 64, so the ring laps) ·
# OUT (default /tmp/eve-jlog-crash/<stamp>) · KEEP=1 keeps the domains.
set -uo pipefail
EVE="$(cd "$(dirname "$0")/.." && pwd)"
run() { # rt args... — exec, so a background run's pid is the runtime's own
  local rt="$1"; shift
  case "$rt" in
    jvm) cd "$EVE" && exec java --add-opens java.base/java.lang=ALL-UNNAMED --enable-native-access=ALL-UNNAMED \
           -cp "$JCP" clojure.main -m eve.jlog-crash "$@" ;;
    jolt) cd "$JCWD" && exec jolt -Scp "$LCP" -m eve.jlog-crash "$@" ;;
    node) EVE_MMAP_ADDON="$EVE/build/Release/mmap_cas.node" exec node "$NODE_JS" "$@" ;;
  esac
}
# "$0" __run rt args...: one run, for timeout to wrap (JCP, LCP, JCWD, NODE_JS from the environment)
if [ "${1:-}" = __run ]; then shift; run "$@"; fi
CONFIGS="${CONFIGS:-jvm jolt node jvm+jolt jvm+node jolt+node jvm+jolt+node}"
ITERS="${ITERS:-50}"
DUR_MS="${DUR_MS:-5000}"
SLOTS="${SLOTS:-64}"
OUT="${OUT:-/tmp/eve-jlog-crash/$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$OUT"
log() { echo "[$(TZ=America/New_York date '+%H:%M:%S ET')] $*" | tee -a "$OUT/run.log" >&2; }

disk_ok() {
  local kb in; kb=$(df -Pk "$OUT" | awk 'NR==2{print $4}'); in=$(df -Pi "$OUT" | awk 'NR==2{print $4}')
  [ "$kb" -ge 2000000 ] && [ "$in" -ge 60000 ] || { log "DISK-HOLD ${kb} KB, ${in} inodes"; return 1; }
}
disk_ok || exit 3

JCP="$(cd "$EVE" && clojure -Spath -A:jvm-test)"
LCP="$(cd "$EVE" && bb classpath jolt):$EVE/test"
JCWD="$(mktemp -d)"
trap 'rm -rf "$JCWD"' EXIT
NODE_JS="$EVE/target/jlog-crash/main.js"
export JCP LCP JCWD NODE_JS
if [[ "$CONFIGS" == *node* ]] && [ ! -f "$NODE_JS" ]; then
  log "building the node driver"
  (cd "$EVE" && npx shadow-cljs release jlog-crash) > "$OUT/node-build.log" 2>&1 || { log "node build failed"; exit 2; }
fi

now_ms() { date +%s%3N; }

for cfg in $CONFIGS; do
  rts=(${cfg//+/ }); [ ${#rts[@]} -eq 1 ] && rts=("${rts[0]}" "${rts[0]}")
  lead=5000; [[ " ${rts[*]} " == *" jvm "* ]] && lead=12000
  for it in $(seq 1 "$ITERS"); do
    disk_ok || exit 3
    d="$OUT/$cfg/$it"; mkdir -p "$d"; base="$d/dom"
    (run "${rts[0]}" init "$base" "$SLOTS") > "$d/init.txt" 2>&1 || { log "$cfg/$it init failed"; continue; }
    start=$(( $(now_ms) + lead ))
    pids=(); tags=(); k=0
    for rt in "${rts[@]}"; do
      tag="$rt$k"; tags+=("$tag")
      (run "$rt" writer "$base" "$tag" "$start" "$DUR_MS") > "$d/w.$tag.log" 2>&1 &
      pids+=($!); k=$((k + 1))
    done
    if (( it % 2 == 1 )); then
      mode=one; victim=$(( RANDOM % ${#pids[@]} )); at=$(( start + 500 + RANDOM % 4500 ))
      while [ "$(now_ms)" -lt "$at" ]; do sleep 0.02; done
      kill -9 "${pids[$victim]}" 2>/dev/null
      echo "one ${tags[$victim]} +$(( at - start ))" > "$d/kill.txt"
    else
      mode=all; at=$(( start + 500 + RANDOM % (DUR_MS - 1000) ))
      while [ "$(now_ms)" -lt "$at" ]; do sleep 0.02; done
      kill -9 "${pids[@]}" 2>/dev/null
      echo "all +$(( at - start ))" > "$d/kill.txt"
    fi
    for p in "${pids[@]}"; do wait "$p" 2>/dev/null; done
    [ "$mode" = all ] && (run "${rts[0]}" age "$base") > "$d/age.txt" 2>&1
    for rt in $(printf '%s\n' "${rts[@]}" | sort -u); do
      timeout 120 "$0" __run "$rt" check "$base" > "$d/check.$rt.txt" 2>&1
      echo "exit $?" >> "$d/check.$rt.txt"
    done
    [ -n "${KEEP:-}" ] || rm -rf "$base" "$base".*
    log "$cfg/$it $(cat "$d/kill.txt")"
  done
done
bb "$EVE/scripts/jlog_crash_check.clj" "$OUT" | tee "$OUT/verdict.txt"
