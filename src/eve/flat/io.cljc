(ns eve.flat.io
  "The writer/reader surface of flat Eve (IMPL-PLAN §3.4, lane H8):
   the Transit-shaped stream API. A writer emits values one at a time;
   each value travels as its packed chunk (eve.flat.chunk, FORMAT-V1
   §2), framed by a 4-byte big-endian length. A reader consumes the
   same stream value by value, on any host the format reaches.

   Framing: a value is packed as the sole element of a vector, so
   scalars — which are never flat values on their own (§2) — travel
   in the same frame shape as collections and records. The frame is
   an internal detail: write! takes a value, read! answers one.

   The reader's only door for stream bytes is eve.flat.check:
   every frame's bytes pass open-checked and are vouched as a
   CheckedBytes handle before chunk/open sees them. There is no
   unchecked read in this namespace. A refusal — from the checker or
   from the framing — is a typed ex-info, never a host Error and
   never a partial value.

   Handlers are a read-side notion, keyed by the wire type key
   (FORMAT-V1 §6, the \"ns/Type\" string): when a value's root is a
   record whose key has a handler in the reader's :handlers, the
   handler answers for it, called with the record handle. With no
   handler, a :default handler is called with [key handle]; with
   neither, the value answers as the generic record itself — the
   floor of §6. Non-record values answer exactly as chunk/open
   answers them: statics read in place, scalars are themselves.

   Writers need no handlers: a record's type key and layout
   fingerprint go on the wire by construction (lane C1).

   JVM streams in this version; the ClojureScript constructors throw
   the typed not-built refusal (the chunk itself is host-identical —
   lane X1 — and a byte-source reader for CLJS is H3/H4 territory)."
  (:require [eve.flat.chunk :as chunk]
            ; the alias is exercised on the other reader pass; the require loads on both 
            #_{:clj-kondo/ignore [:unused-namespace]}   [eve.flat.check :as check])
  #?(:clj (:import [java.io ByteArrayInputStream ByteArrayOutputStream
                    Closeable File InputStream OutputStream]
                   ; the Arrays import is used below: the bare class name sits in the config's host-class unresolved-symbol exclusion (for the seats that reach it without an import), which blinds kondo to the import's use
                   #_{:clj-kondo/ignore [:unused-import]} [java.util Arrays])))

(def eof
  "What read! answers past the last value of a stream (the reader's
   :eof option replaces it)."
  :eve.flat.io/eof)

;; ---------------------------------------------------------------------------
;; framing
;; ---------------------------------------------------------------------------

#_{:clj-kondo/ignore [:unused-private-var]} ; used only on the :clj pass (write!, in the #?(:clj ...) section); no cljs caller exists
(defn- frame-bytes
  "The chunk bytes carrying one value: the value packed as the sole
   element of a vector (see the ns docstring)."
  ^bytes [v]
  (chunk/chunk-bytes (chunk/pack [v])))

#_{:clj-kondo/ignore [:unused-private-var]} ; used only on the :clj pass (the read side, below); no cljs caller exists
(defn- unframe
  "The value a frame's (already checked) chunk carries: element 0 of
   its root vector. A frame whose root is not a 1-vector is refused
   by name — the checker vouches for the chunk's form, not for this
   namespace's framing."
  [^bytes bs]
  (let [root (chunk/open bs)]
    (when-not (and (vector? root) (== 1 (count root)))
      (throw (ex-info "flat io: a frame's root is not a 1-vector"
                      {:eve.flat.io/check :bad-frame})))
    (nth root 0)))

#_{:clj-kondo/ignore [:unused-private-var]} ; used only on the :clj pass (write!/read refusals in the #?(:clj ...) sections); no cljs caller exists
(defn- refuse-frame
  [check msg & {:as data}]
  (throw (ex-info (str "flat io refused (" (name check) "): " msg)
                  (assoc data :eve.flat.io/check check))))

;; ---------------------------------------------------------------------------
;; the writer
;; ---------------------------------------------------------------------------

;; State leaves a deftype only through protocol methods implemented
;; inside it: this toolchain compiles deftype fields package-private,
;; so field access from free functions throws (the same wall lane Z1
;; hit with FlatSio; its IFlatSioState protocol is the pattern).

(defprotocol IWriterState
  (-wout [w])
  (-wclosed? [w]))

#?(:clj
   (deftype Writer [^OutputStream out ^:volatile-mutable closed]
     IWriterState
     (-wout [_] out)
     (-wclosed? [_] closed)
     Closeable
     (close [_]
       (when-not closed
         (set! closed true)
         (.close out)))))

