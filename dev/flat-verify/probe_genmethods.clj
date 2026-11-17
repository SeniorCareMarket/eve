;; Generated freeze/thaw (eve-deftype's IFreezeThaw pair):
;; the sister-test namespace's Gadget/Widget types were
;; defined with plain eve-deftype and no flat code — the
;; methods must exist on them by generation alone.
(require 'eve.flat.sister-test)
(require '[eve.atom :as ea] '[eve.flat.sister :as sister]
         '[eve.flat.freeze :as freeze] '[eve.flat.chunk :as chunk])
(def ->Widget (ns-resolve 'eve.flat.sister-test '->Widget))
(def ->Gadget (ns-resolve 'eve.flat.sister-test '->Gadget))
(def field-map (ns-resolve 'eve.flat.sister-test 'field-map))
(def w (->Widget 7 7.5 "seven" (->Gadget 10 "g1")))
(def a (ea/atom {:id :gen-a} nil))
(def sio (ea/atom-sio a))
(def fv (sister/freeze sio w))
(swap! a (fn [_] fv))
(println :gen {:w-freezable (satisfies? sister/IFreezeThaw w)
               :fv-class (class fv)
               :installed-deref-fields @a
               :w-fields (field-map w)})
(def s (chunk/open (chunk/chunk-bytes (chunk/pack w))))
(println :gen-sister {:s-class (class s)
                      :s-freezable (satisfies? sister/IFreezeThaw s)
                      :thawed (sister/thaw s)})
(def fv2 (sister/freeze sio [1 2 3]))
(println :gen-generic {:fv2-class (class fv2) :thawed-vec (sister/thaw fv2)})
(println :DONE)
(System/exit 0)
