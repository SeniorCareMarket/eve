#!/bin/bash
# Ants jank seat, stage (a): two jank processes, one shared world.
# Process 1 creates the world, places ants, behaves; process 2
# reopens the same world cold. The printed digests (snapshot +
# registry + STM clock, hashed in one transaction) must be EQUAL:
# every value process 1 committed is read back exactly by process 2.
# Usage: ANTS_ROOT=<checkout> bash run_jank_probe.sh
set -u
ROOT=${ANTS_ROOT:?set ANTS_ROOT (the checkout)}
EVE=$ROOT/eve
STM_ANTS=$ROOT/docs/stm-ants
MP="$EVE/src:$EVE/adapters/slab-io/jank/src:$EVE/adapters/core/clojure/src:$EVE/adapters/completion/jank/src:$EVE/adapters/coll/jank/src:$EVE/adapters/slab/heap/src:$EVE/adapters/region/jank/src:$EVE/adapters/typed-view/jank/src:$EVE/adapters/encoding/jank/src:$EVE/adapters/int32/jank/src:$EVE/adapters/codec/byte-array/src:$EVE/adapters/process/jank/src:$EVE/adapters/thread/none/src:$EVE/adapters/types/jank/src:$EVE/adapters/crypto/jank/src:$EVE/adapters/http/jank/src:$EVE/adapters/http-server/jank/src:$EVE/adapters/journal-file/unsupported/src:$ROOT/docs/stm-eve:$STM_ANTS:$STM_ANTS/hosts/jank"
JANK=${JANK:-/usr/local/bin/jank}
WORK=${ANTS_PROBE_WORK:-/home/john/j/ants-jank-probe}
rm -rf "$WORK"; mkdir -p "$WORK/create" "$WORK/reopen"
WORLD="$WORK/world"
printf '{:mode :create :root "%s" :dim 8}\n' "$WORLD" > "$WORK/create/probe-config.edn"
printf '{:mode :reopen :root "%s" :dim 8}\n' "$WORLD" > "$WORK/reopen/probe-config.edn"
echo "=== create $(date -Is) ==="
(cd "$WORK/create" && timeout "${ANTS_PROBE_TIMEOUT:-1200}" env LC_ALL=en_US.UTF-8 "$JANK" --module-path "$MP" run-main probe-jank-world) > "$WORK/create.log" 2>&1
echo "create EXIT:$?"
echo "=== reopen $(date -Is) ==="
(cd "$WORK/reopen" && timeout "${ANTS_PROBE_TIMEOUT:-1200}" env LC_ALL=en_US.UTF-8 "$JANK" --module-path "$MP" run-main probe-jank-world) > "$WORK/reopen.log" 2>&1
echo "reopen EXIT:$?"
grep -h "^PROBE-" "$WORK/create.log" "$WORK/reopen.log" || true
D1=$(grep -o "digest= -*[0-9]*" "$WORK/create.log" | head -1)
D2=$(grep -o "digest= -*[0-9]*" "$WORK/reopen.log" | head -1)
C1=$(grep -o "clock= [0-9]*" "$WORK/create.log" | head -1)
C2=$(grep -o "clock= [0-9]*" "$WORK/reopen.log" | head -1)
if [ -n "$D1" ] && [ "$D1" = "$D2" ] && [ "$C1" = "$C2" ]; then
  echo "PROBE-VERDICT: PASS (digest + clock equal across processes)"
else
  echo "PROBE-VERDICT: FAIL (create [$D1 $C1] vs reopen [$D2 $C2])"
fi
