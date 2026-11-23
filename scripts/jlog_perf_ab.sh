#!/usr/bin/env bash
# jlog_perf_ab.sh — the durable-commit cost before and after, per runtime: eve's
# own perf-suite workload :disk-swap-counter (test/eve/perf_suite.cljc: one swap!
# on a persistent, journaled atom; measured as the suite measures it — doubling
# rounds until one takes its 1 s budget) on the BASE tree and the HEAD tree,
# interleaved in PAIRS pairs (the pair's order alternates: AB, BA, ...), one
# process at a time.
#
#   scripts/jlog_perf_ab.sh <base-tree> <head-tree>
#
# Env: RUNTIMES (default "jvm bb jolt node") · PAIRS (default 5) · OUT (default
# /tmp/eve-jlog-perf/<stamp>). Node runs the compiled eve-test bundle of each tree
# (target/eve-test/all.js perf: the whole perf suite; the disk-swap line is kept).
# Prints one line per measurement and a median table (ns/op).
set -uo pipefail
BASE="$(cd "${1:?base tree}" && pwd)"; HEAD="$(cd "${2:?head tree}" && pwd)"
RUNTIMES="${RUNTIMES:-jvm bb jolt node}"
PAIRS="${PAIRS:-5}"
OUT="${OUT:-/tmp/eve-jlog-perf/$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$OUT"
EXPR='(require (quote eve.perf-suite)) (eve.perf-suite/run! {:only #{:disk-swap-counter} :dir "DIR"})'
declare -A JCP LCP BCP
for t in "$BASE" "$HEAD"; do
  JCP[$t]="$(cd "$t" && clojure -Spath -A:jvm-test | tr ':' '\n' | sed "s#^\([^/]\)#$t/\1#" | paste -sd:)"
  LCP[$t]="$(cd "$t" && bb classpath jolt):$t/test"
  BCP[$t]="$(cd "$t" && bb classpath bb):$t/test"
done
one() { # tree rt -> the :disk-swap-counter EDN line
  local t="$1" rt="$2" dir; dir="$OUT/dom/$(basename "$t")-$rt-$RANDOM"; mkdir -p "$dir"
  local e="${EXPR//DIR/$dir}"
  case "$rt" in
    jvm) (cd "$t" && java --add-opens java.base/java.lang=ALL-UNNAMED --enable-native-access=ALL-UNNAMED \
           -cp "${JCP[$t]}" clojure.main -e "$e (System/exit 0)") ;;
    bb) (cd "$t" && bb -cp "${BCP[$t]}" -e "$e") ;;
    jolt) (cd "$(mktemp -d)" && jolt -Scp "${LCP[$t]}" -e "$e") ;;
    node) (cd "$t" && EVE_MMAP_ADDON="$t/build/Release/mmap_cas.node" node target/eve-test/all.js perf) ;;
  esac 2>&1 | grep -E '^\{:perf :disk-swap-counter' | tail -1
  rm -rf "$dir"
}
for i in $(seq 1 "$PAIRS"); do
  if (( i % 2 == 1 )); then order=("$BASE" "$HEAD"); else order=("$HEAD" "$BASE"); fi
  for rt in $RUNTIMES; do
    for t in "${order[@]}"; do
      tag=base; [ "$t" = "$HEAD" ] && tag=head
      line="$(one "$t" "$rt")"
      echo "$i $rt $tag $line" | tee -a "$OUT/raw.txt"
    done
  done
done
bb -e '
(require (quote [clojure.string :as str]))
(let [rows (for [l (str/split-lines (slurp (first *command-line-args*)))
                 :let [[i rt tag & more] (str/split l #" " 4)
                       ns (some->> (re-find #":ns-per-op (\d+)" (or (first more) "")) second parse-long)]
                 :when ns] {:rt rt :tag tag :ns ns})
      med (fn [xs] (let [v (vec (sort xs))] (when (seq v) (nth v (quot (count v) 2)))))]
  (println "runtime   base-median-ns  head-median-ns  head/base")
  (doseq [rt (distinct (map :rt rows))]
    (let [b (med (map :ns (filter #(= [rt "base"] [(:rt %) (:tag %)]) rows)))
          h (med (map :ns (filter #(= [rt "head"] [(:rt %) (:tag %)]) rows)))]
      (println (format "%-8s %15s %15s %10s" rt b h (if (and b h) (format "%.2f" (/ (double h) b)) "-"))))))' "$OUT/raw.txt" | tee "$OUT/summary.txt"
