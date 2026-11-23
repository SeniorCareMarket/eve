;; eve.flat.container — lane C3: the container (IMPL-PLAN §3.3 C3, FORMAT-V1 §2, §9).
;;
;; A flat value past one chunk is chunks plus a directory. A container holds up to
;; sixteen chunks under one directory and is the unit of compression and — for an
;; image — the unit of placement. The container reads only the FORMAT-V1 §2 header
;; fields of a chunk (magic, version, kind, length at bytes 0/4/5/8); what is inside
;; a packed chunk is C1's business, what is inside an image chunk is §9's tables.
;;
;; File form (§2):  chunk* directory-chunk tail
;;   tail := [directory offset u64 LE]["EVS1" u32][0 u32]        (16 bytes, at the end)
;;
;; Directory chunk payload (after its 32-byte header, kind 2):
;;   [n varint][root chunk varint][root offset u32]
;;   per chunk, in order: [first-key length varint][first-key bytes][chunk length u32]
;; First-keys are the chunk's smallest key as Eve scalar bytes, strictly ascending —
;; Eve's canonical order is ascending key bytes, so a lookup binary-searches the
;; directory and opens exactly one chunk. A chunk's offset in the file form is the
;; sum of the lengths before it; the directory stores lengths, not offsets, so the
;; same directory serves the in-memory and the serialized container. A keyless
;; container (an image: chunks are units, not key ranges) stores key length 0 and
;; refuses key lookup.
(ns eve.flat.container
  (:require [eve.encoding :as enc]
            [eve.flat.contract :as contract])
  ; the Arrays import is used below: the bare class name sits in the config's host-class unresolved-symbol exclusion (for the seats that reach it without an import), which blinds kondo to the import's use
  #?(:clj (:import #_{:clj-kondo/ignore [:unused-import]} [java.util Arrays]
                   [java.io ByteArrayOutputStream]
                   [java.util.zip Deflater Inflater])))

(def header-size contract/header-size)
(def max-chunks 16)
(def ^:private tail-size 16)

(def kind-packed contract/kind-packed)
(def kind-image contract/kind-image)
(def kind-directory contract/kind-directory)

;; ---------------------------------------------------------------------------
;; bytes: little-endian fields, varints, unsigned key order
;; ---------------------------------------------------------------------------

(defn- ub ^long [^bytes b ^long i] (bit-and (long (aget b (int i))) 0xFF))

(defn- ru16 ^long [^bytes b ^long i] (bit-or (ub b i) (bit-shift-left (ub b (inc i)) 8)))

(defn- ru32 ^long [^bytes b ^long i] (bit-or (ru16 b i) (bit-shift-left (ru16 b (+ i 2)) 16)))

(defn- ru64 ^long [^bytes b ^long i] (bit-or (ru32 b i) (bit-shift-left (ru32 b (+ i 4)) 32)))

(defn- wu64 [^bytes b ^long i ^long v]
  (dotimes [k 8] (aset b (+ i k) (unchecked-byte (bit-shift-right v (* 8 k))))))

(defn- read-varint
  "[value next-pos]"
  [^bytes b ^long i]
  (loop [i i sh 0 v 0]
    (let [x (ub b i)]
      (if (< x 128) [(bit-or v (bit-shift-left x sh)) (inc i)]
          (recur (inc i) (+ sh 7) (bit-or v (bit-shift-left (bit-and x 127) sh)))))))

(defn compare-key-bytes
  "Unsigned lexicographic order on key bytes — Eve's canonical key order (§4)."
  ^long [^bytes a ^bytes b]
  (let [n (min (alength a) (alength b))]
    (loop [i 0]
      (if (== i n)
        (compare (alength a) (alength b))
        (let [d (- (ub a i) (ub b i))]
          (if (zero? d) (recur (inc i)) d))))))

(defn- slice ^bytes [^bytes b ^long off ^long len]
  #?(:clj (Arrays/copyOfRange b (int off) (int (+ off len)))
     :cljs (.slice b off (+ off len))))

(defn- concat-bytes ^bytes [parts]
  #?(:clj (let [out (ByteArrayOutputStream.)]
            (doseq [^bytes p parts] (.write out p 0 (alength p)))
            (.toByteArray out))
     :cljs (throw (ex-info "eve.flat.container: concat not built on this host" {:check :not-built}))))

;; ---------------------------------------------------------------------------
;; the §2 chunk header — the only part of a chunk this namespace reads
;; ---------------------------------------------------------------------------

(defn- magic-ok? [^bytes b ^long off]
  (and (== 0x45 (ub b off)) (== 0x56 (ub b (+ off 1))) (== 0x53 (ub b (+ off 2))) (== 0x31 (ub b (+ off 3)))))

