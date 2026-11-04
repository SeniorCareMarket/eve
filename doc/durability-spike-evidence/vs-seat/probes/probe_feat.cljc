(ns probe-feat)
(defn branch [] #?(:clj :took-clj :cljs :took-cljs :vs :took-vs :default :took-default))
(defn branch2 [] #?(:clj :took-clj :default :took-default))
(defn branch3 [] #?(:cljs :took-cljs :default :took-default))
