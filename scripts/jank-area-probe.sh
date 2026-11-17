#!/bin/bash
# Bisect the release suite's jank leg by area: each area runs in its
# own jank process, so a native crash kills only its own probe and the
# output names the area. Suspects first (the areas the full leg has
# not yet printed OK for), then the rest as controls.
#
#   ./scripts/jank-area-probe.sh [area ...]
set -u
cd "$(dirname "$0")/.."
AREAS="${@:-thread crypto disk-atom mem-atom seams codec collections net-atom proc-io}"
for a in $AREAS; do
  echo "===== area: $a ====="
  timeout 420 bb probe:jank-area "$a" 2>&1 | grep -v "^warning:" | tail -6
  echo "----- $a rc=${PIPESTATUS[0]} -----"
done
