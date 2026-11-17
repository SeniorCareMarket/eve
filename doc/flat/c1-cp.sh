#!/bin/bash
# Classpath for lane C1 work in THIS tree: eve's JVM paths (deps.edn :paths) +
# test + the research probes (corpora) + jars from the seeded local repo.
EVE=/home/hatch/workspace/static-eve/c1
M2=/home/hatch/m2repo
PATHS="src adapters/slab-io/protocol/src adapters/http/java/src adapters/http-server/jdk/src adapters/crypto/java/src adapters/core/clojure/src adapters/completion/clojure/src adapters/coll/jvm/src adapters/slab/heap/src adapters/region/jvm-varhandle/src adapters/encoding/java/src adapters/int32/jvm/src adapters/codec/byte-array/src adapters/process/java/src adapters/thread/jvm/src adapters/types/jvm/src adapters/types/js/src adapters/journal-file/java/src test doc/static/probes"
CP=""
for p in $PATHS; do CP="$CP:$EVE/$p"; done
for j in \
  $M2/org/clojure/clojure/1.12.0/clojure-1.12.0.jar \
  $M2/org/clojure/spec.alpha/0.5.238/spec.alpha-0.5.238.jar \
  $M2/org/clojure/core.specs.alpha/0.4.74/core.specs.alpha-0.4.74.jar \
  $M2/org/clojure/test.check/1.1.1/test.check-1.1.1.jar \
  $M2/com/cognitect/transit-clj/1.0.333/transit-clj-1.0.333.jar \
  $M2/com/cognitect/transit-java/1.0.371/transit-java-1.0.371.jar \
  $M2/org/msgpack/msgpack/0.6.12/msgpack-0.6.12.jar \
  $M2/org/javassist/javassist/3.18.1-GA/javassist-3.18.1-GA.jar \
  $M2/com/fasterxml/jackson/core/jackson-core/2.8.7/jackson-core-2.8.7.jar ; do
  CP="$CP:$j"
done
echo "${CP#:}"
