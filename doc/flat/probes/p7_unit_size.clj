;; P7 — a spike: the unit of placement. An image cut, in buffer order, into units of at most 64 KiB against units of
;; at most 1 MiB (each block charged its 8-byte table row and 12 bytes a pointer), then placed unit by unit: one run
;; claimed per (unit, size class), the unit's blocks copied, and the pointers rewritten when every unit is in.
;; What a unit size changes: how many units a value is, how many pointers leave their unit, how many run claims.
;; Research code. JVM heap slab domains only.
(load-file "doc/static/probes/lib.clj")
(ns static-probe.p7
  (:require [static-probe.lib :as L :refer [bench]]
            [eve.deftype-proto.alloc :as alloc]
            [eve.deftype-proto.data :as d]))

(println "P7 — eve" (-> (slurp ".git/HEAD") clojure.string/trim) "| clojure" (clojure-version)
         "| java" (System/getProperty "java.version"))

(def rnd (java.util.Random. 7))
(defn rand-text [n]
  (let [words ["the" "quick" "atom" "swap" "value" "epoch" "slab" "domain" "static" "persistent" "vector" "map"
               "chunk" "page" "column" "row" "eve" "hello" "world" "ok" "thanks" "lgtm" "ship" "it"]]
    (clojure.string/join " " (repeatedly n #(nth words (.nextInt rnd (count words)))))))

(def corpora
  [["map 10k int->int"   (zipmap (range 10000) (range 10000))]
   ["vector 100k ints"   (vec (range 100000))]
   ["vector 10k records" (mapv (fn [i] {:id i :name (str "user-" i) :active (even? i) :score (* 1.5 i)}) (range 10000))]
   ["5k chat messages"   (mapv (fn [i]
                                 {:id (str "m-" i)
                                  :room (nth [:general :random :dev :ops] (mod i 4))
                                  :author {:id (mod i 37) :name (str "user-" (mod i 37))}
                                  :text (rand-text (+ 3 (.nextInt rnd 25)))
                                  :ts (+ 1700000000000 (* i 1000))
                                  :tags (set (take (mod i 3) [:pinned :edited :thread]))
                                  :reactions (mapv (fn [j] {:emoji (nth [:+1 :heart :eyes] (mod j 3)) :by (mod (+ i j) 37)})
                                                   (range (mod i 4)))})
                               (range 5000))]])

(defn units
  "Blocks of im cut in buffer order into units of at most cap bytes (block + 8 + 12 a pointer it owns):
   [block->unit ints, unit count]."
  [im cap]
  (let [^ints bsz (:bsz im) ^ints rown (:rown im) n (int (:nblocks im)) u (int-array n) np (int-array n)]
    (dotimes [j (int (:nrel im))] (aset np (aget rown j) (inc (aget np (aget rown j)))))
    (loop [i 0 cur 0 used 0]
      (if (== i n)
        [u (inc cur)]
        (let [s (+ (aget bsz i) 8 (* 12 (aget np i)))]
          (if (and (pos? used) (> (+ used s) cap))
            (do (aset u i (inc cur)) (recur (inc i) (inc cur) (long s)))
            (do (aset u i cur) (recur (inc i) cur (+ used s)))))))))

(defn crossing [im ^ints u]
  (let [^ints rown (:rown im) ^ints rtgt (:rtgt im) nr (int (:nrel im))]
    (loop [j 0 x 0] (if (== j nr) x (recur (inc j) (if (== (aget u (aget rown j)) (aget u (aget rtgt j))) x (inc x)))))))

(defn caps [im nu]
  ;; room for every block plus one bitmap word of slack per (unit, class): a run starts on a word boundary
  (let [^ints bsz (:bsz im) cnt (long-array 7)]
    (dotimes [i (int (:nblocks im))] (let [c (L/class-of (aget bsz i))] (aset cnt c (inc (aget cnt c)))))
    (into {} (map (fn [c] [c (max 65536 (* (long (nth L/class-bytes c)) (+ (aget cnt c) (* 32 nu) 64)))]) (range 6)))))

(defn place-units!
  "im placed in ctx unit by unit: [root offset, run claims]."
  [ctx im ^ints u nu]
  (let [n (int (:nblocks im)) ^ints boff (:boff im) ^ints bsz (:bsz im) ^bytes img (:img im)
        nw (int-array n) cnt (long-array (* 7 (long nu))) claims (long-array 1)]
    (dotimes [i n] (let [k (+ (* 7 (aget u i)) (L/class-of (aget bsz i)))] (aset cnt k (inc (aget cnt k)))))
    (loop [un 0 i 0]
      (when (< un nu)
        (let [nxt (long-array 7)]
          (dotimes [c 6]
            (let [k (aget cnt (+ (* 7 un) c))]
              (when (pos? k) (aset nxt c (L/claim-run! ctx c k)) (aset claims 0 (inc (aget claims 0))))))
          (recur (inc un)
                 (long (loop [i i]
                         (if (and (< i n) (== (aget u i) un))
                           (let [c (L/class-of (aget bsz i))
                                 o (long (if (== c 6)
                                           (alloc/-sio-alloc! ctx (aget bsz i))
                                           (let [o (alloc/encode-slab-offset c (aget nxt c))] (aset nxt c (inc (aget nxt c))) o)))]
                             (aset nw i (unchecked-int o))
                             (System/arraycopy img (aget boff i) ^bytes (alloc/node-heap-array ctx o)
                                               (int (alloc/node-base ctx o)) (aget bsz i))
                             (recur (inc i)))
                           i)))))))
    (let [^ints rpos (:rpos im) ^ints rown (:rown im) ^ints rtgt (:rtgt im)]
      (dotimes [j (int (:nrel im))]
        (let [own (aget rown j) o (long (aget nw own))]
          (L/put-i32! (alloc/node-heap-array ctx o)
                      (+ (long (alloc/node-base ctx o)) (- (aget rpos j) (aget boff own)))
                      (long (aget nw (aget rtgt j)))))))
    [(long (aget nw 0)) (aget claims 0)]))

(doseq [[label v] corpora]
  (let [bs (L/bump-sio (* 32 1024 1024) true)
        off (L/build! bs v)
        im (L/image! bs off (fn [o] (L/size-of bs o)))]
    (println)
    (println (format "== %s — image %,d B, %,d blocks, %,d pointers" label (:len im) (:nblocks im) (:nrel im)))
    (println "   unit    | units | blocks/unit | pointers leaving their unit | run claims | place ms (net of domain) | reads back equal")
    (doseq [[uname cap] [["64 KiB" 65536] ["1 MiB" 1048576] ["whole" Long/MAX_VALUE]]]
      (let [[u nu] (units im cap)
            x (crossing im u)
            cp (caps im nu)
            dom (:min (bench 2 5 #(L/slab-domain cp)))
            r (bench 2 5 #(place-units! (L/slab-domain cp) im u nu))
            ctx (L/slab-domain cp)
            [root claims] (place-units! ctx im u nu)
            ok (try (= v (d/eve->clj (L/reopen ctx root))) (catch Throwable t (str "ERR " (.getMessage t))))]
        (println (format "   %-7s | %5d | %11.0f | %,9d (%5.2f %%)          | %10d | %8.2f                 | %s"
                         uname nu (/ (double (:nblocks im)) nu) x (* 100.0 (/ (double x) (max 1 (:nrel im))))
                         claims (- (:min r) dom) ok))))))

(println)
(println "P7 done")
