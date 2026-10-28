#!/usr/bin/env bash
# the eve-swarm seat as a ClojureDart program: clojure -M:cljd init (once: the Dart project — pubspec.yaml, bin/), then
# compile (lib/cljd-out), then dart compile exe into eve/target/eve-swarm/seat-cljd — the launcher's :cljd row.
# Needs the clojure CLI and the Dart SDK (DART_SDK, default /opt/dart-sdk; dart-archive's zip, doc/hosts/).
#   capstone/eve-swarm/cljd/build.sh [init|compile|exe]   (no argument: all three, init only when not yet done)
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
eve="$(cd "$here/../../.." && pwd)"
export PATH="${DART_SDK:-/opt/dart-sdk}/bin:$PATH"
cd "$here"
step="${1:-all}"
if [ "$step" = init ] || { [ "$step" = all ] && [ ! -f pubspec.yaml ]; }; then
  clojure -M:cljd init
fi
if [ "$step" = compile ] || [ "$step" = all ]; then
  clojure -M:cljd compile
fi
if [ "$step" = exe ] || [ "$step" = all ]; then
  mkdir -p "$eve/target/eve-swarm"
  dart compile exe bin/cljd.dart -o "$eve/target/eve-swarm/seat-cljd"
fi
