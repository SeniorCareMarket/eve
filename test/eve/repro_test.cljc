(ns eve.repro-test
  "Minimal reproduction of Dataset Pipeline EveArray cnt corruption."
  (:refer-clojure :exclude [atom])
  (:require
   [eve.array :as arr]
   [eve.dataset :as ds]
   [eve.dataset.functional :as func]
   [eve.dataset.argops :as argops]
   [eve.atom :as e]
   #?(:cljs [eve.deftype-proto.alloc :as alloc])
   #?(:clj [clojure.java.io :as io])))

(def domain-extensions
  [".slab0" ".slab1" ".slab2" ".slab3" ".slab4" ".slab5" ".root" ".rmap"])

(defn cleanup! [path]
  #?(:cljs (let [fs (js/require "fs")]
             (doseq [ext domain-extensions]
               (let [p (str path ext)]
                 (try (.unlinkSync fs p) (catch :default _)))))
     :clj (doseq [ext domain-extensions]
            (let [f (io/file (str path ext))]
              (when (.exists f) (.delete f))))))

#?(:cljs
   (defn- read-raw-header
     "Read first 8 header bytes of an EveArray's slab block."
     [arr-val]
     (when arr-val
       (let [off (.-offset__ arr-val)
             base (alloc/resolve-dv! off)
             dv alloc/resolved-dv]
         {:type-id (.getUint8 dv base)
          :subtype (.getUint8 dv (+ base 1))
          :cnt (.getInt32 dv (+ base 4) true)
          :offset off}))))

(defn- test-dataset-pipeline! [n path]
  (println (str "\n--- Dataset pipeline n=" n " ---"))
  (cleanup! path)
  (let [prices-v (vec (repeatedly n #(* (rand) 100.0)))
        qtys-v (vec (repeatedly n #(* (rand) 50.0)))
        cats-v (vec (repeatedly n #(int (* (rand) 10))))
        ids-v (vec (range n))
        gt500 (fn [x] (> x 500.0))
        a (e/atom {:id (keyword "repro" (str "ds-" n)) :persistent path} nil)]
    (reset! a {:price (arr/eve-array :float64 prices-v)
               :qty (arr/eve-array :float64 qtys-v)
               :cat (arr/eve-array :int32 cats-v)
               :id (arr/eve-array :int32 ids-v)})
    (try
      (swap! a
             (fn [s]
               (let [p (:price s)
                     q (:qty s)
                     c (:cat s)
                     id (:id s)]
                 (println (str "  price cnt=" (count p) " qty cnt=" (count q)))

                 (let [rev (func/mul p q)]
                   (println (str "  rev cnt=" (count rev)
                                 #?(:cljs (str " hdr=" (pr-str (read-raw-header rev))))))

                   (let [ds1 (ds/dataset {:price p :qty q :cat c :id id})]
                     (println (str "  ds1 created, nrows=" (ds/row-count ds1)))

                     ;; Check rev AFTER dataset creation
                     (println (str "  rev after ds1: cnt=" (count rev)
                                   #?(:cljs (str " hdr=" (pr-str (read-raw-header rev))))))

                     (let [ds2 (ds/add-column ds1 :revenue rev)]
                       (println (str "  ds2 (add-col) nrows=" (ds/row-count ds2)))

                       (let [ds3 (ds/filter-rows ds2 :revenue gt500)]
                         (println (str "  ds3 (filter) nrows=" (ds/row-count ds3)))

                         (let [ds4 (ds/sort-by-column ds3 :revenue :desc)]
                           (println (str "  ds4 (sort) nrows=" (ds/row-count ds4)))

                           (let [take-n (min 200 (ds/row-count ds4))
                                 ds5 (ds/head ds4 take-n)]
                             (println (str "  ds5 (head " take-n ") nrows=" (ds/row-count ds5)))

                             (let [rev5 (ds/column ds5 :revenue)
                                   p5 (ds/column ds5 :price)]
                               (println (str "  rev5 cnt=" (count rev5)
                                             #?(:cljs (str " hdr=" (pr-str (read-raw-header rev5))))))
                               (println (str "  p5 cnt=" (count p5)
                                             #?(:cljs (str " hdr=" (pr-str (read-raw-header p5))))))
                               (assoc s :result
                                      {:rev-sum (func/sum rev5)
                                       :price-mean (func/mean p5)})))))))))))
      (println (str "  SUCCESS: " (:result @a)))
      (catch #?(:cljs :default :clj Exception) ex
        (println (str "  FAILED: " #?(:cljs (.-message ex) :clj (.getMessage ex))))))
    (e/close! a)
    (cleanup! path)))

(defn run-repro! []
  (println "=== EveArray cnt corruption repro (v4) ===")
  (let [path "/tmp/eve-repro-ds"]
    ;; Test at increasing scales
    (test-dataset-pipeline! 100 path)
    (test-dataset-pipeline! 500 path)
    (test-dataset-pipeline! 1000 path)
    (test-dataset-pipeline! 5000 path)
    (test-dataset-pipeline! 10000 path)
    (println "\n=== Repro complete ===")))
