(ns eve.dom.codec
  "eve.dom's frame codec: the bytes a worker and the page (the UI, or screen, thread) exchange for one proxy round
   trip. A proxy call is small and frequent, so this is a compact tagged binary format of its own (little-endian),
   not eve.wire's flat chunk: the 70e6f4c8 thread-submit regression showed what a full pack per message costs.

   A request frame is a u32 op count, then ops; a reply frame is a u32 count of page-side errors met by queued ops,
   then one value per op that answers. A value is a tag byte and its payload:

     0 undefined   1 null   2 false   3 true   4 number (f64)   5 string (u32 length, UTF-8)
     6 handle (i32 handle, u8 kind 0 object / 1 function, string type: the constructor's name)
     7 array (u32 n, n values)   8 plain object (u32 n, n string keys each followed by its value)
     9 error (string name, string message)   10 callback (i32 id: a worker fn, run by a page-side stub)
     11 method (a function-valued property read by GET: the worker calls it by name on its owner)")

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

(def ^:private encoder (js/TextEncoder.))
(def ^:private decoder (js/TextDecoder.))

(deftype Writer [^:mutable buf ^:mutable dv ^:mutable u8 ^:mutable pos])

(defn writer
  ([] (writer 4096))
  ([n] (let [b (js/ArrayBuffer. n)] (Writer. b (js/DataView. b) (js/Uint8Array. b) 0))))

(defn clear! [^Writer w] (set! (.-pos w) 0) w)

(defn- ensure! [^Writer w n]
  (let [need (+ (.-pos w) n)]
    (when (> need (.-byteLength (.-buf w)))
      (let [nb (js/ArrayBuffer. (max need (* 2 (.-byteLength (.-buf w)))))
            nu (js/Uint8Array. nb)]
        (.set nu (.subarray (.-u8 w) 0 (.-pos w)))
        (set! (.-buf w) nb)
        (set! (.-dv w) (js/DataView. nb))
        (set! (.-u8 w) nu)))))

(defn u8! [^Writer w x]
  (ensure! w 1)
  (aset (.-u8 w) (.-pos w) x)
  (set! (.-pos w) (inc (.-pos w))))

(defn u32! [^Writer w x]
  (ensure! w 4)
  (.setUint32 (.-dv w) (.-pos w) x true)
  (set! (.-pos w) (+ 4 (.-pos w))))

(defn i32! [^Writer w x]
  (ensure! w 4)
  (.setInt32 (.-dv w) (.-pos w) x true)
  (set! (.-pos w) (+ 4 (.-pos w))))

(defn f64! [^Writer w x]
  (ensure! w 8)
  (.setFloat64 (.-dv w) (.-pos w) x true)
  (set! (.-pos w) (+ 8 (.-pos w))))

(defn str! [^Writer w s]
  (let [s (str s)
        n (* 3 (.-length s))]
    (ensure! w (+ 4 n))
    (let [at (.-pos w)
          r (.encodeInto encoder s (.subarray (.-u8 w) (+ at 4) (+ at 4 n)))
          k (.-written r)]
      (.setUint32 (.-dv w) at k true)
      (set! (.-pos w) (+ at 4 k)))))

(defn patch-u32! [^Writer w at x] (.setUint32 (.-dv w) at x true))

(defn view
  "The written bytes, as a view (no copy)."
  [^Writer w]
  (.subarray (.-u8 w) 0 (.-pos w)))

(defn cut!
  "Keep only the bytes after position at, moved to the front (the tail of a frame that was split)."
  [^Writer w at]
  (let [n (- (.-pos w) at)]
    (.copyWithin (.-u8 w) 4 at (.-pos w))
    (set! (.-pos w) (+ 4 n))))

(deftype Reader [dv u8 ^:mutable pos])

(defn reader
  "A reader over u8 (a Uint8Array over a non-shared buffer: TextDecoder refuses shared ones)."
  [^js u8]
  (Reader. (js/DataView. (.-buffer u8) (.-byteOffset u8) (.-byteLength u8)) u8 0))

(defn r-u8 [^Reader r]
  (let [x (aget (.-u8 r) (.-pos r))] (set! (.-pos r) (inc (.-pos r))) x))

(defn r-u32 [^Reader r]
  (let [x (.getUint32 (.-dv r) (.-pos r) true)] (set! (.-pos r) (+ 4 (.-pos r))) x))

(defn r-i32 [^Reader r]
  (let [x (.getInt32 (.-dv r) (.-pos r) true)] (set! (.-pos r) (+ 4 (.-pos r))) x))

(defn r-f64 [^Reader r]
  (let [x (.getFloat64 (.-dv r) (.-pos r) true)] (set! (.-pos r) (+ 8 (.-pos r))) x))

(defn r-str [^Reader r]
  (let [n (r-u32 r)
        at (.-pos r)]
    (set! (.-pos r) (+ at n))
    (.decode decoder (.subarray (.-u8 r) at (+ at n)))))

(defn more? [^Reader r] (< (.-pos r) (.-byteLength (.-u8 r))))

(defn copy-out
  "n bytes of a (possibly shared) Uint8Array from offset at, into a fresh unshared array."
  [^js src at n]
  (let [out (js/Uint8Array. n)]
    (.set out (.subarray src at (+ at n)))
    out))

(defn plain-object?
  "A plain JS object: its prototype is Object.prototype or null."
  [x]
  (and (some? x) (identical? "object" (goog/typeOf x))
       (let [p (js/Object.getPrototypeOf x)]
         (or (nil? p) (identical? p (.-prototype js/Object))))))

(defn data?
  "Does x copy by value (primitives, arrays and plain objects of them, to depth 8, no cycles)? A value carrying
   behaviour (a function anywhere inside) or a host object stays where it is, crossing as a handle."
  ([x] (data? x 0))
  ([x depth]
   (cond
     (or (nil? x) (undefined? x) (boolean? x) (number? x) (string? x)) true
     (> depth 8) false
     (js/Array.isArray x) (.every x #(data? % (inc depth)))
     (plain-object? x) (.every (js/Object.keys x) #(data? (aget x %) (inc depth)))
     :else false)))

(defn type-name
  "The constructor name of a page-side object, for the worker's prototype and method caches."
  [x]
  (try (let [c (.-constructor x)]
         (or (when c (.-name c)) "Object"))
       (catch :default _ "Object")))

;; The proxy SharedArrayBuffer (cross-origin-isolated pages): a doorbell word the page waits on, then one slot per
;; thread id — control words (state, chunk length, total length, sequence) and SLOT bytes of frame.
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
