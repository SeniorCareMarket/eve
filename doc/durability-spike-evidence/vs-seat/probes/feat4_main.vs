(ns feat4-main
  (:require [probe-feat4 :as pf]))
(println :vsonly (pf/vsonly))
(println :vsfirst (pf/vsfirst))
(println :cljfirst (pf/cljfirst))
