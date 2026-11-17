#!/bin/bash
# Flat S2b acceptance + swap matrix + canonical perf rows.
# Usage: run-mac.sh [all|chain|matrix|perf]   (default: all)
# Runs from the tree root (this script lives in dev/flat-s2b).
# Every step logs into dev/flat-s2b/results/; a step's failure
# never stops the later steps; at the end the logs are printed
# in full (minus compiler-warning noise) — paste it all back.
set -u
STEP="${1:-all}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT" || exit 1
RES="dev/flat-s2b/results"
mkdir -p "$RES"
echo "== versions =="
java -version 2>&1 | head -1
clojure --version 2>&1 | head -1
echo "== building classpath =="
BASE_CP="$(clojure -Spath 2>/dev/null)"
TRANSIT='{:deps {com.cognitect/transit-clj {:mvn/version "1.0.333"} com.cognitect/transit-java {:mvn/version "1.0.371"}}}'
TEST_CP="$(clojure -Sdeps "$TRANSIT" -Spath 2>/dev/null)"
CP="$BASE_CP:test"
PCP="$TEST_CP:test"
JFLAGS="--add-opens java.base/java.lang=ALL-UNNAMED --enable-native-access=ALL-UNNAMED"
LOGS=""
if [ "$STEP" = "all" ] || [ "$STEP" = "chain" ]; then
  echo "== chain acceptance probe =="
  java -Xmx2g $JFLAGS -cp "$CP" clojure.main dev/flat-s2b/probe_chain.clj \
    > "$RES/chain.log" 2>&1
  echo "chain exit: $?"
  LOGS="$LOGS chain"
fi
if [ "$STEP" = "all" ] || [ "$STEP" = "matrix" ]; then
  echo "== swap matrix (single interleaved session) =="
  java -Xmx2g $JFLAGS -cp "$CP" clojure.main dev/flat-s2b/probe_matrix.clj \
    > "$RES/matrix.log" 2>&1
  echo "matrix exit: $?"
  LOGS="$LOGS matrix"
fi
if [ "$STEP" = "all" ] || [ "$STEP" = "perf" ]; then
  echo "== perf suite: records, main battery =="
  java -Xmx2g $JFLAGS -cp "$PCP" clojure.main -m eve.flat.perf-suite --runs 1 --only records-10k \
    > "$RES/perf-records.log" 2>&1
  echo "records exit: $?"
  echo "== perf suite: records, heap battery =="
  java -Xmx2g $JFLAGS -cp "$PCP" clojure.main -m eve.flat.perf-suite --runs 1 --only records-10k --heap \
    > "$RES/perf-records-heap.log" 2>&1
  echo "records-heap exit: $?"
  echo "== perf suite: messages, main battery =="
  java -Xmx2g $JFLAGS -cp "$PCP" clojure.main -m eve.flat.perf-suite --runs 1 --only messages-5k \
    > "$RES/perf-messages.log" 2>&1
  echo "messages exit: $?"
  echo "== perf suite: messages, heap battery =="
  java -Xmx2g $JFLAGS -cp "$PCP" clojure.main -m eve.flat.perf-suite --runs 1 --only messages-5k --heap \
    > "$RES/perf-messages-heap.log" 2>&1
  echo "messages-heap exit: $?"
  LOGS="$LOGS perf-records perf-records-heap perf-messages perf-messages-heap"
fi
echo "================ RESULTS (paste from here) ================"
for f in $LOGS; do
  echo "===== $f.log"
  grep -avE "Reflection warning|^WARNING:" "$RES/$f.log" | tail -120
done
echo "================ END RESULTS ================"
