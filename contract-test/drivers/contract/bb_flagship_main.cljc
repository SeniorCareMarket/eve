(ns contract.bb-flagship-main
  "Flagship entry point for the Babashka seat: Eve's bb completion must
   load before the port's namespaces analyze (the eve.runner.<host>-main
   pattern, after contract.ds-host-main). Measured fs3run8: entering
   contract.ds-flagship directly on bb dies loading datascript.conn —
   NullPointerException at sci.impl.deftype/analyze-deftype*
   (RT.intCast) — because the completion's deftype presentation is not
   yet interned; with eve.host-completion required first, the whole
   port loads. The scenario itself (contract.ds-flagship) is unchanged;
   this wrapper is seat entry only, as contract.flagship-main is for
   the jank/nbb/jolt seats."
  (:require [eve.host-completion]
            [contract.ds-flagship :as flagship]
            [contract.ds-window :as window]))

(defn -main [& args]
  ;; fs3run8 window modes (seed/go/window/counters) dispatch to
  ;; contract.ds-window; the classic rounds stay on contract.ds-flagship.
  (if (#{"seed" "go" "window" "counters"} (first args))
    (apply window/-main args)
    (apply flagship/-main args)))
