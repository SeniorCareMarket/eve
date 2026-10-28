(ns eve.deftype.value-buffer
  "A runtime's own arrays in eve memory (EVM-11e): what a language runtime's closures, accumulators and argument
   vectors hold — VS's are the kernel's slab arrays today (vs/src/vs/kernel/wasm/sarr.vs, eve's slab_array.js).

   THE HOME is eve.deftype.array: an array of eve values, any value in a slot (a closure's captured fn by
   reference, FORMAT-DECISIONS.md §9; a number, a string, a collection by pointer). A closure's captures and a
   call's argument vector are fixed in size and written while new: make-array, aset, aget, alength.

   An accumulator grows: this namespace's buffer carries sarr.vs's operations — buffer (with a capacity),
   buffer-count, buffer-capacity, buffer-nth, buffer-set!, push! (amortised O(1): in place while the array has room,
   else an array twice the size, the values copied — slots share the values they point at), pop! (the last value;
   its slot is left as it was, and the next push! overwrites it), and ->array, the eve array of exactly its
   values, to publish. Writes come from the thread that made the buffer, or throw (sarr.vs's P1-8 owner rule): its
   array is never seen by anyone else, which is what lets it be written after it is new. An array the buffer
   outgrew stays where it is, as a dropped value does outside an atom."
  (:refer-clojure :exclude [deftype])
  (:require [eve.deftype :refer [deftype]]
            [eve.deftype.array :as arr]
            [eve.process :as proc]))

(defprotocol IValueBuffer
  (-varr [b])
  (-vn [b])
  (-vowner [b])
  (-set-varr! [b a])
  (-set-vn! [b n]))

(deftype ValueBuffer [^:mutable a ^:mutable n owner]
  IValueBuffer
  (-varr [_] a)
  (-vn [_] n)
  (-vowner [_] owner)
  (-set-varr! [_ x] (set! a x))
  (-set-vn! [_ x] (set! n x)))

(defn buffer
  "An empty buffer with room for capacity values (at least 1), owned by this
   thread."
  ([] (buffer 8))
  ([capacity] (->ValueBuffer (arr/make-array (max 1 capacity)) 0 (proc/thread-id))))

(defn buffer-count "The number of values." [b] (-vn b))

(defn buffer-capacity "The values it holds before it grows." [b] (arr/alength (-varr b)))

(defn- owned! [b]
  (when (not= (proc/thread-id) (-vowner b))
    (throw (ex-info "eve.deftype.value-buffer: written by a thread that does not own it" {:type :not-owner}))))

(defn- in-range! [b i]
  (when (or (neg? i) (>= i (-vn b)))
    (throw (ex-info (str "eve.deftype.value-buffer: index " i " of " (-vn b)) {:type :index :index i}))))

(defn buffer-nth "The value at index i." [b i] (in-range! b i) (arr/aget (-varr b) i))

(defn buffer-set!
  "Store v at index i (< count). Answers the buffer."
  [b i v]
  (owned! b)
  (in-range! b i)
  (arr/aset (-varr b) i v)
  b)

(defn push!
  "Append v: in place while there is room, else into an array twice the size.
   Answers the buffer."
  [b v]
  (owned! b)
  (let [a (-varr b)
        n (-vn b)
        a (if (< n (arr/alength a))
            a
            (let [g (arr/make-array (* 2 (arr/alength a)))]
              (arr/array-copy a 0 g 0 n)
              (-set-varr! b g)
              g))]
    (arr/aset a n v)
    (-set-vn! b (inc n))
    b))

(defn pop!
  "Remove the last value and answer it; throws when empty."
  [b]
  (owned! b)
  (let [n (-vn b)]
    (when (zero? n) (throw (ex-info "eve.deftype.value-buffer: pop! of an empty buffer" {:type :empty})))
    (let [v (arr/aget (-varr b) (dec n))]
      (-set-vn! b (dec n))
      v)))

(defn ->array
  "A new eve array of exactly the buffer's values, in order — what to publish."
  [b]
  (let [n (-vn b)
        out (arr/make-array n)]
    (arr/array-copy (-varr b) 0 out 0 n)))
