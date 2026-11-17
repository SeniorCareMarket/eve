#!/bin/bash
# Flat-types verification (Mac seat). Run from anywhere:
# cds to the tree root of the claude/flat-on-release checkout.
# Suites + acceptance probes; verdicts print as they land,
# full logs in /tmp/flat-verify/.
set -u
cd "$(dirname "$0")/../.."
REL=$(cat dev/flat-verify/rel.cp)
JARS=$(clojure -Sdeps '{:deps {com.cognitect/transit-clj {:mvn/version "1.0.333"} org.clojure/test.check {:mvn/version "1.1.1"}}}' -Spath 2>/dev/null)
CP="$REL:$JARS"
J="java -Xmx2g --add-opens java.base/java.lang=ALL-UNNAMED --enable-native-access=ALL-UNNAMED"
mkdir -p /tmp/flat-verify
echo "=== SUITES ==="
$J -Deve.test.ns="eve.flat.chunk-test,eve.flat.dataset-batch-test,eve.tally-test,eve.flat.stream-test,eve.flat.sister-test,eve.flat.transfer-test" -cp "$CP" clojure.main dev/flat-verify/run_tests.clj > /tmp/flat-verify/suites.out 2>&1
grep -E ":RESULT|FAIL in|ERROR in" /tmp/flat-verify/suites.out | head -10
for p in probe_genmethods probe_packnest2 probe_embed probe_funnel probe_transit probe_subtree probe_census2; do
  echo "=== $p ==="
  $J -cp "$CP" clojure.main dev/flat-verify/$p.clj > /tmp/flat-verify/$p.out 2>&1
  grep -E ":gen|:packed|:embed|:widget|:array|:dataset|:swapped|:transit|:root|:nested|:sibling|:subtree|:classes|:R \{|:N \{|:N2 \{|:V \{|:DONE" /tmp/flat-verify/$p.out | cut -c1-340 | head -14
  grep -E "Syntax error|Execution error" /tmp/flat-verify/$p.out | head -2
done
echo "=== MAC-VERIFY-END ==="
