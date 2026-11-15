(ns eve.region
  "Route 2 windows for the :teavm seat. Each window is a fixed base in the
   imported eve.region memory (Stage A). The directory words are the ones
   the Route 2 layout already has. This adapter does not allocate a journal,
   a liveness scan, a header counter, or an arena."
  (:require [eve.mem :as mem])
  (:import [EveRegion]
           [RegionBytes]
           [ThreadUnsafeRegionReq]))

(defn- word-byte [^long abs]
  (unchecked-byte
   (bit-and
    (unsigned-bit-shift-right
     (EveRegion/i32Load (bit-and abs (bit-not 3)))
     (* 8 (bit-and abs 3)))
    255)))

(defn- put-word-byte! [^long abs b]
  (let [aligned (bit-and abs (bit-not 3))
        sh (* 8 (bit-and abs 3))
        mask (bit-not (bit-shift-left 255 sh))
        w (EveRegion/i32Load aligned)
        nw (bit-or (bit-and w mask) (bit-shift-left (bit-and b 255) sh))]
    (EveRegion/i32Store aligned (unchecked-int nw))))

(deftype TeavmWindow [^long base ^long size]
  mem/IMemRegion
  (-byte-length [_] size)
  (-load-i32 [_ off] (EveRegion/i32Load (+ base (long off))))
  (-store-i32! [_ off v] (EveRegion/i32Store (+ base (long off)) (RegionBytes/bits v)))
  (-cas-i32! [_ off e d] (EveRegion/i32Cas (+ base (long off)) (RegionBytes/bits e) (RegionBytes/bits d)))
  (-add-i32! [_ off d] (EveRegion/i32Add (+ base (long off)) (RegionBytes/bits d)))
  (-sub-i32! [_ off d] (EveRegion/i32Add (+ base (long off)) (RegionBytes/bits (- (long d)))))
  (-exchange-i32! [_ off v] (EveRegion/i32Exchange (+ base (long off)) (RegionBytes/bits v)))
  (-load-i64 [_ off] (EveRegion/i64Load (+ base (long off))))
  (-store-i64! [_ off v] (EveRegion/i64Store (+ base (long off)) (long v)))
  (-cas-i64! [_ off e d] (EveRegion/i64Cas (+ base (long off)) (long e) (long d)))
  (-add-i64! [_ off d] (EveRegion/i64Add (+ base (long off)) (long d)))
  (-sub-i64! [_ off d] (EveRegion/i64Add (+ base (long off)) (long (- (long d)))))
  (-wait-i32! [_ off exp ms]
    (case (int (EveRegion/i32Wait (+ base (long off)) (RegionBytes/bits exp) (RegionBytes/bits ms)))
      0 :ok
      1 :not-equal
      :timed-out))
  (-notify-i32! [_ off n] (EveRegion/i32Notify (+ base (long off)) (RegionBytes/bits n)))
  (-supports-watch? [_] false)
  (-read-bytes [_ off len]
    (let [^bytes a (byte-array (int len))
          b (+ base (long off))]
      (dotimes [i (int len)]
        (RegionBytes/put a (int i) (int (word-byte (+ b i)))))
      a))
  (-write-bytes! [_ off src]
    (let [b (+ base (long off))
          n (alength ^bytes src)]
      (dotimes [i n]
        (put-word-byte! (+ b i) (RegionBytes/get src (int i))))))
  mem/ISyncableRegion
  (-sync! [_] (EveRegion/force) nil)
  (-sync-range! [_ _ _] (EveRegion/force) nil)
  mem/IHostAtomics
  (-atomic-rmw? [_] true)
  (-with-file-lock [_ _ f] (f)))

(defn- heap-load-i32 [^bytes a off]
  (bit-or (RegionBytes/get a (int off))
          (bit-shift-left (RegionBytes/get a (+ (int off) 1)) 8)
          (bit-shift-left (RegionBytes/get a (+ (int off) 2)) 16)
          (bit-shift-left (RegionBytes/get a (+ (int off) 3)) 24)))

(defn- heap-store-i32! [^bytes a off v]
  (let [u (bit-and (long v) 0xffffffff)]
    (RegionBytes/put a (int off) (int (bit-and u 255)))
    (RegionBytes/put a (+ (int off) 1) (int (bit-and (unsigned-bit-shift-right u 8) 255)))
    (RegionBytes/put a (+ (int off) 2) (int (bit-and (unsigned-bit-shift-right u 16) 255)))
    (RegionBytes/put a (+ (int off) 3) (int (unsigned-bit-shift-right u 24)))))

