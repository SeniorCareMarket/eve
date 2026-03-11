(ns eve.hamt-util
  "Shared utilities for EVE HAMT data structures (map and set).

   Contains:
   - portable-hash-bytes: Murmur3_x86_32 hash producing identical output on
     CLJS and JVM, used for HAMT trie navigation.
   - Bitwise HAMT helpers: mask-hash, bitpos, has-bit?, get-index, popcount32.")

;;=============================================================================
;; Portable Murmur3_x86_32 hash — identical output on CLJS and JVM
;;=============================================================================
;; Used for HAMT trie navigation so both platforms produce the same tree shape.
;; Input: serialized key bytes (Uint8Array on CLJS, byte[] on JVM).

(defn- ubyte
  "Read unsigned byte from buf at index i."
  [buf i]
  #?(:cljs (aget buf i)
     :clj  (bit-and (long (aget ^bytes buf i)) 0xFF)))

(defn- imul32 [a b]
  #?(:cljs (js/Math.imul a b)
     :clj  (long (unchecked-multiply-int a b))))

(defn- rotl32 [x r]
  #?(:cljs (bit-or (unsigned-bit-shift-right x (- 32 r)) (bit-shift-left x r))
     :clj  (long (Integer/rotateLeft (unchecked-int x) (int r)))))

(defn- ushr32 [x n]
  #?(:cljs (unsigned-bit-shift-right x n)
     :clj  (unsigned-bit-shift-right (Integer/toUnsignedLong (unchecked-int x)) (int n))))

(defn portable-hash-bytes
  "Murmur3_x86_32 hash (seed=0) of byte array.
   Returns int32, identical on CLJS (Uint8Array) and JVM (byte[])."
  [buf]
  (let [len     #?(:cljs (.-length buf) :clj (alength ^bytes buf))
        nblocks (unsigned-bit-shift-right len 2)]
    (loop [i 0, h (int 0)]
      (if (< i nblocks)
        (let [j (* i 4)
              k (bit-or (ubyte buf j)
                        (bit-shift-left (ubyte buf (+ j 1)) 8)
                        (bit-shift-left (ubyte buf (+ j 2)) 16)
                        (bit-shift-left (ubyte buf (+ j 3)) 24))
              k (-> k (imul32 (unchecked-int 0xcc9e2d51)) (rotl32 15)
                      (imul32 (unchecked-int 0x1b873593)))
              h (bit-xor h k)
              h (-> h (rotl32 13) (imul32 5))
              h (+ h (unchecked-int 0xe6546b64))]
          (recur (inc i) #?(:cljs (bit-or h 0) :clj (long (unchecked-int h)))))
        ;; Tail
        (let [t (bit-shift-left nblocks 2)
              r (bit-and len 3)
              k (cond (== r 3) (bit-or (ubyte buf t)
                                       (bit-shift-left (ubyte buf (+ t 1)) 8)
                                       (bit-shift-left (ubyte buf (+ t 2)) 16))
                      (== r 2) (bit-or (ubyte buf t)
                                       (bit-shift-left (ubyte buf (+ t 1)) 8))
                      (== r 1) (ubyte buf t)
                      :else    0)
              h (if (pos? r)
                  (bit-xor h (-> k (imul32 (unchecked-int 0xcc9e2d51))
                                   (rotl32 15)
                                   (imul32 (unchecked-int 0x1b873593))))
                  h)
              ;; fmix32
              h (bit-xor h len)
              h (bit-xor h (ushr32 h 16))
              h (imul32 h (unchecked-int 0x85ebca6b))
              h (bit-xor h (ushr32 h 13))
              h (imul32 h (unchecked-int 0xc2b2ae35))
              h (bit-xor h (ushr32 h 16))]
          #?(:cljs (bit-or h 0) :clj (unchecked-int h)))))))

;;=============================================================================
;; Bitwise HAMT helpers — shared across map and set
;;=============================================================================

(defn popcount32
  "Population count (number of set bits) in a 32-bit integer."
  [n]
  #?(:cljs (let [n (- n (bit-and (unsigned-bit-shift-right n 1) 0x55555555))
                 n (+ (bit-and n 0x33333333) (bit-and (unsigned-bit-shift-right n 2) 0x33333333))
                 n (bit-and (+ n (unsigned-bit-shift-right n 4)) 0x0f0f0f0f)]
             (unsigned-bit-shift-right (js* "Math.imul(~{}, 0x01010101)" n) 24))
     :clj  (Integer/bitCount (unchecked-int n))))

(defn mask-hash
  "Extract the 5-bit slot index from hash at the given shift level."
  [kh shift]
  (bit-and (unsigned-bit-shift-right kh shift) 0x1f))

(defn bitpos
  "Compute the bitmap position for hash at the given shift level."
  [kh shift]
  (bit-shift-left 1 (mask-hash kh shift)))

(defn has-bit?
  "Test whether a specific bit is set in a bitmap."
  [bitmap bit]
  (not (zero? (bit-and bitmap bit))))

(defn get-index
  "Count set bits below `bit` in `bitmap` to get the array index."
  [bitmap bit]
  (popcount32 (bit-and bitmap (dec bit))))
