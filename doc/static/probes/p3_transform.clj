;; P3 — what the transformations cost, in one session on the same data (research probe).
;;
;; Questions: O5 / A9 (is feeding a pre-laid-out value into the allocator "placement, not planning", and what does
;; it buy), Q1 (physical packing priced), Q10 (the conversion matrix). Every arm runs here, on the same values:
;;
;;   incumbents   plain -> slab (the direct writer swap! uses), slab -> plain (eve->clj), plain <-> flat codec bytes,
;;                plain <-> Transit (JSON, MessagePack), plain <-> EDN text
;;   new          slab -> image (the relocating block copier), image read in place, image -> plain,
;;                image -> slab by per-block allocation, image -> slab by one claimed run per size class
;;
;; Run (from /home/user/eve):  java -Xmx6g -cp "$(doc/static/probes/eve-cp.sh)" clojure.main doc/static/probes/p3_transform.clj
(load-file "doc/static/probes/lib.clj")
(ns p3-transform
  (:require [static-probe.lib :as L :refer [bench]]
            [eve.deftype-proto.alloc :as alloc]
            [eve.deftype-proto.data :as d]
            [eve.codec :as codec]
            [cognitect.transit :as transit]
            [clojure.edn :as edn])
  (:import [java.io ByteArrayOutputStream ByteArrayInputStream]
           [java.util Random]))