(deftype TeavmHeap [^bytes bytes]
  mem/IMemRegion
  (-byte-length [_] (alength bytes))
  (-load-i32 [_ off] (heap-load-i32 bytes (int off)))
  (-store-i32! [_ off v] (heap-store-i32! bytes (int off) (int v)))
  (-cas-i32! [_ off e d]
    (let [old (heap-load-i32 bytes (int off))]
      (when (= (int old) (int e)) (heap-store-i32! bytes (int off) (int d)))
      old))
  (-add-i32! [_ off d]
    (let [old (heap-load-i32 bytes (int off))]
      (heap-store-i32! bytes (int off) (unchecked-int (+ old (int d))))
      old))
  (-sub-i32! [_ off d]
    (let [old (heap-load-i32 bytes (int off))]
      (heap-store-i32! bytes (int off) (unchecked-int (- old (int d))))
      old))
  (-exchange-i32! [_ off v]
    (let [old (heap-load-i32 bytes (int off))]
      (heap-store-i32! bytes (int off) (int v))
      old))
  (-load-i64 [_ off]
    (bit-or (heap-load-i32 bytes (int off))
            (bit-shift-left (bit-and (heap-load-i32 bytes (+ (int off) 4)) 0xffffffff) 32)))
  (-store-i64! [_ off v]
    (heap-store-i32! bytes (int off) (unchecked-int v))
    (heap-store-i32! bytes (+ (int off) 4) (unchecked-int (unsigned-bit-shift-right (long v) 32))))
  (-cas-i64! [_ off e d]
    (let [old (mem/-load-i64 _ off)]
      (when (= old (long e)) (mem/-store-i64! _ off d))
      old))
  (-add-i64! [_ off d]
    (let [old (mem/-load-i64 _ off)]
      (mem/-store-i64! _ off (+ old (long d)))
      old))
  (-sub-i64! [_ off d]
    (let [old (mem/-load-i64 _ off)]
      (mem/-store-i64! _ off (- old (long d)))
      old))
  (-wait-i32! [_ _ exp _]
    (if (= (int exp) (heap-load-i32 bytes 0)) :timed-out :not-equal))
  (-notify-i32! [_ _ _] 0)
  (-supports-watch? [_] false)
  (-read-bytes [_ off len]
    (java.util.Arrays/copyOfRange bytes (int off) (+ (int off) (int len))))
  (-write-bytes! [_ off src]
    (System/arraycopy ^bytes src 0 bytes (int off) (alength ^bytes src)))
  mem/IHostAtomics
  (-atomic-rmw? [_] false)
  (-with-file-lock [_ _ f] (f)))

(defn lustre-region?
  "This seat's windows are the imported memory, not a Lustre mapping."
  [_]
  false)

(defn open-mmap-region
  ([path size] (open-mmap-region path size nil))
  ([path size _opts]
   (let [n (int size)
         base (ThreadUnsafeRegionReq/open (str path) n)]
     (when (neg? base)
       (throw (ex-info "eve.region: the Route 2 window was refused"
                       {:path (str path) :size n})))
     (->TeavmWindow base (EveRegion/openLength)))))

(defn make-heap-region [n]
  (->TeavmHeap (byte-array (int n))))

(defn sab-control-init!
  "The shared directory already exists in the imported memory. Print the
   discovery line the harness reads and return 0."
  []
  (println "EVE-SAB-CTRL 0")
  (flush)
  0)

(defn sab-await-manifest!
  "A fresh memory has an empty directory, which is a manifest of zero
   entries. A loaded snapshot is already in the memory before the body
   starts, so there is nothing further to wait for."
  [& _]
  true)

(defn sab-snapshot-names
  "Names are discovered by sab-window-state when a domain opens. The
   install pass has no separate name list."
  []
  #{})

(defn sab-window-state
  "The directory state of one window name, or nil when the name is absent."
  [name]
  (let [s (ThreadUnsafeRegionReq/state (str name))]
    (when (>= s 0) s)))

(defn sab-set-body-state! [_] nil)
