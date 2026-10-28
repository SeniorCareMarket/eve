(ns eve.runner.vs-aegis-main
  "EVE on ValueScript: aegis's clear-evaluation test namespaces (aegis/docs/simple-spike/REPORT.md), compiled by the installed vs over
   hosts.edn's :vs roots and run under wasmtime, as test/eve/runner/vs_main.vs runs the shared tests:
     VS_CLJ_ROOTS=src:<:vs roots>:test vs compile test/eve/runner/vs_aegis_main.vs -o target/vs/vs_aegis_main.wasm
     wasmtime run --dir . -W threads=y -W shared-memory=y -W exceptions=y -W max-wasm-stack=67108864 <wasm>
   A VS deftest runs as its namespace loads; the program ends printing `N passed, M failed`."
  (:require [clojure.test]
            [eve.aegis.merkle-test]
            [eve.aegis.h2c-test]
            [eve.aegis.rsabssa-test]
            [eve.aegis.claims-test]))
