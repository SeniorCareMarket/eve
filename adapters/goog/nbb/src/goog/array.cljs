(ns goog.array
  "Google Closure's goog.array, for nbb (ClojureScript on SCI): nbb carries
   no Closure library, so a library written for ClojureScript whose source
   names a goog namespace — DataScript's datascript.db requires goog.array —
   cannot load on eve's nbb seat until the seat provides it (F-NBB-GOOG).
   Each var is Closure's own definition (goog/array/array.js), over
   JavaScript's operators, so a value orders here as it orders in
   ClojureScript. The surface is census-driven: a var joins this namespace
   when a library eve's adopters load names it — the DataScript port names
   defaultCompare, at three call sites in datascript.db (comparing type
   names, attribute names, and same-typed values).")

(defn defaultCompare
  "Closure's goog.array.defaultCompare: 1 if a > b, -1 if a < b, else 0,
   over JavaScript's relational operators — its own definition, word for
   word."
  [a b]
  (cond
    (> a b) 1
    (< a b) -1
    :else   0))
