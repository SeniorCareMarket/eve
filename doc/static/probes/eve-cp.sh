#!/bin/bash
# Print the JVM classpath for eve probes: eve's deps.edn :paths (hosts.edn :jvm) + test + Clojure jars.
EVE=/home/user/eve
J=${EVE_PROBE_JARS:-/tmp/claude-0/-home-user/b132a92e-e24c-5e30-ae79-571f5391deda/scratchpad/jars}
PATHS="src adapters/slab-io/protocol/src adapters/http/java/src adapters/http-server/jdk/src adapters/crypto/java/src adapters/core/clojure/src adapters/completion/clojure/src adapters/coll/jvm/src adapters/slab/heap/src adapters/region/jvm-varhandle/src adapters/encoding/java/src adapters/int32/jvm/src adapters/codec/byte-array/src adapters/process/java/src adapters/thread/jvm/src adapters/types/jvm/src adapters/types/js/src adapters/journal-file/java/src test"
CP=""
for p in $PATHS; do CP="$CP:$EVE/$p"; done
for j in $J/*.jar; do CP="$CP:$j"; done
echo "${CP#:}${EXTRA_CP:+:$EXTRA_CP}"