(defn- header-at
  "The §2 header of the chunk starting at off in b, parsed by the frozen contract
   (eve.flat.contract/read-header, which refuses a bad magic by name) plus this
   lane's structural checks: version, and a length that fits the bytes given."
  [^bytes b ^long off]
  (when-not (<= (+ off header-size) (alength b))
    (throw (ex-info "eve.flat.container: no room for a chunk header at this offset"
                    {:check :length :offset off})))
  (let [h (contract/read-header b off)
        kind (if (keyword? (:kind h))
               ({:packed kind-packed :image kind-image
                 :directory kind-directory :raw contract/kind-raw} (:kind h))
               (:kind h))
        length (:length h)]
    (when (> (:version h) 1)
      (throw (ex-info "eve.flat.container: chunk version above this reader's"
                      {:check :version :version (:version h)})))
    (when (or (< length header-size) (not (zero? (rem length 8))) (> (+ off length) (alength b)))
      (throw (ex-info "eve.flat.container: a chunk length that does not fit its bytes"
                      {:check :length :length length :offset off})))
    {:kind kind :length length :root (:root h)
     :symtab (:symtab h) :shapetab (:shapetab h) :typetab (:typetab h)}))

(defn chunk-kind ^long [^bytes chunk] (:kind (header-at chunk 0)))
(defn chunk-length ^long [^bytes chunk] (:length (header-at chunk 0)))
(defn chunk-root-offset ^long [^bytes chunk] (:root (header-at chunk 0)))

(defn- check-chunk
  "A standalone chunk byte array: header sane and length = the array's length."
  [^bytes chunk]
  (let [h (header-at chunk 0)]
    (when-not (== (:length h) (alength chunk))
      (throw (ex-info "eve.flat.container: chunk length is not its byte count"
                      {:check :length :length (:length h) :bytes (alength chunk)})))
    h))

;; ---------------------------------------------------------------------------
;; building a container
;; ---------------------------------------------------------------------------

(defn make-container
  "entries: [{:bytes chunk-bytes :first-key key-bytes-or-nil} …] (1 to 16), root
   {:chunk i :offset n}. Either every entry has a :first-key (strictly ascending)
   or none does (an image container: units, not key ranges)."
  [entries root]
  (when-not (<= 1 (count entries) max-chunks)
    (throw (ex-info "eve.flat.container: a container holds one to sixteen chunks"
                    {:check :chunk-count :n (count entries)})))
  (doseq [e entries] (check-chunk (:bytes e)))
  (let [keys (map :first-key entries)]
    (when (and (some some? keys) (not (every? some? keys)))
      (throw (ex-info "eve.flat.container: first-keys are all-or-none" {:check :first-keys})))
    (when (every? some? keys)
      (doseq [[a b] (partition 2 1 keys)]
        (when-not (neg? (compare-key-bytes a b))
          (throw (ex-info "eve.flat.container: first-keys must strictly ascend"
                          {:check :first-keys}))))))
  {:chunks (mapv :bytes entries)
   :first-keys (mapv :first-key entries)
   :root root})

;; ---------------------------------------------------------------------------
;; the directory chunk
;; ---------------------------------------------------------------------------

#?(:clj
   (defn- varint-into [^ByteArrayOutputStream out ^long v]
     (loop [v v]
       (if (< v 128)
         (.write out (int v))
         (do (.write out (int (bit-or (bit-and v 127) 128)))
             (recur (bit-shift-right v 7)))))))

#?(:clj
   (defn- u32-into [^ByteArrayOutputStream out ^long v]
     (dotimes [k 4] (.write out (int (bit-and (bit-shift-right v (* 8 k)) 0xFF))))))

(defn directory-bytes
  "The container's directory as a kind-2 chunk (§2)."
  ^bytes [{:keys [chunks first-keys root]}]
  #?(:cljs (throw (ex-info "eve.flat.container: directory encode not built on this host" {:check :not-built}))
     :clj (let [payload (ByteArrayOutputStream.)]
            (varint-into payload (count chunks))
            (varint-into payload (:chunk root))
            (u32-into payload (:offset root))
            (doseq [[kb ^bytes chunk] (map vector first-keys chunks)]
              (varint-into payload (if kb (alength kb) 0))
              (when kb (.write payload kb 0 (alength kb)))
              (u32-into payload (alength chunk)))
            (let [body (.toByteArray payload)
                  total (* 8 (quot (+ header-size (alength body) 7) 8))
                  out (byte-array total)]
              (contract/write-header! out 0 {:kind :directory :length total :root header-size})
              (System/arraycopy body 0 out header-size (alength body))
              out))))

