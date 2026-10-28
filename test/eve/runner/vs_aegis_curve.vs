(ns eve.runner.vs-aegis-curve
  "EVE on ValueScript: the aegis hash-to-curve and ring-signature suites (no RSA vector namespaces),
   compiled and run as test/eve/runner/vs_aegis_main.vs is."
  (:require [clojure.test]
            [eve.aegis.h2c-test]
            [eve.aegis.clsag-test]))
