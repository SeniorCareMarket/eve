;; P3c — the copy-out, written tight (research probe). P3's copier is Clojure with boxing on its hot path and came
;; out level with eve->clj; that says nothing about the mechanism. Here the same walk is a small Java class
;; (doc/static/probes/java/staticprobe/ImageCopier.java) reading the heap regions directly, block sizes derived
;; from each block's own bytes. Same session, same data:
;;
;;   slab -> plain            eve->clj (the incumbent materialization)
;;   slab -> image            the relocating copy, Clojure prototype vs the tight one
;;   image -> slab            one run claimed per size class
;;   plain -> slab            the direct writer into the slab (the incumbent store), vs
;;   plain -> bump -> image -> slab   build off-allocator in an append-only buffer, compact, place
;;
;; Run (from /home/user/eve):
;;   javac -d <classes> doc/static/probes/java/staticprobe/ImageCopier.java
;;   EXTRA_CP=<classes> java -Xmx6g -cp "$(doc/static/probes/eve-cp.sh)" clojure.main doc/static/probes/p3c_copier.clj
(load-file "doc/static/probes/lib.clj")
(load-file "doc/static/probes/corpora.clj")
(ns p3c-copier
  (:require [static-probe.lib :as L :refer [bench]]
            [static-probe.corpora :as C]
            [eve.deftype-proto.alloc :as alloc]
            [eve.deftype-proto.data :as d]
            [eve.mem :as mem])
  (:import [staticprobe ImageCopier]
           [eve.deftype_proto.alloc JvmSlabCtx]))

(println "P3c — eve" (clojure.string/trim (slurp ".git/HEAD")) "| clojure" (clojure-version)
         "| java" (System/getProperty "java.version"))

(defn- as-image [^ImageCopier c root]
  (L/->Image (.-img c) (.-len c) root (.-boff c) (.-bsz c) (.-nb c) (.-rpos c) (.-rown c) (.-rtgt c) (.-nr c)))

(defn slab-image
  "The image of the value at root of heap domain ctx, by the tight copier."
  [^JvmSlabCtx ctx root]
  (let [regions ^objects (.-regions ctx)
        reg (into-array (Class/forName "[B") (map (fn [i] (mem/-heap-bytes (aget regions i))) (range 7)))
        c (ImageCopier. reg (.-data_offsets ctx) (.-block_sizes ctx))]
    (as-image c (.run c (unchecked-int root)))))

(defn bump-image
  "The image of the value at root of an append-only buffer (its live blocks only: what the writer superseded while
   building stays behind)."
  [bs root]
  (let [reg (into-array (Class/forName "[B") [(.buf ^static_probe.lib.IBump bs)])
        c (ImageCopier. reg (int-array [0]) (int-array [1]))]
    (as-image c (.run c (unchecked-int root)))))

(def corpora
  [["map 10k int->int"        (zipmap (range 10000) (range 10000))]
   ["vector 100k ints"        (vec (range 100000))]
   ["vector 10k records"      (mapv (fn [i] {:id i :name (str "user-" i) :active (even? i) :score (* 1.5 i)}) (range 10000))]
   ["map 1k kw->vec(20 ints)" (into {} (map (fn [i] [(keyword (str "k" i)) (vec (range 20))]) (range 1000)))]
   ["5k chat messages"        (C/messages 5000)]])

(defn row [label & kvs] (println (format "    %-58s %s" label (apply str kvs))))
(defn fmt [{:keys [min med]}] (format "%9.2f ms (med %9.2f)" min med))

(doseq [[label v] corpora]
  (println)
  (println "==" label)
  (let [bs0 (L/bump-sio (* 8 1024 1024) true)
        im0 (L/image! bs0 (L/build! bs0 v) (fn [o] (L/size-of bs0 o)))
        caps (L/caps-for im0 3)
        ctx (L/slab-domain caps)
        rs (L/rec-sio ctx)
        soff (L/build! rs v)
        slab-v (L/reopen ctx soff)
        im-clj (L/image! ctx soff (fn [o] (L/size-of rs o)))
        im (slab-image ctx soff)]
    (assert (= v (d/eve->clj (L/image-view im))) "tight image differs from the value")
    (assert (= (:nblocks im) (:nblocks im-clj)) "the two copiers disagree on the block count")
    (assert (= (:len im) (:len im-clj)) "the two copiers disagree on the image size")
    (row "image" (format "%,d B, %,d blocks, %,d pointers (both copiers agree)" (:len im) (:nblocks im) (:nrel im)))
    (let [r-plain (bench #(d/eve->clj slab-v))
          r-clj (bench #(L/image! ctx soff (fn [o] (L/size-of rs o))))
          r-java (bench 5 9 #(slab-image ctx soff))]
      (row "slab -> plain (eve->clj)" (fmt r-plain))
      (row "slab -> image, Clojure prototype" (fmt r-clj))
      (row "slab -> image, tight" (fmt r-java)
           (format "   = %.0f ns/block, %.1fx eve->clj" (/ (* 1e6 (:min r-java)) (:nblocks im)) (/ (:min r-plain) (:min r-java)))))
    (let [r-dom (bench #(L/slab-domain caps))
          r-place (bench #(L/ingest! (L/slab-domain caps) im :runs))
          r-build (bench #(L/build! (L/slab-domain caps) v))
          r-via (bench #(let [bs (L/bump-sio (* 32 1024 1024) false)
                              im2 (bump-image bs (L/build! bs v))]
                          (L/ingest! (L/slab-domain caps) im2 :runs)))
          ;; correctness of the off-allocator path
          bs (L/bump-sio (* 32 1024 1024) false)
          im2 (bump-image bs (L/build! bs v))
          tgt (L/slab-domain caps)
          off (L/ingest! tgt im2 :runs)]
      (assert (= v (d/eve->clj (L/reopen tgt off))) "the off-allocator path's value differs")
      (assert (= (:nblocks im2) (:nblocks im)) "the bump image holds a different block count")
      (row "(a pre-sized domain alone)" (fmt r-dom))
      (row "image -> slab, one run per class (incl. the domain)" (fmt r-place))
      (row "plain -> slab, the direct writer (incl. the domain)" (fmt r-build))
      (row "plain -> bump -> image -> slab by runs (incl. the domain)" (fmt r-via)
           (format "   = %.2fx the direct writer" (/ (:min r-via) (:min r-build)))))))

(println)
(println "P3c done")
(System/exit 0)
