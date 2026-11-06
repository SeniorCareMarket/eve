#!/usr/bin/env bash
# Durability spike — contention driver (2026-10-27).
#   run-contention.sh <eve-tree> <variant A|B|C> <procs> <swaps-per-proc> <sync-every> <tag>
# Creates the shared domain with the tree's own code, starts <procs> JVM
# worker processes on ONE shared disk atom (counter), releases them with a
# .go file once all are ready, and prints: per-worker loop elapsed ms, the
# makespan (max elapsed), aggregate ops/sec over the swap phases, and the
# final counter read back (must equal procs*swaps).
set -u
TREE="$1"; VARIANT="$2"; PROCS="$3"; N="$4"; SYNCE="$5"; TAG="$6"
export CLJ_CONFIG=~/workspace/eve-env/cljconfig
export JAVA_HOME=~/opt/jdk-21.0.7+6-jre
export PATH=~/opt/jdk-21.0.7+6-jre/bin:~/opt/clojure-tools/bin:$PATH
export BENCH_DIR=${BENCH_DIR:-$TREE/target/durability-bench-runs}
DIR="$BENCH_DIR/cont-$TAG"
CLJ="clojure -J--add-opens -Jjava.base/java.lang=ALL-UNNAMED -J--enable-native-access=ALL-UNNAMED -Sdeps {:aliases{:bench{:extra-paths[\"bench\"]}}} -M:bench -m eve.durability-bench"
rm -f "$DIR.go" "$DIR".ready-* "$DIR".elapsed-*
(cd "$TREE" && $CLJ cinit "$DIR") || { echo "CINIT-FAILED $TAG"; exit 1; }
pids=()
for i in $(seq 1 "$PROCS"); do
  (cd "$TREE" && $CLJ cworker "$DIR" "$VARIANT" "$N" "$SYNCE" "$DIR.elapsed-$i") > "$DIR.worker-$i.log" 2>&1 &
  pids+=($!)
done
# release the workers together once every one has joined and warmed
for i in $(seq 1 300); do
  ready=$(ls "$DIR".ready-* 2>/dev/null | wc -l)
  [ "$ready" -ge "$PROCS" ] && break
  sleep 1
done
touch "$DIR.go"
for p in "${pids[@]}"; do wait "$p"; done
echo "CONTENTION variant=$VARIANT tag=$TAG procs=$PROCS swaps-per-proc=$N sync-every=$SYNCE"
mx=0
for i in $(seq 1 "$PROCS"); do
  e=$(cat "$DIR.elapsed-$i" 2>/dev/null || echo MISSING)
  echo "  worker-$i elapsed-ms=$e"
  case "$e" in MISSING) ;; *) awk "BEGIN{exit !($e>$mx)}" && mx=$e ;; esac
done
awk "BEGIN{printf \"  makespan-ms=%s aggregate-ops-sec=%.1f aggregate-us-per-op=%.1f\n\", $mx, ($PROCS*$N)/($mx/1000), ($mx*1000)/($PROCS*$N)}"
(cd "$TREE" && $CLJ cread "$DIR")
