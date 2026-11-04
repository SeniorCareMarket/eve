(ns eve.hamt-util
  "Shared utilities for EVE HAMT data structures (map and set).

   Contains:
   - portable-hash-bytes: Murmur3_x86_32 hash producing identical output on
     every host, used for HAMT trie navigation.
   - xxh3-128-bytes: eve's hash — XXH3-128 over canonical serialized bytes.
     One portable implementation (32-bit limb arithmetic) producing
     identical output on every host; no host hash function is involved.
     Byte-exact with ValueScript's kernel's xxh3_128 (seed 0, default
     192-byte secret, 16 big-endian output bytes, high word first).
     Ported verbatim from the Merkle phase-1 port so the deftype registry can
     derive type-ids from eve's hash ahead of that work's landing;
     when phase 1 lands, the two copies are the same text.
   - key-hash-bytes: the 16-byte xxh3-128 digest of serialized key bytes.
   - fold-hash-int: the int32-shaped fold of the wide hash (XOR of its
     four big-endian u32 words, signed int32).
   - Bitwise HAMT helpers: mask-hash, bitpos, has-bit?, get-index, popcount32.

   The 32-bit arithmetic is eve.int32's (the int32 adapter): the same signed
   results on every host.

   A host-native xxh3-128 may be plugged in through the adapter slot
   (register-xxh3-128-impl!) only after it has passed the KAT suite in
   test/eve/xxh3_kat_test.cljc on that host — identity is proven by
   measurement, never assumed."
  (:refer-clojure :exclude [aget alength bytes])
  (:require [eve.host :refer [aget alength bytes]]
            [eve.encoding :as enc]
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
;; Portable xxh3-128 — identical output on every host
;;=============================================================================
;; Used for HAMT trie navigation so every host produces the same tree shape.
;; Input: serialized key bytes (Uint8Array on CLJS, byte[] on JVM, the host's
;; byte array elsewhere — signed or unsigned elements both read correctly).
;; The u64 arithmetic runs on [hi lo] pairs of unsigned 32-bit limbs held as
;; plain numbers in [0, 2^32); every intermediate product uses 16-bit
;; sub-limbs, so each value stays below 2^53 and is exact on every host.

(defn- byte-len [buf]
  (alength ^bytes buf))

(defn- new-bytes [n]
  (enc/make-bytes n))

(defn- put-byte! [arr i v]
  (aset ^bytes arr (int i) (unchecked-byte v)))

(def ^:private u32-range 4294967296)

(def ^:private pow2-table
  (loop [i 0
         acc []
         p 1]
    (if (> i 32) acc (recur (inc i) (conj acc p) (* p 2)))))

(defn- pow2 [n]
  (nth pow2-table n))

(defn- xor32 [a b]
  (i32/ushr (bit-xor a b) 0))

(defn- xor64 [[ah al] [bh bl]]
  [(xor32 ah bh) (xor32 al bl)])

(defn- add64 [[ah al] [bh bl]]
  (let [lo (+ al bl)
        hi (+ ah bh (if (>= lo u32-range) 1 0))]
    [(if (>= hi u32-range) (- hi u32-range) hi)
     (if (>= lo u32-range) (- lo u32-range) lo)]))

(defn- shr64
  "Unsigned 64-bit shift right by n (0 <= n < 64)."
  [[hi lo] n]
  (if (< n 32)
    (let [p (pow2 n)
          q (pow2 (- 32 n))]
      [(quot hi p) (+ (* (mod hi p) q) (quot lo p))])
    [0 (quot hi (pow2 (- n 32)))]))

(defn- shl64
  "64-bit shift left by n (0 <= n < 32)."
  [[hi lo] n]
  (let [p (pow2 n)
        q (pow2 (- 32 n))]
    [(mod (+ (* hi p) (quot lo q)) u32-range) (mod (* lo p) u32-range)]))

(defn- mul128
  "Full 64x64 -> 128 multiply. Returns [hi64 lo64], each a [hi lo] pair."
  [[ah al] [bh bl]]
  (let [av [(mod al 65536) (quot al 65536) (mod ah 65536) (quot ah 65536)]
        bv [(mod bl 65536) (quot bl 65536) (mod bh 65536) (quot bh 65536)]
        cols (reduce (fn [c i]
                       (reduce (fn [c j] (update c (+ i j) + (* (av i) (bv j))))
                               c (range 4))) [0 0 0 0 0 0 0] (range 4))
        limbs (loop [i 0
                     carry 0
                     out []]
                (if (< i 7)
                  (let [v (+ (nth cols i) carry)]
                    (recur (inc i) (quot v 65536) (conj out (mod v 65536))))
                  (conj out carry)))]
    [[(+ (* (limbs 7) 65536) (limbs 6)) (+ (* (limbs 5) 65536) (limbs 4))]
     [(+ (* (limbs 3) 65536) (limbs 2)) (+ (* (limbs 1) 65536) (limbs 0))]]))

(defn- mul64
  "Wrapping 64x64 -> 64 multiply (low half of the full product)."
  [a b]
  (second (mul128 a b)))

(defn- mul128-fold64
  "XXH3's fold: low 64 bits of the 128-bit product XOR its high 64 bits."
  [a b]
  (let [[hi lo] (mul128 a b)]
    (xor64 lo hi)))

(defn- m32to64
  "Unsigned 32x32 -> 64 multiply."
  [a b]
  (second (mul128 [0 a] [0 b])))

(defn- read-u32le [buf off]
  (+ (ubyte buf off) (* (ubyte buf (+ off 1)) 256)
     (* (ubyte buf (+ off 2)) 65536) (* (ubyte buf (+ off 3)) 16777216)))

(defn- read-u64le [buf off]
  [(read-u32le buf (+ off 4)) (read-u32le buf off)])

(defn- read-u32be [buf off]
  (+ (* (ubyte buf off) 16777216) (* (ubyte buf (+ off 1)) 65536)
     (* (ubyte buf (+ off 2)) 256) (ubyte buf (+ off 3))))

(defn- bswap32 [x]
  (+ (* (mod x 256) 16777216) (* (mod (quot x 256) 256) 65536)
     (* (mod (quot x 65536) 256) 256) (quot x 16777216)))

(defn- bswap64 [[hi lo]]
  [(bswap32 lo) (bswap32 hi)])

(defn- rotl32 [x r]
  (+ (mod (* x (pow2 r)) u32-range) (quot x (pow2 (- 32 r)))))

(def ^:private xxh3-secret-bytes
  [184
   254
   108
   57
   35
   164
   75
   190
   124
   1
   129
   44
   247
   33
   173
   28
   222
   212
   109
   233
   131
   144
   151
   219
   114
   64
   164
   164
   183
   179
   103
   31
   203
   121
   230
   78
   204
   192
   229
   120
   130
   90
   208
   125
   204
   255
   114
   33
   184
   8
   70
   116
   247
   67
   36
   142
   224
   53
   144
   230
   129
   58
   38
   76
   60
   40
   82
   187
   145
   195
   0
   203
   136
   208
   101
   139
   27
   83
   46
   163
   113
   100
   72
   151
   162
   13
   249
   78
   56
   25
   239
   70
   169
   222
   172
   216
   168
   250
   118
   63
   227
   156
   52
   63
   249
   220
   187
   199
   199
   11
   79
   29
   138
   81
   224
   75
   205
   180
   89
   49
   200
   159
   126
   201
   217
   120
   115
   100
   234
   197
   172
   131
   52
   211
   235
   195
   197
   129
   160
   255
   250
   19
   99
   235
   23
   13
   221
   81
   183
   240
   218
   73
   211
   22
   85
   38
   41
   212
   104
   158
   43
   22
   190
   88
   125
   71
   161
   252
   143
   248
   184
   209
   122
   208
   49
   206
   69
   203
   58
   143
   149
   22
   4
   40
   175
   215
   251
   202
   187
   75
   64
   126])

(def ^:private xxh3-secret
  (let [arr (new-bytes 192)]
    (dotimes [i 192]
      (put-byte! arr i (nth xxh3-secret-bytes i)))
    arr))

(defn- s64 [off]
  (read-u64le xxh3-secret off))

(defn- s32 [off]
  (read-u32le xxh3-secret off))

(def ^:private prime32-1 0x9E3779B1)

(def ^:private prime32-2 0x85EBCA77)

(def ^:private prime32-3 0xC2B2AE3D)

(def ^:private prime64-1 [0x9E3779B1 0x85EBCA87])

(def ^:private prime64-2 [0xC2B2AE3D 0x27D4EB4F])

(def ^:private prime64-3 [0x165667B1 0x9E3779F9])

(def ^:private prime64-4 [0x85EBCA77 0xC2B2AE63])

(def ^:private prime64-5 [0x27D4EB2F 0x165667C5])

(def ^:private prime-mx1 [0x16566791 0x9E3779F9])

(def ^:private prime-mx2 [0x9FB21C65 0x1E98DF25])

(defn- xxh64-avalanche [h]
  (let [h (xor64 h (shr64 h 33))
        h (mul64 h prime64-2)
        h (xor64 h (shr64 h 29))
        h (mul64 h prime64-3)
        h (xor64 h (shr64 h 32))]
    h))

(defn- xxh3-avalanche [h]
  (let [h (xor64 h (shr64 h 37))
        h (mul64 h prime-mx1)
        h (xor64 h (shr64 h 32))]
    h))

(defn- mix16b
  "(input[off..off+16] XOR secret[sec-off..sec-off+16]) folded via mul128."
  [buf off sec-off]
  (mul128-fold64 (xor64 (read-u64le buf off) (s64 sec-off))
                 (xor64 (read-u64le buf (+ off 8)) (s64 (+ sec-off 8)))))

(defn- mix32b
  "XXH3's 32-byte mix step; takes and returns the [lo hi] accumulator pair."
  [lo hi buf off1 off2 sec-off]
  (let [lo (add64 lo (mix16b buf off1 sec-off))
        lo (xor64 lo (add64 (read-u64le buf (+ off2 8)) (read-u64le buf off2)))
        hi (add64 hi (mix16b buf off2 (+ sec-off 16)))
        hi (xor64 hi (add64 (read-u64le buf (+ off1 8)) (read-u64le buf off1)))]
    [lo hi]))

(defn- xxh3-len-0 []
  [(xxh64-avalanche (xor64 (s64 80) (s64 88)))
   (xxh64-avalanche (xor64 (s64 64) (s64 72)))])

(defn- xxh3-len-1to3 [buf len]
  (let [c1 (ubyte buf 0)
        c2 (ubyte buf (quot len 2))
        c3 (ubyte buf (dec len))
        combinedl (mod (+ (* c1 65536) (* c2 16777216) c3 (* len 256)) u32-range)
        combinedh (rotl32 (bswap32 combinedl) 13)
        bitflipl (xor32 (s32 0) (s32 4))
        bitfliph (xor32 (s32 8) (s32 12))]
    [(xxh64-avalanche [0 (xor32 combinedh bitfliph)])
     (xxh64-avalanche [0 (xor32 combinedl bitflipl)])]))

(defn- xxh3-len-4to8 [buf len]
  (let [input-lo (read-u32le buf 0)
        input-hi (read-u32le buf (- len 4))
        keyed (xor64 [input-hi input-lo] (xor64 (s64 16) (s64 24)))
        [m-hi m-lo] (mul128 keyed (add64 prime64-1 [0 (* len 4)]))
        m-hi (add64 m-hi (shl64 m-lo 1))
        m-lo (xor64 m-lo (shr64 m-hi 3))
        m-lo (xor64 m-lo (shr64 m-lo 35))
        m-lo (mul64 m-lo prime-mx2)
        m-lo (xor64 m-lo (shr64 m-lo 28))
        m-hi (xxh3-avalanche m-hi)]
    [m-hi m-lo]))

(defn- xxh3-len-9to16 [buf len]
  (let [in-lo (read-u64le buf 0)
        in-hi (read-u64le buf (- len 8))
        bitflipl (xor64 (s64 32) (s64 40))
        bitfliph (xor64 (s64 48) (s64 56))
        [m-hi m-lo] (mul128 (xor64 (xor64 in-lo in-hi) bitflipl) prime64-1)
        m-lo (add64 m-lo [(* (dec len) 4194304) 0])
        in-hi (xor64 in-hi bitfliph)
        m-hi (add64 m-hi (add64 in-hi (m32to64 (second in-hi) (dec prime32-2))))
        m-lo (xor64 m-lo (bswap64 m-hi))
        [h-hi h-lo] (mul128 m-lo prime64-2)
        h-hi (add64 h-hi (mul64 m-hi prime64-2))]
    [(xxh3-avalanche h-hi) (xxh3-avalanche h-lo)]))

(defn- neg64
  "Two's complement negation of a u64 [hi lo] pair."
  [[hi lo]]
  (if (zero? lo)
    [(mod (- u32-range hi) u32-range) 0]
    [(- u32-range 1 hi) (- u32-range lo)]))

(defn- xxh3-len-17to128 [buf len]
  (let [[lo hi] (reduce (fn [[lo hi] [off1 off2 sec]]
                          (mix32b lo hi buf off1 off2 sec))
                        [(mul64 [0 len] prime64-1) [0 0]]
                        (cond-> []
                          (> len 96) (conj [48 (- len 64) 96])
                          (> len 64) (conj [32 (- len 48) 64])
                          (> len 32) (conj [16 (- len 32) 32])
                          true (conj [0 (- len 16) 0])))]
    (let [h-lo (add64 lo hi)
          h-hi (add64 (add64 (mul64 lo prime64-1) (mul64 hi prime64-4))
                      (mul64 [0 len] prime64-2))]
      [(neg64 (xxh3-avalanche h-hi)) (xxh3-avalanche h-lo)])))

(defn- xxh3-len-129to240 [buf len]
  (let [[lo hi] (loop [i 32
                       lo (mul64 [0 len] prime64-1)
                       hi [0 0]]
                  (if (< i 160)
                    (let [[lo hi] (mix32b lo hi buf (- i 32) (- i 16) (- i 32))]
                      (recur (+ i 32) lo hi))
                    [lo hi]))
        lo (xxh3-avalanche lo)
        hi (xxh3-avalanche hi)
        [lo hi] (loop [i 160
                       lo lo
                       hi hi]
                  (if (<= i len)
                    (let [[lo hi] (mix32b lo hi buf (- i 32) (- i 16) (- i 157))]
                      (recur (+ i 32) lo hi))
                    [lo hi]))
        [lo hi] (mix32b lo hi buf (- len 16) (- len 32) 103)
        h-lo (add64 lo hi)
        h-hi (add64 (add64 (mul64 lo prime64-1) (mul64 hi prime64-4))
                    (mul64 [0 len] prime64-2))]
    [(neg64 (xxh3-avalanche h-hi)) (xxh3-avalanche h-lo)]))

(defn- accumulate-512 [acc buf off sec-off]
  (loop [i 0
         acc acc]
    (if (< i 8)
      (let [data-val (read-u64le buf (+ off (* i 8)))
            data-key (xor64 data-val (s64 (+ sec-off (* i 8))))
            acc (assoc acc (bit-xor i 1)
                       (add64 (nth acc (bit-xor i 1)) data-val))
            acc (assoc acc i
                       (add64 (nth acc i)
                              (m32to64 (second data-key) (first data-key))))]
        (recur (inc i) acc))
      acc)))

(defn- accumulate-stripes [acc buf off nb-stripes]
  (loop [n 0
         acc acc]
    (if (< n nb-stripes)
      (recur (inc n) (accumulate-512 acc buf (+ off (* n 64)) (* n 8)))
      acc)))

(defn- scramble-acc [acc]
  (mapv (fn [i]
          (let [a (nth acc i)
                a (xor64 a (shr64 a 47))
                a (xor64 a (s64 (+ 128 (* i 8))))]
            (mul64 a [0 prime32-1]))) (range 8)))

(defn- merge-accs [acc sec-off start]
  (xxh3-avalanche (loop [i 0
                         result start]
                    (if (< i 4)
                      (recur (inc i)
                             (add64 result
                                    (mul128-fold64 (xor64 (nth acc (* i 2))
                                                          (s64 (+ sec-off
                                                                  (* i 16))))
                                                   (xor64 (nth acc (inc (* i 2)))
                                                          (s64 (+ sec-off
                                                                  (* i 16) 8))))))
                      result))))

(defn- xxh3-long [buf len]
  (let [acc0 [[0 prime32-3]
              prime64-1
              prime64-2
              prime64-3
              prime64-4
              [0 prime32-2]
              prime64-5
              [0 prime32-1]]
        nb-blocks (quot (dec len) 1024)
        acc (loop [n 0
                   acc acc0]
              (if (< n nb-blocks)
                (recur (inc n)
                       (scramble-acc (accumulate-stripes acc buf (* n 1024) 16)))
                acc))
        nb-stripes (quot (- (dec len) (* nb-blocks 1024)) 64)
        acc (accumulate-stripes acc buf (* nb-blocks 1024) nb-stripes)
        acc (accumulate-512 acc buf (- len 64) 121)
        lo (merge-accs acc 11 (mul64 [0 len] prime64-1))
        hi (merge-accs acc 117
                       (xor64 (mul64 [0 len] prime64-2) [0xFFFFFFFF 0xFFFFFFFF]))]
    [hi lo]))

(defn- digest->bytes
  "Write a [hi lo] u64 pair as 16 big-endian bytes (hi first)."
  [[hi lo]]
  (let [out (new-bytes 16)
        limbs [(first hi) (second hi) (first lo) (second lo)]]
    (dotimes [w 4]
      (let [x (nth limbs w)]
        (put-byte! out (* w 4) (mod (quot x 16777216) 256))
        (put-byte! out (+ (* w 4) 1) (mod (quot x 65536) 256))
        (put-byte! out (+ (* w 4) 2) (mod (quot x 256) 256))
        (put-byte! out (+ (* w 4) 3) (mod x 256))))
    out))

(defn- xxh3-128-portable
  "Portable XXH3-128 (seed 0, default secret) of a byte array.
   Returns the 16-byte digest as a fresh platform byte array
   (byte[] on JVM, Uint8Array on CLJS), big-endian, high word first."
  [buf]
  (let [len (byte-len buf)
        result (cond
                 (zero? len) (xxh3-len-0)
                 (<= len 3) (xxh3-len-1to3 buf len)
                 (<= len 8) (xxh3-len-4to8 buf len)
                 (<= len 16) (xxh3-len-9to16 buf len)
                 (<= len 128) (xxh3-len-17to128 buf len)
                 (<= len 240) (xxh3-len-129to240 buf len)
                 :else (xxh3-long buf len))]
    (digest->bytes result)))

(defonce ^:private xxh3-128-native-impl (atom nil))

(defn register-xxh3-128-impl!
  "Adapter slot: register a host-native xxh3-128 fn (byte array -> 16-byte
   digest) to be used in place of the portable implementation. Register an
   impl ONLY after it has been measured byte-identical to the portable one
   via the KAT suite (test/eve/xxh3_kat_test.cljc) on that host."
  [f]
  (reset! xxh3-128-native-impl f))

(defn xxh3-128-bytes
  "eve's hash of a byte array: XXH3-128, seed 0, default secret.
   Returns 16 big-endian digest bytes, identical on JVM and CLJS."
  [buf]
  (if-let [f @xxh3-128-native-impl] (f buf) (xxh3-128-portable buf)))

(defn key-hash-bytes
  "HAMT placement hash of serialized key bytes — the 16-byte xxh3-128
   digest. HAMT navigation consumes this as bytes, never as an int."
  [buf]
  (xxh3-128-bytes buf))

(defn fold-hash-int
  "The int32-shaped fold of a 16-byte wide hash: XOR of its four
   big-endian u32 words, as a signed int32. This is what the
   language-visible `hash` returns for wide-hashable values."
  [hb]
  (i32/i32 (xor32 (xor32 (read-u32be hb 0) (read-u32be hb 4))
                  (xor32 (read-u32be hb 8) (read-u32be hb 12)))))

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
