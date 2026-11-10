(ns ^:no-doc me.tonsky.persistent-sorted-set.arrays
  "Eve port of Tonsky's arrays namespace (persistent-sorted-set 0.2.3).

   Vanilla abstracts the host array substrate (JS Array / Java Object[]) behind
   these names. The port re-bases the same names and contracts on Eve vectors:
   an 'array' here is an Eve persistent vector. Vanilla only ever mutates
   freshly-allocated arrays before publication (copy-on-write discipline), so
   every operation below is a persistent-vector construction with the same
   inputs and outputs. See ../../../../PORTING.md (category S)."
  (:refer-clojure :exclude [make-array into-array array amap aget aset alength array? aclone])
  ;; PORT DELTA (F-CLJE-HOST-REFERS): clojerl's clojure.core has no
  ;; unsigned-bit-shift-right (half, below); Eve's seat answer is
  ;; eve.host's — every Eve seat defines it there. Under :clje this
  ;; ns refers it; other seats' read is unchanged.
  ;; PORT DELTA (F-CLJE-NS-REQUIRE): the conditional wraps the whole
  ;; :require clause — as the sole libspec inside (:require ...) it
  ;; left an EMPTY clause on the other seats, which Clojure's ns
  ;; spec rejects outright (stage-50 JVM behave died loading this
  ;; ns). A vanished clause is legal; an empty one is not.
  #?(:clje (:require [eve.host :refer [unsigned-bit-shift-right]]))
  (:require [eve.vec :as evec]))

(defn make-array
  "An array of n empty (nil) slots."
  [size]
  (vec (repeat size nil)))

(defn into-array
  "An array holding the elements of aseq, in order."
  [aseq]
  (vec aseq))

(defn aget
  "The element at index i."
  [arr i]
  (nth arr i))

(defn alength
  "The number of slots."
  [arr]
  (count arr))

(defn aset
  "Vanilla mutates a fresh array in place and returns it. The port returns the
   updated vector; construction call sites thread the value (PORTING.md S2)."
  [arr i v]
  (assoc arr i v))

(defn array
  "An array of the arguments."
  [& args]
  (vec args))

(defn acopy
  "Returns `to` with the range [from-start, from-end) of `from` copied in at
   to-start. Vanilla mutates `to`; the port returns the new vector (S2).
   PORT DELTA (tally write fix): when both arrays are Eve vectors (the
   stored-node case), assembled at the block level
   (eve.vec/replace-range-vec) — carried-over elements keep their value
   blocks instead of being decoded and re-wrapped one assoc at a time.
   A not-yet-stored array is a host vector (make-array/into-array/array
   build host vectors; an array becomes an Eve vector when its node is
   stored and read back) — host inputs take the original assoc path,
   whose elements have no blocks to share yet."
  [from from-start from-end to to-start]
  (if (and (evec/eve-vector? from) (evec/eve-vector? to))
    (evec/replace-range-vec to to-start from from-start from-end)
    (loop [i from-start
           j to-start
           acc to]
      (if (< i from-end)
        (recur (inc i) (inc j) (assoc acc j (nth from i)))
        acc))))

(defn aclone
  "Vanilla copies the array. Eve vectors are persistent — a clone is the value
   itself (S3)."
  [from]
  from)

(defn aconcat
  "An array of a's elements followed by b's.
   PORT DELTA (tally write fix): when both sides are Eve vectors,
   assembled at the block level (eve.vec/concat-vec) — both sides'
   elements keep their value blocks. Host-vector inputs (not yet
   stored) take the original (into (vec a) b) path."
  [a b]
  (if (and (evec/eve-vector? a) (evec/eve-vector? b))
    (evec/concat-vec a b)
    (into (vec a) b)))

(defn splice
  "arr[cut-from, splice-from) ++ xs ++ arr[splice-to, cut-to) as one array.
   PORT DELTA (tally write fix): when arr is an Eve vector, assembled
   at the block level (eve.vec/splice-vec) — carried-over elements keep
   their value blocks; only the spliced-in xs are wrapped. This is the
   sorted set's splice shape, and the reason a datom insert no longer
   re-wraps every key and pointer on the path. A host-vector arr (not
   yet stored) takes the original vec/concat/subvec rebuild."
  [arr cut-from cut-to splice-from splice-to xs]
  (if (evec/eve-vector? arr)
    (evec/splice-vec arr cut-from splice-from splice-to cut-to xs)
    (vec
      (concat
        (subvec arr cut-from splice-from)
        xs
        (subvec arr splice-to cut-to)))))

(defn amap
  "An array of (f x) for each element x of arr."
  [f arr]
  (mapv f arr))

(defn asort
  "An array of arr's elements, sorted by comparator cmp."
  [arr cmp]
  (vec (sort cmp arr)))

(defn array?
  "True when x is one of this namespace's arrays (an Eve vector, in the port)."
  [x]
  (vector? x))

(defn alast
  "The last element."
  [arr]
  (nth arr (dec (count arr))))

(defn aslice
  "The sub-array arr[from, to) — vanilla's (.slice arr from to) call sites.
   PORT DELTA (tally write fix): when arr is an Eve vector, the
   block-level subvec (eve.vec/subvec-sharing) — the range's elements
   keep their value blocks. A host-vector arr takes the original
   subvec."
  ([arr from]
   (if (evec/eve-vector? arr)
     (evec/subvec-sharing arr from (alength arr))
     (subvec arr from)))
  ([arr from to]
   (if (evec/eve-vector? arr)
     (evec/subvec-sharing arr from to)
     (subvec arr from to))))

(defn half
  "Half of x, rounded down (vanilla: unsigned-bit-shift-right by 1)."
  [x]
  (unsigned-bit-shift-right x 1))
