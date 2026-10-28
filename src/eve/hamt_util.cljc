(ns eve.hamt-util
  "Shared utilities for EVE HAMT data structures (map and set).

   Contains:
   - portable-hash-bytes: Murmur3_x86_32 hash producing identical output on
     every host, used for HAMT trie navigation.
   - Bitwise HAMT helpers: mask-hash, bitpos, has-bit?, get-index, popcount32.

   The 32-bit arithmetic is eve.int32's (the int32 adapter): the same signed
   results on every host."
  (:refer-clojure :exclude [aget alength bytes])
  (:require [eve.host :refer [aget alength bytes]]
            [eve.int32 :as i32]))

;;=============================================================================
;; Portable Murmur3_x86_32 hash — identical output on every host
;;=============================================================================
;; Used for HAMT trie navigation so every host produces the same tree shape.
;; Input: serialized key bytes (Uint8Array on CLJS, byte[] on JVM, the host's
;; byte array elsewhere — signed or unsigned elements both read correctly).

(defn- ubyte
  "Read unsigned byte from buf at index i."
  [buf i]
  (bit-and (aget ^bytes buf i) 0xFF))

(defn portable-hash-bytes
  "Murmur3_x86_32 hash (seed=0) of byte array.
   Returns int32, identical on every host."
  [buf]
  (let [len     (alength ^bytes buf)
        nblocks (i32/ushr len 2)]
    (loop [i 0, h 0]
      (if (< i nblocks)
        (let [j (* i 4)
              k (bit-or (bit-or (ubyte buf j)
                                (bit-shift-left (ubyte buf (+ j 1)) 8))
                        (bit-or (bit-shift-left (ubyte buf (+ j 2)) 16)
                                (bit-shift-left (ubyte buf (+ j 3)) 24)))
              k (-> k (i32/imul 0xcc9e2d51) (i32/rotl 15) (i32/imul 0x1b873593))
              h (bit-xor h k)
              h (-> h (i32/rotl 13) (i32/imul 5))
              h (+ h 0xe6546b64)]
          (recur (inc i) (i32/i32 h)))
        ;; Tail
        (let [t (* nblocks 4)
              r (bit-and len 3)
              k (cond (== r 3) (bit-or (bit-or (ubyte buf t)
                                               (bit-shift-left (ubyte buf (+ t 1)) 8))
                                       (bit-shift-left (ubyte buf (+ t 2)) 16))
                      (== r 2) (bit-or (ubyte buf t)
                                       (bit-shift-left (ubyte buf (+ t 1)) 8))
                      (== r 1) (ubyte buf t)
                      :else    0)
              h (if (pos? r)
                  (bit-xor h (-> k (i32/imul 0xcc9e2d51)
                                 (i32/rotl 15)
                                 (i32/imul 0x1b873593)))
                  h)
              ;; fmix32
              h (bit-xor h len)
              h (bit-xor h (i32/ushr h 16))
              h (i32/imul h 0x85ebca6b)
              h (bit-xor h (i32/ushr h 13))
              h (i32/imul h 0xc2b2ae35)
              h (bit-xor h (i32/ushr h 16))]
          (i32/i32 h))))))

;;=============================================================================
;; Bitwise HAMT helpers — shared across map and set
;;=============================================================================

(defn popcount32
  "Population count (number of set bits) in a 32-bit integer."
  [n]
  (i32/popcount n))

(defn mask-hash
  "Extract the 5-bit slot index from hash at the given shift level: bits
   [shift, shift+5) of the hash's 32-bit pattern, so the last level (shift
   30) has two bits on every host (FORMAT-DECISIONS.md §6)."
  [kh shift]
  (bit-and (i32/ushr kh shift) 0x1f))

(defn bitpos
  "Compute the bitmap position for hash at the given shift level."
  [kh shift]
  (bit-shift-left 1 (mask-hash kh shift)))

(defn has-bit?
  "Test whether a specific bit is set in a bitmap."
  [bitmap bit]
  (not (zero? (bit-and bitmap bit))))

(defn node-bit
  "The bit key hash kh occupies in an existing bitmap node at `shift` (data
   bitmap dbm, child bitmap nbm): (bitpos kh shift), except in a shift-30 node
   a JVM or bb build wrote before FORMAT-DECISIONS §6. That build indexed a
   negative hash by bits 30-34 of its sign-extended long, 28 slots above the
   32-bit pattern's (30/31 for 2/3); when the pattern's slot is empty there and
   that one is set, the key is read, replaced and removed where it was
   written. A new key takes (bitpos kh shift)."
  [dbm nbm kh shift]
  (let [bit (bitpos kh shift)]
    (if (and (== shift 30) (neg? kh)
             (not (has-bit? dbm bit)) (not (has-bit? nbm bit)))
      (let [old (bit-shift-left bit 28)]
        (if (or (has-bit? dbm old) (has-bit? nbm old)) old bit))
      bit)))

(defn get-index
  "Count set bits below `bit` in `bitmap` to get the array index."
  [bitmap bit]
  (popcount32 (bit-and bitmap (dec bit))))