(defn container-bytes
  "The file form (§2): the chunks, the directory chunk, the 16-byte tail."
  ^bytes [container]
  (let [chunks (:chunks container)
        dir (directory-bytes container)
        dir-offset (reduce + (map (fn [b] (alength b)) chunks))
        tail (byte-array tail-size)]
    (wu64 tail 0 dir-offset)
    (enc/arraycopy (enc/utf8-bytes "EVS1") 0 tail 8 4)
    (concat-bytes (conj (vec chunks) dir tail))))

(defn read-directory
  "The directory of a container in file form. Reads the tail and the directory
   chunk only — no chunk payload is touched."
  [^bytes fb]
  (let [n (alength fb)]
    (when-not (and (<= tail-size n) (magic-ok? fb (- n 8)))
      (throw (ex-info "eve.flat.container: no EVS1 tail" {:check :tail})))
    (let [dir-offset (ru64 fb (- n tail-size))
          h (header-at fb dir-offset)]
      (when-not (== kind-directory (:kind h))
        (throw (ex-info "eve.flat.container: the tail does not point at a directory"
                        {:check :directory-kind :kind (:kind h)})))
      (let [p0 (+ dir-offset header-size)
            [cnt p] (read-varint fb p0)
            [root-chunk p] (read-varint fb p)
            root-offset (ru32 fb p)
            p (+ p 4)]
        (loop [i 0 p p off 0 entries []]
          (if (== i cnt)
            {:entries entries :root {:chunk root-chunk :offset root-offset} :dir-offset dir-offset}
            (let [[kl p] (read-varint fb p)
                  kb (when (pos? kl) (slice fb p kl))
                  len (ru32 fb (+ p kl))]
              (recur (inc i) (+ p kl 4) (+ off len)
                     (conj entries {:first-key kb :length len :offset off})))))))))

;; ---------------------------------------------------------------------------
;; finding and opening exactly one chunk
;; ---------------------------------------------------------------------------

(defn find-chunk-idx
  "Binary search over the directory's first-keys: the chunk whose key range holds
   key-bytes — the greatest first-key ≤ key — or nil when key is below the first."
  [directory ^bytes key-bytes]
  (let [entries (:entries directory)]
    (when (nil? (:first-key (first entries)))
      (throw (ex-info "eve.flat.container: this container is not key-addressable"
                      {:check :not-keyed})))
    (loop [lo 0 hi (dec (count entries)) found nil]
      (if (> lo hi)
        found
        (let [mid (quot (+ lo hi) 2)
              c (compare-key-bytes key-bytes (:first-key (nth entries mid)))]
          (cond (zero? c) mid
                (neg? c) (recur lo (dec mid) found)
                :else (recur (inc mid) hi mid)))))))

(defn chunk-bytes-at
  "Chunk idx's bytes, sliced out of the file form — the one chunk an open touches."
  ^bytes [^bytes fb directory ^long idx]
  (let [e (nth (:entries directory) idx)]
    (slice fb (:offset e) (:length e))))

(defn open-value-chunk
  "Find key-bytes' chunk by binary search and open exactly that one:
   (open-fn chunk-bytes). open-fn is the chunk reader's (C1's) business."
  [^bytes fb directory ^bytes key-bytes open-fn]
  (let [idx (find-chunk-idx directory key-bytes)]
    (when (nil? idx)
      (throw (ex-info "eve.flat.container: key below the container's first key"
                      {:check :below-first-key})))
    (open-fn (chunk-bytes-at fb directory idx))))

;; ---------------------------------------------------------------------------
;; entering a stream at any chunk
;; ---------------------------------------------------------------------------

(defn stream-chunk-offset
  "The offset of chunk idx in a stream of chunks, walking header lengths only —
   no chunk is opened and no payload is read."
  ^long [^bytes stream ^long idx]
  (loop [i 0 off 0]
    (if (== i idx)
      off
      (recur (inc i) (+ off (:length (header-at stream off)))))))

(defn chunk-at-offset
  "The chunk at a stream offset, checked against its header and copied out."
  ^bytes [^bytes stream ^long off]
  (let [h (header-at stream off)]
    (slice stream off (:length h))))

;; ---------------------------------------------------------------------------
;; appending (C3.4): a new chunk and a new root; the old container is untouched
;; ---------------------------------------------------------------------------