#?(:clj
   (defn writer
     "A writer over an OutputStream. Values are written with write!;
      each write! emits its chunk and flushes — the stream is
      incremental, a reader in another process sees values as they
      are written. Options: none in v1 (see the ns docstring)."
     ([out] (writer out {}))
     ([^OutputStream out _opts]
      (Writer. out false))))

#?(:clj
   (defn write!
     "Emit one value on w: its packed chunk, framed. Answers w."
     [^Writer w v]
     (when (-wclosed? w)
       (refuse-frame :writer-closed "write! on a closed writer"))
     (let [^OutputStream out (-wout w)
           ^bytes bs (frame-bytes v)
           n (alength bs)]
       (.write out (unchecked-byte (bit-shift-right n 24)))
       (.write out (unchecked-byte (bit-shift-right n 16)))
       (.write out (unchecked-byte (bit-shift-right n 8)))
       (.write out (unchecked-byte n))
       (.write out bs)
       (.flush out)
       w)))

#?(:clj
   (defn flush!
     "Flush w's stream. (write! already flushes per value; flush!
      exists for writers wrapped around this one.)"
     [^Writer w]
     (.flush ^OutputStream (-wout w))
     w))

#?(:clj
   (defn close!
     "Close w's stream. Answers w."
     [^Writer w]
     (.close w)
     w))

;; ---------------------------------------------------------------------------
;; the reader
;; ---------------------------------------------------------------------------

(defprotocol IReaderState
  (-rin [r])
  (-rhandlers [r])
  (-rdefault [r])
  (-reof [r])
  (-rclosed? [r]))

#?(:clj
   (deftype Reader [^InputStream in handlers default eof-val ^:volatile-mutable closed]
     IReaderState
     (-rin [_] in)
     (-rhandlers [_] handlers)
     (-rdefault [_] default)
     (-reof [_] eof-val)
     (-rclosed? [_] closed)
     Closeable
     (close [_]
       (when-not closed
         (set! closed true)
         (.close in)))))

#?(:clj
   (defn reader
     "A reader over an InputStream. Options:
       :handlers — {type-key (fn [record-handle] answer)}
       :default  — (fn [type-key record-handle] answer) for records
                   no handler claims; without it the generic record
                   itself is the answer
       :eof      — what read! answers past the last value
                   (default eve.flat.io/eof)
     Every frame's bytes pass eve.flat.check/open-checked before
     they are opened; a refusal propagates as its typed ex-info."
     ([in] (reader in {}))
     ([^InputStream in opts]
      (Reader. in (get opts :handlers {}) (get opts :default)
               (get opts :eof eof) false))))

#?(:clj
   (defn- read-n
     "Up to n bytes from in as a byte array of what was read: empty
      at a clean end of stream, short only when the stream ends
      mid-way."
     ^bytes [^InputStream in ^long n]
     (let [buf (byte-array n)]
       (loop [off 0]
         (if (== off n)
           buf
           (let [r (.read in buf (int off) (int (- n off)))]
             (if (neg? r)
               (if (zero? off) (byte-array 0) (Arrays/copyOf buf (int off)))
               (recur (+ off r)))))))))

