(ns eve.region
  "eve's regions (eve.mem's IMemRegion and IHostAtomics; HOST-CONTRACT §1-2) over ValueScript's own linear memory. A
   region is its first byte's address, a number: a window of consecutive slab regions no allocator hands out
   ((mmap/reserve! n)) for a heap region, a file's pages mapped over a fresh window (vs.mmap/map-file!, under
   vs-eve-host's doorbell) for an mmap region — the same files eve's other hosts map. eve.slab-io (adapters/slab-io/vs)
   reads and writes them with the program-runtime mmap arms directly; IMemRegion on a number does the same for every
   other caller: plain loads and stores, the seq-cst atomics, memory.atomic.wait32 / notify. The wasm memory is
   shared, so every thread of the program sees the same bytes.

   Like eve's JVM region (bb-load-i32), an aligned offset takes the atomic op and an unaligned one a plain load or
   store: the HAMT writes a node's i32 fields at any byte offset, and the atomic arm traps there."
  (:require [eve.mem :as mem]
            [vs.mmap :as vmm]))

(def ^:private sizes (atom {}))   ;; a region's address -> its byte length

(extend-protocol mem/IHostAtomics
  Object
  (-atomic-rmw? [_] true)
  (-with-file-lock [_ _byte-off f] (f)))

(defn- xchg32 [a v]
  (loop [] (let [o (mmap/load-i32 a)] (if (= o (mmap/cas-i32! a o v)) o (recur)))))

(defn- add64 [a d]
  (loop [] (let [o (mmap/load-i64 a)] (if (= o (mmap/cas-i64! a o (+ o d))) o (recur)))))

(extend-protocol mem/IMemRegion
  Long
  (-byte-length [r] (get @sizes r))
  (-load-i32 [r off] (let [a (+ r off)] (if (zero? (bit-and a 3)) (mmap/load-i32 a) (mmap/i32 a))))
  (-store-i32! [r off v]
    (let [a (+ r off)] (if (zero? (bit-and a 3)) (mmap/store-i32! a (unchecked-int v)) (mmap/i32! a (unchecked-int v))))
    nil)
  (-cas-i32! [r off expected desired] (mmap/cas-i32! (+ r off) (unchecked-int expected) (unchecked-int desired)))
  (-add-i32! [r off delta] (mmap/add-i32! (+ r off) delta))
  (-sub-i32! [r off delta] (mmap/add-i32! (+ r off) (- delta)))
  (-exchange-i32! [r off v] (xchg32 (+ r off) (unchecked-int v)))
  (-load-i64 [r off] (let [a (+ r off)] (if (zero? (bit-and a 7)) (mmap/load-i64 a) (mmap/i64 a))))
  (-store-i64! [r off v]
    (let [a (+ r off)] (if (zero? (bit-and a 7)) (mmap/store-i64! a v) (mmap/i64! a v)))
    nil)
  (-cas-i64! [r off expected desired] (mmap/cas-i64! (+ r off) expected desired))
  (-add-i64! [r off delta] (add64 (+ r off) delta))
  (-sub-i64! [r off delta] (add64 (+ r off) (- delta)))
  (-wait-i32! [r off expected timeout-ms]
    (let [w (mmap/wait-i32 (+ r off) (unchecked-int expected) timeout-ms)]
      (cond (= w 0) :ok (= w 1) :not-equal :else :timed-out)))
  (-notify-i32! [r off n] (mmap/notify! (+ r off) n))
  (-supports-watch? [_] true)
  (-read-bytes [r off len]
    (let [b (byte-array len) a (+ r off)]
      (loop [i 0] (when (< i len) (aset b i (unchecked-byte (mmap/u8 (+ a i)))) (recur (inc i))))
      b))
  (-write-bytes! [r off src]
    (let [a (+ r off) n (alength src)]
      (loop [i 0] (when (< i n) (mmap/u8! (+ a i) (bit-and (aget src i) 0xFF)) (recur (inc i)))))
    nil))

(defn make-heap-region
  "A region of size-bytes of this program's memory: a fresh window's address (its bytes as the slab regions held them:
   a fresh claim is zeroed by the wasm memory's growth, a reused one is not)."
  [size-bytes]
  (let [w (mmap/reserve! size-bytes)]
    (when (nil? w) (throw (ex-info "eve.region: no window of that size left in the slab" {:size size-bytes})))
    (swap! sizes assoc w size-bytes)
    w))

(defn open-mmap-region
  "The file at path mapped shared over a fresh window, [0, size-bytes) of it (HOST-CONTRACT §1.2: created when
   missing, grown and never truncated, extended bytes read as zero): the window's address. path is the host's (under
   vs-eve-host's --eve-root); a program run without the doorbell host throws, naming it."
  ([path-str size-bytes] (open-mmap-region path-str size-bytes nil))
  ([path-str size-bytes _opts]
   (let [w (:addr (vmm/map-file! path-str size-bytes {:min size-bytes :create? true}))]
     (swap! sizes assoc w size-bytes)
     w)))

(defn lustre-region?
  "true for a region whose atomics are fcntl byte-range locks (a Lustre mount): never here."
  [_r]
  false)
