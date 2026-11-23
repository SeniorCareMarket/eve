(ns eve.flat.place
  "The zip (lane Z3, IMPL-PLAN §3.3): placing a flat image into a live atom
   domain.

   An image (FORMAT-V1 §9) is one byte array holding a value's reachable
   blocks once, root-first, with a block table and a relocation table; the
   pointers inside the blocks hold buffer offsets. Placement is the reverse
   of detach: claim space in the target domain for every block, copy the
   bytes, and rewrite each pointer word to the slab offset its block claimed.

   Doors:
     place        [a image]            place inside a swap! of atom a; the
                                       value the swap installs is the placed
                                       root, read back through the domain.
     place-value  [sio image]          the same, one level down: the placed
                                       root as an Eve value of sio's domain —
                                       the form a swap fn calls with
                                       eve.deftype-proto.data/*domain-sio*.
     place!       [sio image]          the domain-level door: the placed
                                       root's slab offset.
     placement-plan [image unit-size]  the assignment plan, computed before
                                       anything is claimed (Z3.7).

   An image is the §9 chunk byte array (parsed through eve.flat.contract)
   or the equivalent map {:bytes :length :root :blocks [{:offset :size}]
   :relocs [{:position :owner :target}] :layout} — detach emits the chunk;
   the map is the same tables without the header.

   Byte arrays arrive only vouched for: an image in byte form must be the
   eve.flat.check CheckedBytes handle open-checked answers (lane C4's
   contract). Raw byte arrays — however valid they look — are refused
   {:check :unchecked-bytes} at the door, before any parsing; the map form
   is built in process and its tables are checked by validate! below.

   The tables are checked before anything is claimed; every refusal is a
   typed ex-info {:eve.flat.place/check <name>} and leaves the domain's
   allocated set unchanged. Claims go through the one public claim,
   eve.deftype-proto.alloc/alloc-run!: inside a swap attempt that claim is
   logged by the attempt's logging sio, so a lost CAS takes the whole
   placement back (the atom drops the root and frees the attempt's unheld
   blocks). alloc-run! has ONE canonical answer shape (collation decision):
   a vector of the blocks' slab-qualified offsets in ascending order, or
   nil when no free run of n exists. The scalar first-offset shape lives
   only at the -sio-alloc-run! protocol layer, where it is an
   implementation detail alloc-run! itself converts. Bytes and
   pointer words are written only through the target sio's own ops, so an
   encrypting sio's keystream applies to placed bytes exactly as it does to
   written ones. Any failure after the first claim frees every offset
   claimed so far before the exception propagates.

   Placement runs unit by unit: with :unit-size (bytes), blocks are grouped
   in buffer order into units charged size + 8 + 12 per owned pointer (the
   probe P7 accounting); the full assignment plan is computed before the
   first unit is placed, claims and copies proceed unit by unit, and the
   pointer pass resolves every relocation from the completed plan — a
   pointer that leaves its unit is worth exactly the same pass as one that
   stays."
  (:require [eve.deftype-proto.alloc :as alloc]
            [eve.deftype-proto.data :as d]
            [eve.deftype-proto.serialize :as ser]
            [eve.flat.check :as check]
            [eve.flat.contract :as contract])
  #?(:clj (:import [java.util Arrays])))

;; ---------------------------------------------------------------------------
;; Refusals
;; ---------------------------------------------------------------------------

(defn- refuse [check msg data]
  (throw (ex-info (str "eve.flat.place: " msg)
                  (assoc data :eve.flat.place/check check))))

(defn- rd-i32
  "The signed little-endian i32 at i in ba (an image's stored pointer word)."
  ^long [^bytes ba ^long i]
  (let [i (int i)]
    (long (unchecked-int
           (bit-or (bit-and (long (aget ba i)) 0xFF)
                   (bit-shift-left (bit-and (long (aget ba (unchecked-add-int i 1))) 0xFF) 8)
                   (bit-shift-left (bit-and (long (aget ba (unchecked-add-int i 2))) 0xFF) 16)
                   (bit-shift-left (bit-and (long (aget ba (unchecked-add-int i 3))) 0xFF) 24))))))

;; ---------------------------------------------------------------------------
;; The image, normalized
;; ---------------------------------------------------------------------------

(defn- chunk->image
  "A §9 chunk byte array, parsed through the contract, as the normalized
   image map place works on."
  [^bytes ba]
  (let [h (contract/read-header ba 0)]
    (when (not= :image (:kind h))
      (refuse :wrong-kind "the chunk is not an image chunk" {:kind (:kind h)}))
    (let [layout (contract/layout-id h)]
      (when (not= 1 layout)
        (refuse :unknown-layout "the image's layout is not the canonical layout 1" {:layout layout}))
      (let [len (:length h)
            symtab (:symtab h)
            shapetab (:shapetab h)
            typetab (:typetab h)]
        (when (or (< len contract/header-size) (> len (alength ba)))
          (refuse :length-out-of-range "the chunk's length is outside its byte array" {:length len}))
        (when (or (zero? symtab) (zero? shapetab))
          (refuse :tables-absent "the image chunk carries no block/relocation tables" {}))
        (let [nb (quot (- shapetab symtab) contract/block-entry-size)
              end (if (pos? typetab) typetab (if (contract/digest? h) (- len 32) len))
              span (- end shapetab)
              nr (quot span contract/relocation-entry-size)
              ;; FORMAT-V1 §2: the chunk's length is a multiple of 8, so up
              ;; to 7 pad bytes may follow the relocation table (before the
              ;; digest, when one ends the chunk). The table's count is the
              ;; quotient; the pad must be zero bytes, never table data.
              pad (when (<= 0 span) (- span (* nr contract/relocation-entry-size)))]
          (when (or (neg? nb) (neg? nr)
                    (not= (- shapetab symtab) (* nb contract/block-entry-size))
                    (nil? pad)
                    (not (every? (fn [i] (zero? (aget ba (int (+ shapetab (* nr contract/relocation-entry-size) i)))))
                                 (range pad))))
            (refuse :tables-absent "the image's tables do not divide evenly" {:symtab symtab :shapetab shapetab}))
          {:bytes ba
           :length len
           :root (:root h)
           :blocks (contract/read-block-table ba symtab nb)
           :relocs (contract/read-relocation-table ba shapetab nr)
           :layout layout
           :blocks-end symtab})))))

(defn- map->image [m]
  (when (and (contains? m :kind) (not= :image (:kind m)))
    (refuse :wrong-kind "the image map is not an image" {:kind (:kind m)}))
  (when (and (contains? m :layout) (not= 1 (:layout m)))
    (refuse :unknown-layout "the image's layout is not the canonical layout 1" {:layout (:layout m)}))
  {:bytes (:bytes m)
   :length (:length m)
   :root (:root m)
   :blocks (:blocks m)
   :relocs (:relocs m)
   :layout (get m :layout 1)
   :blocks-end nil})

(defn normalize-image
  "An image in either form (a CheckedBytes handle for the chunk byte array,
   or the map of its tables) as the one normalized map validation and
   placement consume. Anything else in byte form goes through
   eve.flat.check/require-checked, which refuses it {:check
   :unchecked-bytes} — raw bytes never reach the parser."
  [image]
  (cond (map? image) (map->image image)
        (check/checked? image) (chunk->image (check/checked-bytes image))
        :else (chunk->image (check/require-checked image))))

;; ---------------------------------------------------------------------------
;; Validation — before anything is claimed (Z3.2)
;; ---------------------------------------------------------------------------

(def ^:private header-kinds
  "The type-id bytes a value's header block can start with: the kinds
   eve.retire/val-children walks (scalar, vector, list, array, object,
   var-array, map, set) and the deftype range."
  (into #{0x01 0x12 0x13 0x1D 0x1E 0x1F 0xED 0xEE} (range 0x40 0xED)))

(defn validate!
  "Check a normalized image's tables, in full, before any claim. Returns the
   image; throws a typed refusal (see the namespace doc) on the first bad
   table entry. The checks, their order and their refusal data are the
   shipped gate's, unchanged; the tables are lifted into primitive arrays
   once so the checks themselves run as primitive loops (the doseq/nth form
   measured 1.6-6.6 µs per block on the corpora — REENGINE re-derivation)."
  [{:keys [bytes length root blocks relocs blocks-end] :as img}]
  (let [n (count blocks)
        nr (count relocs)
        boff (long-array n)
        bsz (long-array n)
        rpos (long-array nr)
        rown (long-array nr)
        rtgt (long-array nr)]
    (dotimes [i n]
      (let [b (nth blocks i)]
        (aset boff i (long (:offset b)))
        (aset bsz i (long (:size b)))))
    (dotimes [j nr]
      (let [r (nth relocs j)]
        (aset rpos j (long (:position r)))
        (aset rown j (long (:owner r)))
        (aset rtgt j (long (:target r)))))
    (when (zero? n)
      (refuse :root-not-a-block "the image has no blocks" {}))
    ;; every block: a size its class can hold, inside the image (and, in the
    ;; chunk form, ahead of the tables that describe it)
    (dotimes [i n]
      (let [offset (aget boff i) size (aget bsz i)]
        (when (<= size 0)
          (refuse :size-cannot-hold "a block's size is not positive" {:block i :size size}))
        (when (or (neg? offset) (> (+ offset size) length) (and blocks-end (> (+ offset size) blocks-end)))
          (refuse :block-out-of-range "a block lies outside the image" {:block i :offset offset :size size}))))
    ;; no two blocks overlap (block indices sorted by offset: a packed-long
    ;; sort on the JVM; a keyed sort on CLJS, whose bit ops are 32-bit)
    (let [sorted-idx #?(:clj (let [packed (long-array n)]
                               (dotimes [i n] (aset packed i (bit-or (bit-shift-left (aget boff i) 32)
                                                                     (bit-and i 0xFFFFFFFF))))
                               (java.util.Arrays/sort packed)
                               (let [out (int-array n)]
                                 (dotimes [k n] (aset out k (int (bit-and (aget packed k) 0xFFFFFFFF))))
                                 out))
                        :default (int-array (sort-by (fn [i] (aget boff i)) (range n))))]
      (dotimes [k (dec n)]
        (let [a (aget sorted-idx k)
              b (aget sorted-idx (inc k))]
          (when (> (+ (aget boff a) (aget bsz a)) (aget boff b))
            (refuse :blocks-overlap "two blocks overlap" {:a (nth blocks a) :b (nth blocks b)})))))
    ;; the root is one of the blocks, and its first byte is a known kind
    (let [root-block (first (filter #(= root (:offset %)) blocks))]
      (when-not root-block
        (refuse :root-not-a-block "the root offset is no block's offset" {:root root}))
      (let [kind (bit-and (aget ^bytes bytes (int root)) 0xFF)]
        (when-not (contains? header-kinds kind)
          (refuse :unknown-kind "the root block's first byte is no known kind" {:kind kind}))))
    ;; every relocation: owner and target are blocks, the position is a word
    ;; inside its owner's bytes, and the word stored there is the target
    ;; block's buffer offset (the image's pointer representation, §9)
    (dotimes [j nr]
      (let [position (aget rpos j) owner (aget rown j) target (aget rtgt j)]
        (when (or (neg? owner) (>= owner n) (neg? target) (>= target n))
          (refuse :pointer-to-non-block "a relocation names a block that is not in the table"
                  {:reloc j :owner owner :target target}))
        (let [ooff (aget boff owner) osize (aget bsz owner)]
          (when (or (< position ooff) (> (+ position 4) (+ ooff osize)))
            (refuse :relocation-out-of-range "a relocation's position is outside its owner's bytes"
                    {:reloc j :position position :owner owner}))
          (let [stored (rd-i32 bytes position)]
            (when (not= stored (aget boff target))
              (refuse :relocation-value "the word at a relocation's position is not its target's buffer offset"
                      {:reloc j :position position :stored stored :target-offset (aget boff target)}))))))
    img))

;; ---------------------------------------------------------------------------
;; The placement plan (Z3.7)
;; ---------------------------------------------------------------------------

(defn- class-of ^long [^long size]
  (let [c (d/size->class-idx size)]
    (if (neg? c) 6 (long c))))

(defn placement-plan
  "The assignment plan for an image at the given unit size: blocks grouped
   in buffer order into units (charged size + 8 for the block-table entry +
   12 per pointer the block owns), each block's class, and the count of
   pointers that leave their unit. The plan is computed in full before
   anything is claimed; placement executes it."
  [image unit-size]
  (let [img (validate! (normalize-image image))
        {:keys [blocks relocs]} img
        n (count blocks)
        nr (count relocs)
        boff (long-array n)
        bsz (long-array n)
        rown (long-array nr)
        rtgt (long-array nr)
        owned (int-array n)]
    (dotimes [i n]
      (let [b (nth blocks i)]
        (aset boff i (long (:offset b)))
        (aset bsz i (long (:size b)))))
    (dotimes [j nr]
      (let [r (nth relocs j)]
        (aset rown j (long (:owner r)))
        (aset rtgt j (long (:target r)))
        (aset owned (aget rown j) (inc (aget owned (aget rown j))))))
    ;; order: block indices sorted by buffer offset (packed-long sort on
    ;; the JVM; keyed sort on CLJS)
    (let [order #?(:clj (let [packed (long-array n)]
                          (dotimes [i n] (aset packed i (bit-or (bit-shift-left (aget boff i) 32)
                                                                (bit-and i 0xFFFFFFFF))))
                          (java.util.Arrays/sort packed)
                          (let [out (int-array n)]
                            (dotimes [k n] (aset out k (int (bit-and (aget packed k) 0xFFFFFFFF))))
                            out))
                   :cljs (int-array (sort-by (fn [i] (aget boff i)) (range n))))
          unit-of (int-array n)
          classes (int-array n)]
      (loop [k 0 u 0 charge 0]
        (when (< k n)
          (let [i (aget order k)
                c (+ (aget bsz i) 8 (* 12 (aget owned i)))
                u' (if (and (pos? charge) (> (+ charge c) unit-size)) (inc u) u)
                charge' (if (= u' u) (+ charge c) c)]
            (aset unit-of i u')
            (aset classes i (class-of (aget bsz i)))
            (recur (inc k) u' charge'))))
      {:image img
       :nunits (if (zero? n) 0 (inc (reduce max 0 (seq unit-of))))
       :unit-of unit-of
       :classes classes
       :order (vec (seq order))
       :crossings (loop [j 0 c 0]
                    (if (< j nr)
                      (recur (inc j) (if (not= (aget unit-of (aget rown j))
                                               (aget unit-of (aget rtgt j)))
                                       (inc c) c))
                      c))})))

;; ---------------------------------------------------------------------------
;; Claims — through alloc/alloc-run!, with the fallback chain (Z3.3)
;; ---------------------------------------------------------------------------

(defn- run-offsets
  "alloc-run!'s answer as a vector of k offsets, or nil when it answers
   nil (no run). The canonical answer shape is the vector (see the ns
   docstring); the scalar first-offset shape exists only at the
   -sio-alloc-run! protocol layer and never reaches this call site."
  [sio ci k]
  (let [r (alloc/alloc-run! sio ci k)]
    (cond (nil? r) nil
          (sequential? r) (vec r)
          :else nil)))

(defn- claim-slice!
  "Claim offsets for the blocks idxs[lo..hi) — one class's blocks of one
   unit, in buffer order — into offs, marking each in claimed (the rollback
   set). The whole run first (one alloc-run! for the slice); on a nil
   answer, halves recursively; a single block a run will not answer goes
   through -sio-alloc! at its own size. The Z3.3 fallback chain, unchanged;
   only its bookkeeping is primitive (the shipped form's per-block
   group-by/filterv scaffolding over unhinted arrays measured ~10 µs per
   block — REENGINE re-derivation, placesample leg)."
  [sio ci ^ints idxs lo hi ^longs sizes ^longs offs ^booleans claimed]
  (letfn [(go [lo hi]
            (let [k (- hi lo)]
              (cond
                (zero? k) nil
                (== k 1) (let [i (aget idxs lo)
                               r (run-offsets sio ci 1)
                               off (long (if r (nth r 0) (alloc/-sio-alloc! sio (aget sizes i))))]
                           (aset offs i off)
                           (aset claimed i true))
                :else (if-let [r (run-offsets sio ci k)]
                        (dotimes [j k]
                          (let [i (aget idxs (+ lo j))]
                            (aset offs i (long (nth r j)))
                            (aset claimed i true)))
                        (let [mid (+ lo (quot k 2))]
                          (go lo mid)
                          (go mid hi))))))]
    (go lo hi)))

;; ---------------------------------------------------------------------------
;; Placement — the batch engine (REENGINE)
;;
;; Claims: one alloc-run! per (unit, class) slice (the plan's units are
;; contiguous ranges of the buffer order and unit-of is non-decreasing
;; along it, so a class's members split into per-unit slices by a single
;; cursor walk — no group-by, no filterv).
;; Copies: where the target sio's root is a heap-backed JvmSlabCtx (the
;; common case, and every bench's), each block's bytes go straight from
;; the image array into the domain's heap array by arraycopy — the Z2
;; claim+copy shape. Any other sio (an encrypting sio above all: its
;; keystream must see the bytes) keeps the shipped per-block
;; -sio-write-bytes! path, which is also the CLJS path and the overflow
;; class's path. The fast path is safe by construction: alloc/node-heap-
;; array answers an array only when the sio unwraps (through logging
;; wrappers alone, which transform nothing) to a JvmSlabCtx whose class
;; region is this process's heap bytes.
;; Pointers: the same split — direct i32 stores into the heap array where
;; there is one, -sio-write-i32! through the sio where there is not.
;; ---------------------------------------------------------------------------

#_{:clj-kondo/ignore [:unused-private-var]} ; used only on the :clj pass (the :clj fast-path block in copy-run); no cljs caller exists
(defn- put-i32-arr!
  "The little-endian i32 store of v at byte i of a (the target heap array)."
  [^bytes a ^long i ^long v]
  (let [i (int i)]
    (aset a i (unchecked-byte v))
    (aset a (unchecked-add-int i 1) (unchecked-byte (bit-shift-right v 8)))
    (aset a (unchecked-add-int i 2) (unchecked-byte (bit-shift-right v 16)))
    (aset a (unchecked-add-int i 3) (unchecked-byte (bit-shift-right v 24)))
    nil))

(defn- execute-plan!
  "Claim, copy and relocate per the plan: all claims first (unit-major,
   class slices), then all copies, then the pointer pass resolved from
   the completed plan. On any failure, free every offset claimed so far
   through the same sio and rethrow."
  [sio {:keys [image nunits unit-of classes order]}]
  (let [^ints unit-of unit-of
        ^ints classes classes
        {:keys [bytes blocks relocs root]} image
        n (count blocks)
        nr (count relocs)
        sizes (long-array n)
        boff (long-array n)
        offs (long-array n)
        claimed (boolean-array n)
        order-arr (int-array n)
        rpos (long-array nr)
        rown (long-array nr)
        rtgt (long-array nr)
        root-idx (volatile! -1)]
    (dotimes [i n]
      (let [b (nth blocks i)]
        (aset sizes i (long (:size b)))
        (aset boff i (long (:offset b)))
        (aset order-arr i (int (nth order i)))
        (when (== root (aget boff i)) (vreset! root-idx i))))
    (dotimes [j nr]
      (let [r (nth relocs j)]
        (aset rpos j (long (:position r)))
        (aset rown j (long (:owner r)))
        (aset rtgt j (long (:target r)))))
    (try
      ;; class membership: counting sort of the buffer order by class —
      ;; members[ci] holds class ci's block indices in buffer order.
      (let [counts (int-array 7)
            starts (int-array 7)
            members (int-array n)
            fill (int-array 7)
            cursors (int-array 7)]
        (dotimes [k n] (let [ci (aget classes (aget order-arr k))]
                         (aset counts ci (inc (aget counts ci)))))
        (loop [ci 0 acc 0]
          (when (< ci 7)
            (aset starts ci acc)
            (aset fill ci acc)
            (aset cursors ci acc)
            (recur (inc ci) (+ acc (aget counts ci)))))
        (dotimes [k n]
          (let [i (aget order-arr k) ci (aget classes i) p (aget fill ci)]
            (aset members p i)
            (aset fill ci (inc p))))
        ;; PHASE A — claims, unit-major: for each unit, each class's
        ;; contiguous slice of its members (the cursor walk).
        (dotimes [u nunits]
          (dotimes [ci 7]
            (let [mend (+ (aget starts ci) (aget counts ci))
                  lo (aget cursors ci)]
              (loop [hi lo]
                (if (and (< hi mend) (== u (aget unit-of (aget members hi))))
                  (recur (inc hi))
                  (do (aset cursors ci hi)
                      (when (< lo hi)
                        (if (== ci 6)
                          (loop [p lo]
                            (when (< p hi)
                              (let [i (aget members p)
                                    off (alloc/-sio-alloc! sio (aget sizes i))]
                                (aset offs i (long off))
                                (aset claimed i true)
                                (recur (inc p)))))
                          (claim-slice! sio (long ci) members lo hi sizes offs claimed)))))))))
        ;; PHASE B — copies. fast-arr[ci]: the class's heap array in the
        ;; target domain, or nil (per class, resolved once, after all
        ;; claims — a claim may grow a class and replace its array).
        (let [fast-arr (object-array 7)]
          #?(:clj (dotimes [ci 6]
                    (let [mend (+ (aget starts ci) (aget counts ci))]
                      (when (< (aget starts ci) mend)
                        (aset fast-arr ci (alloc/node-heap-array sio (aget offs (aget members (aget starts ci)))))))))
          (dotimes [ci 7]
            (let [arr #?(:clj (aget fast-arr ci) :cljs nil)
                  mend (+ (aget starts ci) (aget counts ci))]
              (loop [p (aget starts ci)]
                (when (< p mend)
                  (let [i (aget members p) o (int (aget boff i)) s (int (aget sizes i))]
                    ;; arr is the :clj-only fast-path array (nil on
                    ;; the other passes by construction of the #?
                    ;; binding above), so this test is the fast
                    ;; path's gate, constant only per pass.
                    (if #_{:clj-kondo/ignore [:constant-condition]} (and arr (not (== ci 6)))
                        #?(:clj (System/arraycopy ^bytes bytes o arr (int (alloc/node-base sio (aget offs i))) s)
                           :cljs nil)
                        (alloc/-sio-write-bytes! sio (aget offs i) 0
                                                 #?(:clj (Arrays/copyOfRange ^bytes bytes o (int (+ o s)))
                                                    :cljs (.slice bytes o (+ o s))))))
                  (recur (inc p))))))
          ;; PHASE C — the pointer pass: every relocation, resolved from
          ;; the completed plan. Owner byte bases are cached per block.
          (let [_owner-base (long-array n)
                owner-seen (boolean-array n)]
            (dotimes [j nr]
              (let [o (aget rown j) t (aget rtgt j) pos (aget rpos j)
                    arr #?(:clj (aget fast-arr (aget classes o)) :cljs nil)]
                (if #_{:clj-kondo/ignore [:constant-condition]} (and arr (not (== (aget classes o) 6)))
                    #?(:clj (let [base (if (aget owner-seen o)
                                         (aget owner-base o)
                                         (let [b (alloc/node-base sio (aget offs o))]
                                           (aset owner-base o b)
                                           (aset owner-seen o true)
                                           b))]
                              (put-i32-arr! arr (+ base (- pos (aget boff o))) (aget offs t)))
                       :cljs nil)
                    (alloc/-sio-write-i32! sio (aget offs o)
                                           (- pos (aget boff o))
                                           (aget offs t))))))))
      (aget offs @root-idx)
      (catch #?(:clj Throwable :cljs :default) t
        (dotimes [i n]
          (when (aget claimed i)
            (try (alloc/-sio-free! sio (aget offs i)) (catch #?(:clj Throwable :cljs :default) _ nil))))
        (throw t)))))

(defn place!
  "Place an image into sio's domain: the placed root's slab offset. With
   :unit-size (bytes), placement proceeds unit by unit per the plan (see
   placement-plan); with no :unit-size the whole image is one unit."
  ([sio image] (place! sio image {}))
  ([sio image {:keys [unit-size] :or {unit-size Long/MAX_VALUE}}]
   (execute-plan! sio (placement-plan image unit-size))))

(defn place-value
  "Place an image into sio's domain and answer the placed root as an Eve
   value of that domain. Inside a swap fn, sio is the attempt's domain sio
   (eve.deftype-proto.data/*domain-sio*): the value's sio-root is the
   domain's, so the atom installs it in place — no second copy."
  ([sio image] (place-value sio image {}))
  ([sio image opts]
   (let [off (place! sio image opts)
         t (ser/read-header-tag sio off)
         ctor (ser/get-header-constructor t)]
     (ctor sio off))))

(defn place
  "Place an image as atom a's new value, inside a swap!: the value the swap
   installs (the placed root, read back through the domain). This is the
   door eve.flat/place delegates to."
  ([a image] (place a image {}))
  ([a image opts]
   (when (nil? a)
     (refuse :no-atom "place wants an Eve atom, got nil" {}))
   (swap! a (fn [_] (place-value d/*domain-sio* image opts)))))
