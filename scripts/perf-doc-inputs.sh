#!/usr/bin/env bash
# perf-doc-inputs.sh — produce, in one run, every input the 0.9.3.2
# doc/performance.md rebuild still needs from this machine, into
# perf-doc-out/ :
#
#   01-test-compile.txt   npm run test:compile — the CLJS build verdict
#                         (exit code in the summary; full log in the file)
#   02-perf-all.txt       bb scripts/perf-all.clj cljs lpy cljr — the three
#                         hosts whose perf sections the doc still lacks,
#                         plus the merged full table perf-all prints from
#                         doc/hosts/perf-results.edn (header + every row)
#   03-shootout-jvm.txt   the section 2 startup shootout on the JVM seat:
#                         contract `one` (cold process, fresh journaled
#                         domain, one datum, ONE-DONE checked) x5, shell
#                         wall clock, median printed
#   04-ds-runtime.txt     NOT produced here, deliberately: section 1's
#                         Eve-DataScript vs stock tables come from the
#                         bench driver bench/contract/ds_bench.cljc,
#                         which is NOT in this tree (it lives with the
#                         DataScript lanes; sha256
#                         7ef8a26485767732d3e20cad0555920f042df6e133bacaafb522154ebfaa9b45
#                         in ds-perf-final/RESULTS-FINAL.md). Numbers from
#                         any other driver would not compare with the
#                         tables already in the doc, so this script does
#                         not invent one; the file says so.
#
# Every section runs even if an earlier one fails. The summary at the
# end reports each section's verdict as measured — a failed section is
# FAILED, never folded into an OK. perf-all exits 0 even when a host
# fails, so its per-host lines are checked, not its exit code.
set -u
cd "$(dirname "$0")/.."
OUT=perf-doc-out
mkdir -p "$OUT"
FAIL=0

echo "perf-doc-inputs: $(uname -sm), $(date)"

echo
echo "== 01 test:compile =="
if npm run test:compile > "$OUT/01-test-compile.txt" 2>&1; then
  echo "test:compile: OK (log: $OUT/01-test-compile.txt)"
else
  echo "test:compile: FAILED (log: $OUT/01-test-compile.txt)"
  FAIL=1
fi
tail -2 "$OUT/01-test-compile.txt"

echo
echo "== 02 perf-all cljs lpy cljr =="
bb scripts/perf-all.clj cljs lpy cljr > "$OUT/02-perf-all.txt" 2>&1
for h in cljs lpy cljr; do
  line=$(grep "^== $h: " "$OUT/02-perf-all.txt" || true)
  if echo "$line" | grep -q ", exit 0," && ! echo "$line" | grep -q ": 0 workloads"; then
    echo "perf-all $h: OK — $line"
  else
    echo "perf-all $h: FAILED — ${line:-no result line} (log: $OUT/02-perf-all.txt)"
    FAIL=1
  fi
done
grep "^| " "$OUT/02-perf-all.txt" || true

echo
echo "== 03 shootout: contract one, JVM, x5 cold =="
WALLS="$OUT/.one-walls"
: > "$WALLS"
: > "$OUT/03-shootout-jvm.txt"
for i in 1 2 3 4 5; do
  d=$(mktemp -d)
  s=$(python3 -c 'import time; print(time.time())')
  clojure -Sdeps '{:aliases {:contract {:extra-paths ["contract-test/lib-ds" "contract-test/lib" "contract-test/lib-ds-deps" "contract-test/drivers"]}}}' \
    -M:contract -m contract.one "$d/db" > "$OUT/.one-run.txt" 2>&1
  rc=$?
  e=$(python3 -c 'import time; print(time.time())')
  w=$(python3 -c "print(f'{$e - $s:.3f}')")
  echo "run $i: wall $w s, exit $rc" >> "$OUT/03-shootout-jvm.txt"
  cat "$OUT/.one-run.txt" >> "$OUT/03-shootout-jvm.txt"
  if [ $rc -eq 0 ] && grep -q "ONE-DONE" "$OUT/.one-run.txt"; then
    echo "$w" >> "$WALLS"
  else
    echo "run $i did not print ONE-DONE" >> "$OUT/03-shootout-jvm.txt"
    FAIL=1
  fi
  rm -rf "$d"
done
rm -f "$OUT/.one-run.txt"
if [ -s "$WALLS" ]; then
  med=$(python3 -c "
import statistics
print(f'{statistics.median([float(l) for l in open(\"$WALLS\") if l.strip()]):.3f}')")
  echo "shootout jvm: median wall $med s over $(grep -c . "$WALLS")/5 ONE-DONE runs (log: $OUT/03-shootout-jvm.txt)"
  echo "median wall: $med s" >> "$OUT/03-shootout-jvm.txt"
else
  echo "shootout jvm: FAILED — no ONE-DONE runs (log: $OUT/03-shootout-jvm.txt)"
  FAIL=1
fi
rm -f "$WALLS"

echo
echo "== 04 ds runtime =="
cat > "$OUT/04-ds-runtime.txt" <<'NOTE'
NOT PRODUCED by perf-doc-inputs.sh: doc/performance.md section 1
(Eve-DataScript vs stock DataScript) is measured with the bench driver
bench/contract/ds_bench.cljc, which is not in this tree — it lives
with the DataScript lanes (sha256
7ef8a26485767732d3e20cad0555920f042df6e133bacaafb522154ebfaa9b45,
ds-perf-final/RESULTS-FINAL.md). A substitute driver would produce
numbers that do not compare with the tables already in the doc.
NOTE
cat "$OUT/04-ds-runtime.txt"

echo
if [ $FAIL -eq 0 ]; then
  echo "PERF-DOC-INPUTS: OK (outputs in $OUT/)"
else
  echo "PERF-DOC-INPUTS: FAILED (sections marked FAILED above; outputs in $OUT/)"
fi
exit $FAIL