(println "P3 — eve" (-> (slurp ".git/HEAD") clojure.string/trim) "| clojure" (clojure-version)
         "| java" (System/getProperty "java.version") "| cores" (.availableProcessors (Runtime/getRuntime))
         "| load1" (try (first (clojure.string/split (String. (.readAllBytes (.getInputStream (.start (ProcessBuilder. ["cat" "/proc/loadavg"]))))) #" "))
                        (catch Exception _ "?")))

(defn transit-bytes [v fmt]
  (let [out (ByteArrayOutputStream. 4096)]
    (transit/write (transit/writer out fmt) v)
    (.toByteArray out)))
(defn transit-read [^bytes bs fmt]
  (transit/read (transit/reader (ByteArrayInputStream. bs) fmt)))

(def rnd (Random. 42))
(defn rand-text [n]
  (let [words ["the" "quick" "atom" "swap" "value" "epoch" "slab" "domain" "static" "persistent" "vector" "map"
               "chunk" "page" "column" "row" "eve" "hello" "world" "ok" "thanks" "lgtm" "ship" "it"]]
    (clojure.string/join " " (repeatedly n #(nth words (.nextInt rnd (count words)))))))

(def corpora
  [["map 10k int->int"          (zipmap (range 10000) (range 10000))]
   ["vector 100k ints"          (vec (range 100000))]
   ["vector 10k records"        (mapv (fn [i] {:id i :name (str "user-" i) :active (even? i) :score (* 1.5 i)}) (range 10000))]
   ["map 1k kw->vec(20 ints)"   (into {} (map (fn [i] [(keyword (str "k" i)) (vec (range 20))]) (range 1000)))]
   ["5k chat messages"          (mapv (fn [i]
                                        {:id (str "m-" i)
                                         :room (nth [:general :random :dev :ops] (mod i 4))
                                         :author {:id (mod i 37) :name (str "user-" (mod i 37))}
                                         :text (rand-text (+ 3 (.nextInt rnd 25)))
                                         :ts (+ 1700000000000 (* i 1000))
                                         :tags (set (take (mod i 3) [:pinned :edited :thread]))
                                         :reactions (mapv (fn [j] {:emoji (nth [:+1 :heart :eyes] (mod j 3)) :by (mod (+ i j) 37)})
                                                          (range (mod i 4)))})
                                      (range 5000))]])

(defn row [label & kvs]
  (println (format "    %-46s %s" label (apply str kvs))))

(defn fmt-ms [{:keys [min med]}] (format "%9.2f ms (med %9.2f)" min med))

(defn elements [v]
  ;; leaf-ish element count, for per-element figures
  (cond (map? v) (count v) (coll? v) (count v) :else 1))

(doseq [[label v] corpora]
  (println)
  (println "==" label)
  (let [n (elements v)
        ;; --- reference builds for sizes: a recorded bump build, and its image
        bs0 (L/bump-sio (* 8 1024 1024) true)
        boff0 (L/build! bs0 v)
        im0 (L/image! bs0 boff0 (fn [o] (L/size-of bs0 o)))
        caps (L/caps-for im0 3)
        caps-exact (L/caps-for im0 1.3)
        {:keys [census padded]} (L/class-census im0)
        nb (:nblocks im0) nr (:nrel im0)
        ;; --- the slab source (pre-sized), built through a recording sio so every block's size is known
        ctx (L/slab-domain caps)
        rs (L/rec-sio ctx)
        soff (L/build! rs v)
        slab-v (L/reopen ctx soff)                    ;; read through the raw domain (the heap fast path)
        size-fn (fn [o] (L/size-of rs o))
        flat (codec/value->eve-bytes v)
        tj (transit-bytes v :json) tm (transit-bytes v :msgpack)
        edn-s (pr-str v)
        im (L/image! ctx soff size-fn)]
    (row "sizes"
         (format "edn %,d B | transit-json %,d | transit-msgpack %,d | flat codec %,d"
                 (count (.getBytes edn-s "UTF-8")) (alength tj) (alength tm) (alength flat)))
    (row ""
         (format "image %,d B (%,d blocks, %,d pointers) | class-padded in slab %,d B | block table %,d B | reloc table %,d B"
                 (:len im) nb nr padded (* 8 nb) (* 12 nr)))
    (row "" (str "block census by class " census))
    (assert (= v (d/eve->clj slab-v)) "slab value differs")
    (assert (= v (d/eve->clj (L/image-view im))) "image value differs")
    (assert (= v (codec/decode nil flat)) "flat round trip differs")

    (println "  to a slab domain")
    (row "plain -> slab, eve's default domain (grows)" (fmt-ms (bench #(L/build! (L/slab-domain) v))))
    (row "plain -> slab, pre-sized domain"            (fmt-ms (bench #(L/build! (L/slab-domain caps) v))))
    (row "plain -> bump buffer (no allocator)"        (fmt-ms (bench #(L/build! (L/bump-sio (* 32 1024 1024) false) v))))
    (let [r-each (bench #(L/ingest! (L/slab-domain caps) im :each))
          r-runs (bench #(L/ingest! (L/slab-domain caps) im :runs))
          r-dom  (bench #(L/slab-domain caps))]
      (row "(creating the pre-sized domain alone)"     (fmt-ms r-dom))
      (row "image -> slab, per-block allocation"       (fmt-ms r-each))
      (row "image -> slab, one run per class"          (fmt-ms r-runs))
      ;; correctness of both ingests, and that the domain still allocates soundly after a run claim
      (let [c1 (L/slab-domain caps) o1 (L/ingest! c1 im :each)
            c2 (L/slab-domain caps) o2 (L/ingest! c2 im :runs)
            v2 (L/reopen c2 o2)
            v2' (assoc (if (map? v2) v2 (L/reopen c2 (L/build! c2 {:k 1}))) :after-ingest 1)]
        (assert (= v (d/eve->clj (L/reopen c1 o1))) ":each ingest differs")
        (assert (= v (d/eve->clj v2)) ":runs ingest differs")
        (assert (= 1 (:after-ingest v2')) "allocation after a run claim")
        (assert (= v (d/eve->clj (L/reopen c2 o2))) ":runs ingest changed by a later allocation")))

    (println "  out of a slab domain")
    (row "slab -> plain (eve->clj)"                   (fmt-ms (bench #(d/eve->clj slab-v))))
    (row "slab -> image (relocating copy)"            (fmt-ms (bench #(L/image! ctx soff size-fn))))
    (row "image -> plain (eve->clj over the view)"    (fmt-ms (bench #(d/eve->clj (L/image-view im)))))

    (println "  the byte codecs (plain on both sides)")
    (row "plain -> flat codec bytes"                  (fmt-ms (bench #(codec/value->eve-bytes v))))
    (row "flat codec bytes -> plain"                  (fmt-ms (bench #(codec/decode nil flat))))
    (row "plain -> transit-json"                      (fmt-ms (bench #(transit-bytes v :json))))
    (row "transit-json -> plain"                      (fmt-ms (bench #(transit-read tj :json))))
    (row "plain -> transit-msgpack"                   (fmt-ms (bench #(transit-bytes v :msgpack))))
    (row "transit-msgpack -> plain"                   (fmt-ms (bench #(transit-read tm :msgpack))))
    (row "plain -> edn text"                          (fmt-ms (bench #(pr-str v))))
    (row "edn text -> plain"                          (fmt-ms (bench #(edn/read-string edn-s))))

    (println "  reading in place: 100k point reads")
    (let [iv (L/image-view im)
          ks (if (map? v) (vec (take 1000 (cycle (keys v)))) nil)
          idx (int-array (repeatedly 100000 #(.nextInt rnd (int n))))
          probe (fn [x]
                  (if (map? x)
                    (let [k (count ks)]
                      (loop [i 0 acc 0]
                        (if (== i 100000) acc
                            (recur (inc i) (+ acc (if (nil? (get x (nth ks (rem (aget idx i) k)))) 0 1))))))
                    (loop [i 0 acc 0]
                      (if (== i 100000) acc
                          (let [e (nth x (aget idx i))]
                            (recur (inc i) (+ acc (if (map? e) (if (nil? (get e :id)) 0 1) 1))))))))]
      (assert (= (probe v) (probe slab-v) (probe iv)))
      (row "plain host collection"                    (fmt-ms (bench #(probe v))))
      (row "slab value (heap fast path)"              (fmt-ms (bench #(probe slab-v))))
      (row "image view (through the slab I/O protocol)" (fmt-ms (bench #(probe iv)))))))

(println)
(println "P3 done")
(System/exit 0)
