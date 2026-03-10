#!/bin/bash
set -euo pipefail

# Only run in remote (web) environment
if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

# Run synchronously so deps are guaranteed before agent starts
PROJECT_DIR="${CLAUDE_PROJECT_DIR:-/home/user/eve}"
SETUP_SCRIPT="$PROJECT_DIR/scripts/ccweb-setup.sh"

if [ ! -f "$SETUP_SCRIPT" ]; then
  echo "[session-start] ERROR: $SETUP_SCRIPT not found"
  exit 1
fi

echo "[session-start] Bootstrapping Clojure/shadow-cljs environment..."

# Run the setup (downloads deps via curl, generates classpath.edn)
bash "$SETUP_SCRIPT"

# Export convenience functions for the session via CLAUDE_ENV_FILE
if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  cat >> "$CLAUDE_ENV_FILE" << 'ENVEOF'
# Shadow-cljs convenience: use shadow-compile instead of npx shadow-cljs compile
# npx shadow-cljs triggers Pomegranate/Aether dep resolution which can't resolve
# DNS through the proxy. shadow-compile uses java -cp directly.
source /home/user/eve/scripts/ccweb-setup.sh 2>/dev/null || true
ENVEOF
fi

echo "[session-start] Clojure environment ready."
