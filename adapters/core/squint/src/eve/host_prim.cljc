(ns eve.host-prim
  "The two operations squint compiles as FORMS, not vars — direct array
   indexing (aget) and the unsigned shift (unsigned-bit-shift-right) —
   in a namespace that shadows neither name.

   Measured on squint 0.16.213 (Bucket F, 2026-11-12): squint-cljs/core.js
   exports neither aget nor unsigned-bit-shift-right; unshadowed, the
   forms compile to a[i] and (x >>> n). In eve.host both names are
   eve.host's own (its :refer-clojure excludes them), so a body there
   that writes the bare form does NOT reach the form: the compiled
   eve.host idx/aget and ushr/unsigned-bit-shift-right called each other
   (mutual recursion, RangeError at run time), and squint's SCI load of
   eve.host — every .cljc consumer of eve.host's macros triggers it —
   died resolving the forward reference ('Unable to resolve symbol:
   aget', eve.host line 70, 98 of the gate compile's 105 errors).
   Here the names are unshadowed, so the forms are the primitives, in
   the compiled pass and in SCI alike; eve.host delegates to idx/ushr.")
(defn idx [a i] (aget a i))
(defn ushr [x n] (unsigned-bit-shift-right x n))
