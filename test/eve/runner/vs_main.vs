(ns eve.runner.vs-main
  "EVE on ValueScript: the shared (.cljc) tests that compile there, and the slab-io feature's conformance test
   (adapters/slab-io/test), compiled by the installed vs over hosts.edn's :vs roots and run under wasmtime:

     bb test:vs

   A VS deftest runs as its namespace loads (VS's src/vs/macros.vs, M08), and the program ends printing
   `N passed, M failed` (VS's src/vs/kernel/wasm/vs1_clojure_test.vs), which the task reads. The shared tests not listed
   here are built on eve/deftype (the collections, eve.atom), which VS expands once its macros can call a program's
   functions."
  (:require [clojure.test]
            [eve.hamt-util-test]
            [eve.eve-bytes-fixture-test]
            [eve.slab-io-test]))
