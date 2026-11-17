(ns eve.math
  "Eve's binary64 math surface. sqrt is correctly rounded, including signed
   zero, subnormals, infinities and NaN, as required by java.lang.Math/sqrt."
  #?(:lpy (:import [math :as math])))

(defn sqrt
  "Correctly rounded binary64 square root. Negative inputs return NaN."
  [x]
  (let [x #?(:clje (float x) :default (double x))]
    #?(:cljs (js/Math.sqrt x)
       ;; ##NaN here would break clojerl's conditional reader, which
       ;; cannot skip-read the tag in an unselected arm.
       :lpy (if (< x 0.0) (python/float "nan") (math/sqrt x))
       ;; The BEAM has no NaN float (clojerl reads ##NaN as :__unbound__),
       ;; so a negative input raises from math:sqrt itself — the same
       ;; stance eve.net.codec takes for non-finite doubles on this host.
       :clje (math/sqrt x)
       :cljr (System.Math/Sqrt x)
       :default (Math/sqrt x))))
