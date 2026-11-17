;; Lane H4 — the JVM reference for jvm-node-image-same-bytes.
;; Builds {:a [1 2 3] :b {:c "x"}} by the direct writers in a fresh heap
;; domain (the same build-plain as image_test.cljc / image_node_test.cljs),
;; detaches it, and prints the image bytes as one hex line after REF:.
;; Run on the JVM:  clojure -M -i doc/flat/h4_jvm_reference.clj -e "(h4-ref/h4-ref)"
;; (the fn is h4-ref/h4-ref: -e evaluates in the user namespace, so the
;; bare "(h4-ref)" of the first filings could not resolve it — measured
;; twice on the venue, "Unable to resolve symbol: h4-ref". The test tree
;; is not needed; src alone carries every namespace used.)
(ns h4-ref
  (:require [eve.deftype-proto.alloc :as alloc]
            [eve.deftype-proto.data :as d]
            [eve.deftype-proto.serialize :as ser]
            [eve.codec :as codec]
            [eve.flat.image :as img]))

(defn h4-ref []
  (let [sio (alloc/heap-domain-sio! {})
        v (binding [d/*domain-sio* sio]
            (let [off (codec/write-collection! :map sio {:a [1 2 3] :b {:c "x"}})
                  t (ser/read-header-tag sio off)]
              ((ser/get-header-constructor t) sio off)))
        bs (img/detach v)]
    (println (str "REF: " (apply str (map (fn [b] (format "%02x" (bit-and b 0xFF))) bs))))
    (println (str "LEN: " (alength bs)))))
