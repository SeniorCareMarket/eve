(ns probe-feat4)
(defn vsonly [] #?(:vs :took-vs :default :took-default))
(defn vsfirst [] #?(:vs :took-vs :clj :took-clj :default :took-default))
(defn cljfirst [] #?(:clj :took-clj :vs :took-vs :default :took-default))
