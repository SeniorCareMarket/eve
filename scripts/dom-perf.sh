#!/usr/bin/env bash
# eve.dom's perf cells (the px-* rows of docs/eve-dom.md), each cell one page load per engine and transport.
# Builds the :eve-dom-perf page once, then for each cell x engine x transport runs REPS page loads through
# scripts/test-browser.clj (EVE_BROWSER_SUITE=dom-perf, EVE_BROWSER_NO_BUILD=1) and prints their EDN lines.
#   CELLS (default: all six), ENGINES (default: chromium firefox webkit), TRANSPORTS (default: sab sw), REPS (3),
#   OUT (a file the EDN lines are appended to), EVE_BENCH_LOCK (a lock file taken around each cell with noclobber,
#   never stolen: waits while another holds it), MIN_FREE_G (6: stop when the disk has less free).
# The lock is released only by the cell that took it (never in a trap). Each cell runs with its own TMPDIR, deleted afterwards. Firefox's sw transport is refused by design (it does not
# send a worker's sync XHR through a service worker), so that pair is skipped.
set -u
cd "$(dirname "$0")/.."
CELLS=${CELLS:-"px-get-rtt px-call-rtt px-batch-100 px-event-latency px-frame-d3 first-render"}
ENGINES=${ENGINES:-"chromium firefox webkit"}
TRANSPORTS=${TRANSPORTS:-"sab sw"}
REPS=${REPS:-3}
OUT=${OUT:-/dev/null}
MIN_FREE_G=${MIN_FREE_G:-6}
LOCK=${EVE_BENCH_LOCK:-}

free_g() { df -BG --output=avail . | tail -1 | tr -dc 0-9; }

take_lock() {
  [ -z "$LOCK" ] && return 0
  until ( set -C; printf 'eve.dom perf cell %s\npid=%s\nstarted=%s\n' "$1" "$$" "$(date '+%H:%M:%S %Z')" > "$LOCK" ) 2>/dev/null; do
    sleep 5
  done
}
release_lock() {
  [ -z "$LOCK" ] && return 0
  grep -q "^pid=$$\$" "$LOCK" 2>/dev/null && rm -f "$LOCK"
}

echo "dom-perf: building the page"
rm -f target/dom-perf/public/js/main.js
nice -n 19 clojure -M:browser-test:dom-demo -m shadow.cljs.devtools.cli release eve-dom-perf >/dev/null 2>&1
[ -f target/dom-perf/public/js/main.js ] || { echo "dom-perf: build FAILED"; exit 1; }

for cell in $CELLS; do
  for engine in $ENGINES; do
    for tr in $TRANSPORTS; do
      [ "$engine/$tr" = firefox/sw ] && continue
      f=$(free_g); [ "$f" -lt "$MIN_FREE_G" ] && { echo "dom-perf: ${f}G free < ${MIN_FREE_G}G; stopping"; exit 2; }
      tmp=$(mktemp -d "${TMPDIR:-/tmp}/eve-dom-perf.XXXXXX")
      q="eve-dom-perf=$cell"; coi=1
      [ "$tr" = sw ] && { q="eve-dom-transport=sw&$q"; coi=0; }
      take_lock "$cell $engine $tr"
      load=$(cut -d' ' -f1 /proc/loadavg 2>/dev/null)
      for rep in $(seq "$REPS"); do
        eng=EVE_BROWSER_CHROMIUM=1; [ "$engine" = chromium ] || eng="EVE_BROWSER=$engine"
        line=$(env TMPDIR="$tmp" EVE_BROWSER_SUITE=dom-perf EVE_BROWSER_NO_BUILD=1 EVE_BROWSER_QUERY="$q" \
               EVE_BROWSER_COI=$coi EVE_BROWSER_TIMEOUT_S=120 "$eng" \
               bb scripts/test-browser.clj 2>&1 | grep '^{:' | tail -1)
        rec="{:engine :$engine :want :$tr :rep $rep :load1 $load :at \"$(date '+%H:%M:%S')\" :r ${line:-nil}}"
        echo "$rec"; echo "$rec" >> "$OUT"
      done
      release_lock
      rm -rf "$tmp"
    done
  done
done
