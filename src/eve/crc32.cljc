(ns eve.crc32
  "CRC-32 (IEEE 802.3: the reflected polynomial 0xEDB88320, initial value and
   final xor 0xFFFFFFFF — java.util.zip.CRC32's and zlib's) of a byte-array
   range, answered as a signed i32: the form eve.jlog stores, little-endian.

   One portable definition (a byte-at-a-time table walk), and the host's own
   where it has a faster one: java.util.zip.CRC32 on the JVM and babashka,
   zlib.crc32 on Node (22.2 and later; the portable walk where it is absent),
   and on jolt the slicing-by-8 over the byte array's own Chez bytevector that
   the jolt journal adapter carried (moved here so it is not lost when the
   per-host journals go). Every arm answers the same value for the same bytes:
   eve.jlog-test holds them to java.util.zip.CRC32's goldens."
  (:refer-clojure :exclude [aget unsigned-bit-shift-right])
  (:require
   [eve.host :refer [aget unsigned-bit-shift-right]]
   [eve.int32 :as i32]
   #?(:jolt [jolt.scheme :as scheme])))

(def ^:private table
  "The 256-entry table of the reflected polynomial, each entry in [0, 2^32)."
  (vec (for [n (range 256)]
         (loop [c n k 0]
           (if (< k 8)
             (recur (if (zero? (bit-and c 1))
                      (unsigned-bit-shift-right c 1)
                      (bit-xor 0xEDB88320 (unsigned-bit-shift-right c 1)))
                    (inc k))
             c)))))

(defn crc32-portable
  "CRC-32 of byte array B from OFF for LEN bytes, by the table walk every host
   can run. A signed i32."
  [^bytes b off len]
  (let [end (+ off len)]
    (loop [c 0xFFFFFFFF
           i off]
      (if (< i end)
        (recur (bit-xor (unsigned-bit-shift-right c 8)
                        (nth table (bit-and (bit-xor c (aget b i)) 0xFF)))
               (inc i))
        (i32/i32 (bit-xor c 0xFFFFFFFF))))))

#?(:jolt
   (scheme/eval-string
    "(begin
       (define eve-crc32-t
         (let ((t (make-vector 8)))
           (let loop ((k 0))
             (unless (fx>= k 8)
               (vector-set! t k (make-fxvector 256))
               (loop (fx+ k 1))))
           (let ((t0 (vector-ref t 0)))
             (let loop ((n 0))
               (unless (fx>= n 256)
                 (let inner ((c n) (i 0))
                   (if (fx< i 8)
                       (inner (fxand (if (fxeven? c)
                                         (fxsrl c 1)
                                         (fxxor #xEDB88320 (fxsrl c 1)))
                                     #xFFFFFFFF)
                              (fx+ i 1))
                       (fxvector-set! t0 n c)))
                 (loop (fx+ n 1))))
             (let loopk ((k 1))
               (unless (fx>= k 8)
                 (let ((tk (vector-ref t k))
                       (tp (vector-ref t (fx- k 1))))
                   (let loopn ((n 0))
                     (unless (fx>= n 256)
                       (let ((c (fxvector-ref tp n)))
                         (fxvector-set! tk n (fxand (fxxor (fxsrl c 8)
                                                           (fxvector-ref t0 (fxand c 255)))
                                                    #xFFFFFFFF)))
                       (loopn (fx+ n 1)))))
                 (loopk (fx+ k 1)))))
           t))
       (define (eve-crc32 a off len)
         (let* ((bv (jolt-array-vec a))
                (t eve-crc32-t)
                (t0 (vector-ref t 0)) (t1 (vector-ref t 1))
                (t2 (vector-ref t 2)) (t3 (vector-ref t 3))
                (t4 (vector-ref t 4)) (t5 (vector-ref t 5))
                (t6 (vector-ref t 6)) (t7 (vector-ref t 7)))
           (let loop ((c #xFFFFFFFF) (i 0))
             (cond
               ((fx<= (fx+ i 8) len)
                (let* ((x (fxand (fxxor c (bytevector-u32-ref bv (fx+ off i) (endianness little)))
                                 #xFFFFFFFF))
                       (y (bytevector-u32-ref bv (fx+ off i 4) (endianness little)))
                       (c2 (fxand (fxxor (fxvector-ref t7 (fxand x 255))
                                         (fxvector-ref t6 (fxand (fxsrl x 8) 255))
                                         (fxvector-ref t5 (fxand (fxsrl x 16) 255))
                                         (fxvector-ref t4 (fxsrl x 24))
                                         (fxvector-ref t3 (fxand y 255))
                                         (fxvector-ref t2 (fxand (fxsrl y 8) 255))
                                         (fxvector-ref t1 (fxand (fxsrl y 16) 255))
                                         (fxvector-ref t0 (fxsrl y 24)))
                                  #xFFFFFFFF)))
                  (loop c2 (fx+ i 8))))
               ((fx< i len)
                (loop (fxand (fxxor (fxsrl c 8)
                                    (fxvector-ref t0 (fxand (fxxor c (bytevector-u8-ref bv (fx+ off i))) 255)))
                             #xFFFFFFFF)
                      (fx+ i 1)))
               (else (fxxor c #xFFFFFFFF))))))
       #t)"))

#?(:jolt (def ^:private chez-crc32 (scheme/proc "eve-crc32")))

#?(:cljs
   (def ^:private zlib-crc32
     "Node's zlib.crc32 (22.2 and later), or nil: an older Node, or no require."
     (try
       (when (exists? js/require)
         (let [f (unchecked-get (js/require "zlib") "crc32")]
           (when (fn? f) f)))
       (catch :default _ nil))))

(defn crc32
  "CRC-32 of byte array B from OFF for LEN bytes, by the host's fastest arm. A
   signed i32."
  [b off len]
  #?(:jolt (i32/i32 (chez-crc32 b off len))
     :glj (crc32-portable b off len)
     :jank (crc32-portable b off len)
     :clj (let [c (java.util.zip.CRC32.)]
            (.update c ^bytes b (int off) (int len))
            (i32/i32 (.getValue c)))
     :cljs (if zlib-crc32
             (i32/i32 (zlib-crc32 (.subarray b off (+ off len))))
             (crc32-portable b off len))
     :default (crc32-portable b off len)))
