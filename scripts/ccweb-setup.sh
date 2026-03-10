#!/bin/bash
# ccweb-setup.sh — Bootstrap Clojure/shadow-cljs environment for Claude Code web sessions.
# Called by .claude/hooks/session-start.sh.
#
# Installs Clojure CLI (if missing), downloads deps, builds native addon,
# and provides a shadow-compile convenience function.
set -euo pipefail

PROJECT_DIR="${CLAUDE_PROJECT_DIR:-/home/user/eve}"

# ── 1. Install Clojure CLI if missing ─────────────────────────────────────
if ! command -v clojure &>/dev/null; then
  echo "[ccweb-setup] Installing Clojure CLI..."
  curl -sL https://download.clojure.org/install/linux-install-1.12.0.1530.sh | bash
fi

# ── 2. Download Clojure deps (maven cache) ────────────────────────────────
echo "[ccweb-setup] Downloading Clojure deps..."
cd "$PROJECT_DIR"
clojure -P -M:jvm-test 2>/dev/null || true
clojure -P -M:node-test 2>/dev/null || true

# ── 3. npm install (native addon + shadow-cljs) ───────────────────────────
if [ ! -d "$PROJECT_DIR/node_modules" ]; then
  echo "[ccweb-setup] Running npm install..."
  cd "$PROJECT_DIR" && npm install 2>/dev/null || true
fi

# ── 4. Build native addon if needed ───────────────────────────────────────
if [ ! -f "$PROJECT_DIR/build/Release/mmap_cas.node" ] && \
   [ ! -f "$PROJECT_DIR/prebuilds/linux-x64/node.napi.node" ]; then
  echo "[ccweb-setup] Building native addon..."
  cd "$PROJECT_DIR" && npm run build:addon 2>/dev/null || true
fi

# ── 5. Generate classpath.edn for shadow-cljs ─────────────────────────────
CLASSPATH_FILE="$PROJECT_DIR/classpath.edn"
if [ ! -f "$CLASSPATH_FILE" ]; then
  echo "[ccweb-setup] Generating classpath.edn..."
  CP=$(cd "$PROJECT_DIR" && clojure -M:node-test -Spath 2>/dev/null || echo "")
  if [ -n "$CP" ]; then
    echo "{:classpath \"$CP\"}" > "$CLASSPATH_FILE"
  fi
fi

# ── 6. Convenience function: shadow-compile ────────────────────────────────
# Use `shadow-compile eve-test` instead of `npx shadow-cljs compile eve-test`
# to avoid Pomegranate/Aether DNS resolution through proxy.
shadow-compile() {
  local build="${1:-eve-test}"
  local cp
  cp=$(cd "$PROJECT_DIR" && clojure -M:node-test -Spath)
  java -cp "$cp" clojure.main -m shadow.cljs.devtools.cli compile "$build"
}

echo "[ccweb-setup] Clojure environment ready."
