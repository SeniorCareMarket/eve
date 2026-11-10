(ns contract.translate
  "Build-time driver of the compiled-jank lane: the completion first (the
   launcher pattern), then eve.host-completion-translate over a manifest of
   <input>\\t<output> pairs (scripts/compiled-jank/stage-translate.sh writes it)."
  (:require [eve.host-completion]
            [eve.host-completion-translate :as t]))

(defn -main [& args]
  (t/run (first args)))
