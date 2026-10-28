(ns reqatom-main
  (:require [eve.atom :as atom]))
(println :loaded (some? atom/persistent-atom-domain))
