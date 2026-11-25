(ns eve.dom.codec
  "eve.dom's frame codec: the bytes a guest (a worker, or a VS program) and the page's executor exchange for one proxy
   round trip. A proxy call is small and frequent, so this is a compact tagged binary format of its own
   (little-endian), not eve.wire's flat chunk: the 70e6f4c8 thread-submit regression showed what a full pack per
   message costs.

   ONE CODEC ON EVERY HOST: its bytes go through eve.flat-io's byte layer (doc/flat/FLAT-IO.md) — the writer is
   flat-io's buf, the reader reads a flat-io chunk — and its strings through eve.encoding. Each host's root of those
   seams is its fast path (the browser's flat-io/js, a DataView; VS's flat-io/vs, a B1 binary); nothing here names a
   host type.

   A request frame is a u32 op count, then ops; a reply frame is a u32 count of page-side errors met by queued ops,
   then one value per op that answers. A value is a tag byte and its payload:

     0 undefined   1 null   2 false   3 true   4 number (f64)   5 string (u32 length, UTF-8)
     6 handle (i32 handle, u8 kind 0 object / 1 function, string type: the constructor's name)
     7 array (u32 n, n values)   8 plain object (u32 n, n string keys each followed by its value)
     9 error (string name, string message)   10 callback (i32 id: a guest fn, run by a page-side stub)
     11 method (a function-valued property read by GET: the guest calls it by name on its owner)"
  (:require [eve.flat-io :as fio]
            [eve.encoding :as enc]))

(def T-UNDEF 0)
(def T-NULL 1)
(def T-FALSE 2)
(def T-TRUE 3)
(def T-NUM 4)
(def T-STR 5)
(def T-HANDLE 6)
(def T-ARRAY 7)
(def T-OBJECT 8)
(def T-ERROR 9)
(def T-CALLBACK 10)
(def T-METHOD 11)

;; ops
(def OP-GET 1)
(def OP-SET 2)
(def OP-CALL 3)
(def OP-CALLQ 4)
(def OP-APPLY 5)
(def OP-NEW 6)
(def OP-GLOBAL 7)
(def OP-RELEASE 8)
(def OP-HAS 9)
(def OP-KEYS 10)
(def OP-DEL 11)
(def OP-ALLOC 12)
(def OP-CALLAS 13)
(def OP-MORE 14)

;; ---------------------------------------------------------------------------------------------------------------
;; the writer: flat-io's growable buf (its offset width unused here)

(defn writer
  ([] (writer 4096))
  ([_n] (fio/buf 4)))

(defn pos "The write position." [w] (fio/buf-pos w))
(defn seek! "Move the write position back to p (a frame cut short)." [w p] (fio/buf-seek w p) w)
(defn clear! [w] (fio/buf-seek w 0) w)

(defn u8! [w x] (fio/buf-u8 w x))
(defn u32! [w x] (fio/buf-u32 w x))
(defn i32! [w x] (fio/buf-u32 w x))      ; the same four bytes: the low 32 bits, read back signed by r-i32
(defn f64! [w x] (fio/buf-f64 w x))

(defn str! [w s]
  (let [b (enc/utf8-bytes (str s))
        n (alength b)]
    (fio/buf-u32 w n)
    (fio/buf-bytes w b 0 n)))

(defn patch-u32! [w at x] (fio/buf-patch-u32 w at x))

(defn bytes
  "The written bytes, as the host's byte array (flat-io's chunk-bytes of them: a copy of exactly pos bytes)."
  [w]
  (fio/chunk-bytes (fio/buf-chunk w (fio/buf-pos w))))

(defn cut!
  "Keep only the bytes after position at, moved to just past the frame's u32 count (the tail of a frame that was
   split). flat-io's buf-bytes copies ascending, so the move to a lower offset reads each byte before it is written."
  [w at]
  (let [n (- (fio/buf-pos w) at)]
    (fio/buf-seek w 4)
    (fio/buf-bytes w (fio/buf-raw w) at n)))

;; ---------------------------------------------------------------------------------------------------------------
;; the reader: a flat-io chunk and a position

(defn reader
  "A reader over host bytes bs (the host's byte array, or a flat-io chunk)."
  [bs]
  (let [c (fio/chunk bs)]
    {:c c :n (fio/chunk-len c) :p (volatile! 0)}))

(defn- adv! [r k] (let [p @(:p r)] (vreset! (:p r) (+ p k)) p))

(defn r-u8 [r] (fio/u8 (:c r) (adv! r 1)))
(defn r-u32 [r] (fio/u32 (:c r) (adv! r 4)))
(defn r-i32 [r] (fio/i32 (:c r) (adv! r 4)))
(defn r-f64 [r] (fio/f64 (:c r) (adv! r 8)))

(defn r-str [r]
  (let [n (r-u32 r)]
    (fio/utf8 (:c r) (adv! r n) n)))

(defn more? [r] (< @(:p r) (:n r)))

;; ---------------------------------------------------------------------------------------------------------------
;; The proxy SharedArrayBuffer (cross-origin-isolated pages): a doorbell word the page waits on, then one slot per
;; thread id — control words (state, chunk length, total length, sequence) and SLOT bytes of frame. Plain numbers: the
;; browser transport's layout, shared by both of its ends.

(def NSLOT 128)
(def SLOT (* 64 1024))
(def HDR-WORDS 8)
(def DOORBELL 0)
(def S-IDLE 0)
(def S-REQUEST 1)
(def S-REPLY 2)
(def S-CHUNK 3)

(defn state-word [i] (+ HDR-WORDS (* 4 i)))
(defn len-word [i] (+ HDR-WORDS (* 4 i) 1))
(defn total-word [i] (+ HDR-WORDS (* 4 i) 2))
(defn data-at [i] (+ (* 4 (+ HDR-WORDS (* 4 NSLOT))) (* SLOT i)))
(defn sab-bytes [] (data-at NSLOT))
