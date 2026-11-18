#!/usr/bin/env bash
# Full-cljc stage proof runner (hatch recipe, stage 2's run.sh shape):
# the two-reader check over the converted cohost-media files.
#
#   capstone/cohost-media/dev/fullcljc/run.sh
#
# The runner deps live in /tmp (ephemeral by design); the scripts are
# the durable part and live beside this file. Maven resolves offline
# from /home/hatch/m2repo via the release-smokes CLJ_CONFIG.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
CAP="$(cd "$HERE/../.." && pwd)"
RUN=/tmp/fullcljc-run
mkdir -p "$RUN"
cat > "$RUN/deps.edn" <<EOF
{:paths ["$HERE"]
 :deps {org.clojure/clojure {:mvn/version "1.12.0"}
        org.clojure/clojurescript {:mvn/version "1.12.134"}}}
EOF
cp "$HERE/reader_check.clj" "$RUN/reader_check.clj"
cd "$RUN"
CLJ_CONFIG=/home/hatch/workspace/release-smokes/cljconfig \
JAVA_HOME=/home/hatch/opt/jdk-25.0.5+8 \
PATH=/home/hatch/opt/jdk-25.0.5+8/bin:/home/hatch/.local/bin:$PATH \
timeout 300 clojure -M reader_check.clj \
  "$CAP/src/cohost_media/boot.cljc" \
  "$CAP/src/cohost_media/start.cljc" \
  "$CAP/src/cohost_media/boot_fs.cljc" \
  "$CAP/src/cohost_media/boot_proc.cljc" \
  "$CAP/src/cohost_media/gateway.cljc" \
  "$CAP/src/cohost_media/meta.cljc" \
  "$CAP/src/cohost_media/av_jolt.cljc" \
  "$CAP/src/cohost_media/member.cljc"
