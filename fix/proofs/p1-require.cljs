(require 'goog.array)
(let [dc goog.array/defaultCompare]
  (println :dc-numbers [(dc 1 2) (dc 2 1) (dc 2 2)])
  (println :dc-strings [(dc "a" "b") (dc "b" "a") (dc "a" "a")])
  (println :dc-bools   [(dc true false) (dc false true) (dc true true)])
  (println :dc-arrays  [(dc (array 1) (array 2)) (dc (array 2) (array 1)) (dc (array 1) (array 1))]))
(require 'datascript.db 'datascript.core)
(println :REQUIRE-OK)
