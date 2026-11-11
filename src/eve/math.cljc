(ns eve.math
  "Eve's binary64 math surface. sqrt is correctly rounded, including signed
   zero, subnormals, infinities and NaN, as required by java.lang.Math/sqrt."
  #?(:lpy (:import [math :as math])))

(defn sqrt
  "Correctly rounded binary64 square root. Negative inputs return NaN."
  [x]
  (let [x (double x)]
    #?(:cljs (js/Math.sqrt x)
       :lpy (if (< x 0.0) ##NaN (math/sqrt x))
       :cljr (System.Math/Sqrt x)
       :default (Math/sqrt x))))
