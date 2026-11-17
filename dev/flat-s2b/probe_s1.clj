;; S1 acceptance (UPGRADE-PLAN): ConcatBatch path-copy writes,
;; BasedValue overlay semantics, FrozenValue edit routing.
(require '[eve.atom :as ea] '[eve.flat.freeze :as freeze]
         '[eve.flat.corpora :as corpora]
         '[eve.deftype-proto.data :as d])

(def corpora (into {} (corpora/corpora :canonical)))
(def rows (:records-10k corpora))
(def plain (freeze/plain-of (freeze/deliver-flat rows)))
(def dv (freeze/deliver-flat rows))
(println :dv-class (class dv) :links (count (.-handles dv)))

;; --- ConcatBatch path-copy assoc -------------------------------------------
(def row' (assoc (nth plain 5000) :name "zed"))
(def edited (assoc dv 5000 (assoc (nth dv 5000) :name "zed")))
(def expected (assoc plain 5000 row'))
(println :cb {:concat (instance? eve.flat.freeze.ConcatBatch edited)
              :cums (.-cums dv)
              :links-differing (count (filter false? (map identical? (.-handles dv) (.-handles edited))))
              :read (= "zed" (:name (nth edited 5000)))
              :orig-untouched (not= "zed" (:name (nth dv 5000)))
              :equal (= edited expected)
              :hash (= (hash edited) (hash expected))
              :count (count edited)})
(println :cb-cons {:count (count (conj dv (first plain)))
                   :last (= (nth (conj dv (first plain)) 10000) (first plain))})
(println :cb-pop {:count (count (pop dv))
                  :equal (= (pop dv) (pop plain))})

;; --- BasedValue -------------------------------------------------------------
(def bv (freeze/based-of dv))
(def bv2 (assoc bv 0 (assoc (nth bv 0) :name "ann")))
(def expected2 (assoc plain 0 (assoc (nth plain 0) :name "ann")))
(println :based {:class (instance? eve.flat.freeze.BasedValue bv2)
                 :base-identical (identical? dv (.-base bv2))
                 :read (= "ann" (:name (nth bv2 0)))
                 :read-fallthrough (= (nth bv2 9999) (nth plain 9999))
                 :equal (= bv2 expected2)
                 :hash (= (hash bv2) (hash expected2))
                 :count (count bv2)
                 :conj-count (count (conj bv2 (first plain)))
                 :conj-last (= (peek (conj bv2 (first plain))) (first plain))
                 :pop-count (count (pop bv2))
                 :pop-after-edit (= (count (pop (assoc bv 9999 (first plain)))) 9999)})

;; --- FrozenValue routing ----------------------------------------------------
(def dir (str (System/getProperty "java.io.tmpdir") "/s1-" (System/nanoTime)))
(def a (ea/atom rows {:mmap-dir dir :file-size (* 256 1024 1024)}))
(def sio (ea/atom-sio a))
(def fv (freeze/freeze-value sio rows))
(swap! a (fn [_] fv))
(def got @a)
(def ans (assoc got 5000 (assoc (nth got 5000) :name "meg")))
(println :fv {:frozen (instance? eve.flat.freeze.FrozenValue got)
              :assoc-answer-class (class ans)
              :assoc-read (= "meg" (:name (nth ans 5000)))
              :assoc-equal (= ans (assoc plain 5000 (assoc (nth plain 5000) :name "meg")))})
(println :DONE)
(System/exit 0)