#?(:clj
   (defn- read-frame
     "The next frame's vouched chunk bytes, or nil at a clean end of
      stream. A stream cut mid-header or mid-chunk is a typed
      refusal; a malformed chunk is check's typed refusal."
     [^Reader r]
     (let [^InputStream in (-rin r)
           hdr (read-n in 4)]
       (cond
         (zero? (alength hdr)) nil
         (< (alength hdr) 4)
         (refuse-frame :truncated-frame
                       "the stream ends inside a frame header")
         :else
         (let [n (bit-or (bit-shift-left (bit-and (aget hdr 0) 0xFF) 24)
                         (bit-shift-left (bit-and (aget hdr 1) 0xFF) 16)
                         (bit-shift-left (bit-and (aget hdr 2) 0xFF) 8)
                         (bit-and (aget hdr 3) 0xFF))
               bs (read-n in n)]
           (when (< (alength bs) n)
             (refuse-frame :truncated-frame
                           "the stream ends inside a frame"
                           :wanted n :got (alength bs)))
           ;; the checked door: vouched before opened, no other way in
           (check/checked-bytes (check/open-checked bs)))))))

#?(:clj
   (defn- dispatch
     "Handler dispatch by wire type key. A record handle is claimed
      by its key's handler, else by the default, else answers as
      itself — the generic record when this reader has no sister
      for it, which is the H8.2 default."
     [^Reader r v]
     (let [k (chunk/record-tag v)]
       (if (nil? k)
         v
         (let [h (get (-rhandlers r) k)]
           (cond
             (some? h) (h v)
             (some? (-rdefault r)) ((-rdefault r) k v)
             :else v))))))

#?(:clj
   (defn read!
     "The next value of r's stream, or the reader's eof value past
      the last one. Records are answered per the handler dispatch;
      other values as chunk/open answers them."
     [^Reader r]
     (when (-rclosed? r)
       (refuse-frame :reader-closed "read! on a closed reader"))
     (let [bs (read-frame r)]
       (if (nil? bs)
         (-reof r)
         (dispatch r (unframe bs))))))

#?(:clj
   (defn read-all
     "Every remaining value of r's stream, as a vector."
     [^Reader r]
     (loop [acc []]
       (let [v (read! r)]
         (if (= v (-reof r))
           acc
           (recur (conj acc v)))))))

;; ---------------------------------------------------------------------------
;; files and byte arrays — the same doors, named for the common shapes
;; ---------------------------------------------------------------------------

#?(:clj
   (defn file-writer
     "A writer over the file f (a java.io.File or a path string),
      created/truncated."
     ([f] (file-writer f {}))
     ([f opts]
      (writer (java.io.FileOutputStream. (if (instance? File f) f (File. (str f))))
              opts))))

#?(:clj
   (defn file-reader
     "A reader over the file f (a java.io.File or a path string)."
     ([f] (file-reader f {}))
     ([f opts]
      (reader (java.io.FileInputStream. (if (instance? File f) f (File. (str f))))
              opts))))

#?(:clj
   (defn write-bytes
     "The bytes of a whole stream of values, as one byte array."
     ^bytes [values]
     (let [baos (ByteArrayOutputStream.)
           w (writer baos)]
       (doseq [v values] (write! w v))
       (.toByteArray baos))))

#?(:clj
   (defn read-bytes
     "Every value of the stream in bs, per the reader opts."
     ([bs] (read-bytes bs {}))
     ([^bytes bs opts]
      (read-all (reader (ByteArrayInputStream. bs) opts)))))

;; ---------------------------------------------------------------------------
;; ClojureScript: no streams in v1 — the typed refusal, as eve.flat's
;; own CLJS delegate answers.
;; ---------------------------------------------------------------------------

#?(:cljs
   (defn writer [_ & _]
     (throw (ex-info "eve.flat.io: streams are not built on this host yet"
                     {:eve.flat/not-built 'eve.flat.io/writer}))))

#?(:cljs
   (defn reader [_ & _]
     (throw (ex-info "eve.flat.io: streams are not built on this host yet"
                     {:eve.flat/not-built 'eve.flat.io/reader}))))