(defn append-chunk
  "A new container with entry's chunk appended and the root moved to it (the
   entry's :root-offset, or the chunk header's root). The old container — its
   chunks, its directory, its written bytes — still reads the old value."
  [container {:keys [bytes first-key root-offset] :as _entry}]
  (let [chunks (conj (:chunks container) bytes)
        keys (conj (:first-keys container) first-key)]
    (when (> (count chunks) max-chunks)
      (throw (ex-info "eve.flat.container: a container holds up to sixteen chunks"
                      {:check :chunk-count :n (count chunks)})))
    (check-chunk bytes)
    (when (and (some? first-key) (some? (peek (:first-keys container))))
      (when-not (neg? (compare-key-bytes (peek (:first-keys container)) first-key))
        (throw (ex-info "eve.flat.container: an appended chunk's first-key must ascend"
                        {:check :first-keys}))))
    {:chunks chunks
     :first-keys keys
     :root {:chunk (dec (count chunks))
            :offset (or root-offset (chunk-root-offset bytes))}}))

;; ---------------------------------------------------------------------------
;; compression (C3.2): the container is the unit — one stream over all of it
;; ---------------------------------------------------------------------------

(defn deflated-size
  "The size of bs under zlib (level 6) — the probe convention (M3)."
  ^long [^bytes bs]
  #?(:cljs (throw (ex-info "eve.flat.container: deflate not built on this host" {:check :not-built}))
     :clj (let [df (Deflater. 6) out (byte-array 65536)]
            (.setInput df bs) (.finish df)
            (loop [n 0] (if (.finished df) (do (.end df) n) (recur (+ n (.deflate df out))))))))

(defn compress-container
  "The whole container — chunks and directory — under one zlib stream."
  ^bytes [container]
  #?(:cljs (throw (ex-info "eve.flat.container: deflate not built on this host" {:check :not-built}))
     :clj (let [bs (container-bytes container)
                df (Deflater. 6)
                out (ByteArrayOutputStream.)]
            (.setInput df bs) (.finish df)
            (let [buf (byte-array 65536)]
              (while (not (.finished df))
                (let [n (.deflate df buf)] (when (pos? n) (.write out buf 0 n)))))
            (.end df)
            (.toByteArray out))))

(defn decompress-container-bytes
  "The container bytes a compress-container stream holds."
  ^bytes [^bytes z]
  #?(:cljs (throw (ex-info "eve.flat.container: inflate not built on this host" {:check :not-built}))
     :clj (let [inf (Inflater.) out (ByteArrayOutputStream.) buf (byte-array 65536)]
            (.setInput inf z)
            (while (not (.finished inf))
              (let [n (.inflate inf buf)] (when (pos? n) (.write out buf 0 n))))
            (.end inf)
            (.toByteArray out))))

;; ---------------------------------------------------------------------------
;; placement (C3.5, with Z3.7): the container is the unit of placement for an image
;; ---------------------------------------------------------------------------

(defn image-units
  "The container's chunks in order — the units Z3 places one by one."
  [container]
  (doseq [^bytes chunk (:chunks container)]
    (when-not (== kind-image (chunk-kind chunk))
      (throw (ex-info "eve.flat.container: a placement container holds image chunks only"
                      {:check :not-image}))))
  (:chunks container))

(defn cross-unit-plan
  "Every relocation whose owner block and target block lie in different units,
   read from the FIRST unit only: §9 puts the image-wide block table and
   relocation table there (the header's symtab/shapetab fields). Entries whose
   owner is not a block start in the block table are not relocations (padding)
   and are skipped. Returns [{:position :owner :target :from-unit :to-unit} …]."
  [container]
  (let [units (image-units container)
        bounds (vec (reductions + 0 (map (fn [b] (alength b)) units)))
        unit-of (fn [off] (loop [i 0] (if (< off (nth bounds (inc i))) i (recur (inc i)))))
        ^bytes u0 (first units)
        h (header-at u0 0)
        symtab (:symtab h) shapetab (:shapetab h)]
    (when (or (zero? symtab) (zero? shapetab))
      (throw (ex-info "eve.flat.container: the first unit carries no placement plan"
                      {:check :no-plan})))
    (let [blocks (into {}
                       (map (fn [{:keys [offset size]}] [offset size]))
                       (contract/read-block-table u0 symtab (quot (- shapetab symtab)
                                                                  contract/block-entry-size)))
          reloc-end (if (pos? (:typetab h)) (:typetab h) (alength u0))
          relocs (contract/read-relocation-table u0 shapetab
                                                 (quot (- reloc-end shapetab)
                                                       contract/relocation-entry-size))]
      (into []
            (keep (fn [{:keys [owner target] :as r}]
                    (when (and (contains? blocks owner) (contains? blocks target))
                      (let [fu (unit-of owner) tu (unit-of target)]
                        (when (not= fu tu)
                          (assoc r :from-unit fu :to-unit tu))))))
            relocs))))
