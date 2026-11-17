;; The flat construction path (John, Nov 14 2026): instantiating
;; eve types with NO domain bound produces flat variants;
;; inside a swap! the same calls produce persistent values.
(require '[eve.atom :as ea] '[eve.flat.sister-test :as st]
         '[eve.dataset :as ds] '[eve.array :as arr]
         '[eve.flat.chunk :as chunk] '[eve.core :as e])
(def ->Widget (ns-resolve 'eve.flat.sister-test '->Widget))
(def ->Gadget (ns-resolve 'eve.flat.sister-test '->Gadget))
(def field-map (ns-resolve 'eve.flat.sister-test 'field-map))
(def describe (ns-resolve 'eve.flat.sister-test 'describe))
;; --- outside any domain ---
(def w (->Widget 3 1.5 "gear" (->Gadget 7 "inner")))
(println :widget-out {:class (class w) :fields (field-map w)
                      :describe (describe w)
                      :flat? (satisfies? chunk/IFlatHandle w)})
(def a (arr/eve-array :int32 [1 2 3]))
(println :array-out {:class (class a) :nth1 (nth a 1) :count (count a)})
(def d (ds/dataset {:a a :b (arr/eve-array :int32 [4 5 6])}))
(println :dataset-out {:class (class d) :rows (ds/row-count d)
                       :col-a (e/into [] (ds/column d :a))})
;; --- the flat values swap into an atom by embedding ---
(def at (ea/atom {:id :funnel-a} nil))
(swap! at (fn [_] {:w w :ds d}))
(println :swapped {:w-class (class (:w @at)) :ds-class (class (:ds @at))
                   :w-describe (try (describe (:w @at)) (catch Throwable t (str "THREW " (.getMessage t))))
                   :ds-rows (try (ds/row-count (:ds @at)) (catch Throwable t (str "THREW " (.getMessage t))))})
;; --- inside a swap the persistent forms appear ---
(def at2 (ea/atom {:id :funnel-b} nil))
(def inside (swap! at2 (fn [_] (->Widget 9 2.5 "cog" (->Gadget 1 "g")))))
(println :widget-in {:class (class inside) :describe (describe inside)
                     :deref-describe (describe @at2)})
(println :DONE)
(System/exit 0)
