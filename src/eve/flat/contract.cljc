(ns eve.flat.contract
  "The frozen byte contract of flat Eve (FORMAT-V1.md §2 and §9), shared by
   every lane of the spike: the 32-byte chunk header, the image's block table
   and relocation table, and the format's constants. Read and write are exact
   inverses; checking untrusted bytes is a separate door (FORMAT-V1.md §10,
   lane C4), not this namespace's job — the one exception is the magic, which
   read-header refuses by name.

   All multi-byte fields are little-endian. u32 fields are carried as
   non-negative numbers (longs on the JVM).")

;; ---------------------------------------------------------------------------
;; Constants (FORMAT-V1.md §2, §9)
;; ---------------------------------------------------------------------------

(def header-size 32)

(def magic-bytes [0x45 0x56 0x53 0x31]) ;; "EVS1", constant for the life of the format

(def format-version 1)

(def kind-packed 0)
(def kind-image 1)
(def kind-directory 2)
(def kind-raw 3)

(def ^:private kind->code {:packed kind-packed :image kind-image
                           :directory kind-directory :raw kind-raw})
(def ^:private code->kind {kind-packed :packed kind-image :image
                           kind-directory :directory kind-raw :raw})

(def flag-wide 1)    ;; offsets are u32; else u16
(def flag-digest 2)  ;; a 32-byte SHA-256 of the chunk ends the chunk
(def flag-types 4)   ;; a type table is present

(defn required-features
  "The flags word's required-features byte (bits 8-15) for byte b: a reader
   that does not know a set bit refuses the chunk."
  [b]
  (* (bit-and b 0xFF) 256))

(def chunk-target-bytes 65536) ;; 64 KiB is a target, not a cap (§2)
(def seal-threshold 65535)     ;; a writer seals when the next row would pass this

(def layout-canonical 1) ;; canonical Eve's blocks (doc/internals.md)
(def layout-kernel 2)    ;; the kernel's ExtCID v3 blocks

;; ---------------------------------------------------------------------------
;; Little-endian words over a host byte array
;; ---------------------------------------------------------------------------

;; JVM: typed (no reflective RT.aget/aset per byte; the reflective form
;; priced the whole zip/place path, 1 ms a block — Nov 15 2026)
(defn- u8 [ba i] #?(:clj (bit-and (aget ^bytes ba (int i)) 0xFF)
                    :default (bit-and (aget ba i) 0xFF)))

(defn- put-u8! [ba i v] #?(:clj (aset ^bytes ba (int i) (unchecked-byte (bit-and (long v) 0xFF)))
                           :default (aset ba i (unchecked-byte (bit-and v 0xFF)))))

(defn- get-u16 [ba i]
  (+ (u8 ba i) (* 256 (u8 ba (+ i 1)))))

(defn- put-u16! [ba i v]
  (put-u8! ba i v)
  (put-u8! ba (+ i 1) (quot v 256)))

(defn- norm-u32 [v]
  #?(:clj (bit-and (long v) 0xFFFFFFFF)
     :cljs (mod v 4294967296)))

(defn- get-u32 [ba i]
  (+ (u8 ba i)
     (* 256 (u8 ba (+ i 1)))
     (* 65536 (u8 ba (+ i 2)))
     (* 16777216 (u8 ba (+ i 3)))))

(defn- put-u32! [ba i v]
  (let [v (norm-u32 v)]
    (put-u8! ba i v)
    (put-u8! ba (+ i 1) (quot v 256))
    (put-u8! ba (+ i 2) (quot v 65536))
    (put-u8! ba (+ i 3) (quot v 16777216))))

;; ---------------------------------------------------------------------------
;; The chunk header (§2): 32 bytes, the chunk 8-aligned in whatever holds it
;; ---------------------------------------------------------------------------

(defn write-header!
  "Write a chunk header into ba at off. Fields: :kind (keyword or code),
   :version (default 1), :flags, :length (bytes, header included), :root
   (offset of the root node or root block), :nsym, :nshape, :symtab,
   :shapetab, :typetab (offsets; 0 = absent). For an image chunk, :layout-id
   fills the nsym field (§9), and :symtab/:shapetab are the block and
   relocation tables' offsets."
  [ba off {:keys [kind version flags length root nsym nshape symtab shapetab typetab layout-id]
           :or {version format-version flags 0 length 0 root 0 nshape 0 symtab 0 shapetab 0 typetab 0}}]
  (doseq [[i b] (map-indexed vector magic-bytes)]
    (put-u8! ba (+ off i) b))
  (put-u8! ba (+ off 4) version)
  (put-u8! ba (+ off 5) (if (keyword? kind) (kind->code kind) kind))
  (put-u16! ba (+ off 6) flags)
  (put-u32! ba (+ off 8) length)
  (put-u32! ba (+ off 12) root)
  (put-u16! ba (+ off 16) (or layout-id nsym 0))
  (put-u16! ba (+ off 18) nshape)
  (put-u32! ba (+ off 20) symtab)
  (put-u32! ba (+ off 24) shapetab)
  (put-u32! ba (+ off 28) typetab)
  ba)

(defn read-header
  "Parse the chunk header at off in ba. Refuses a wrong magic with a typed
   ex-info naming the check; every other field is returned as written (the
   full check of §10 is open-checked's door, lane C4)."
  [ba off]
  (when-not (= magic-bytes (mapv #(u8 ba (+ off %)) (range 4)))
    (throw (ex-info "eve.flat: not a flat chunk (bad magic)"
                    {:eve.flat.contract/check :magic :offset off})))
  (let [code (u8 ba (+ off 5))]
    {:kind (get code->kind code code)
     :version (u8 ba (+ off 4))
     :flags (get-u16 ba (+ off 6))
     :length (get-u32 ba (+ off 8))
     :root (get-u32 ba (+ off 12))
     :nsym (get-u16 ba (+ off 16))
     :nshape (get-u16 ba (+ off 18))
     :symtab (get-u32 ba (+ off 20))
     :shapetab (get-u32 ba (+ off 24))
     :typetab (get-u32 ba (+ off 28))}))

(defn wide? [header] (pos? (bit-and (:flags header) flag-wide)))
(defn digest? [header] (pos? (bit-and (:flags header) flag-digest)))
(defn types? [header] (pos? (bit-and (:flags header) flag-types)))

(defn required-features-of
  "The header's required-features byte (bits 8-15 of the flags word)."
  [header]
  (bit-and (quot (:flags header) 256) 0xFF))

(defn layout-id
  "An image chunk's layout id: the header's nsym field (§9). 1 = canonical
   Eve's blocks, 2 = the kernel's ExtCID v3 blocks."
  [header]
  (:nsym header))

;; ---------------------------------------------------------------------------
;; The image's tables (§9)
;; ---------------------------------------------------------------------------

(def block-entry-size 8)       ;; {offset u32, size u32}
(def relocation-entry-size 12) ;; {field position u32, owner block u32, target block u32}

(defn write-block-table!
  "Write the block table (one {offset, size} entry per block, in buffer
   order) into ba at off."
  [ba off blocks]
  (doseq [[i {:keys [offset size]}] (map-indexed vector blocks)]
    (put-u32! ba (+ off (* i block-entry-size)) offset)
    (put-u32! ba (+ off (* i block-entry-size) 4) size))
  ba)

(defn read-block-table
  [ba off n]
  (mapv (fn [i]
          {:offset (get-u32 ba (+ off (* i block-entry-size)))
           :size (get-u32 ba (+ off (* i block-entry-size) 4))})
        (range n)))

(defn write-relocation-table!
  "Write the relocation table (one {position, owner, target} entry per
   pointer) into ba at off."
  [ba off relocs]
  (doseq [[i {:keys [position owner target]}] (map-indexed vector relocs)]
    (put-u32! ba (+ off (* i relocation-entry-size)) position)
    (put-u32! ba (+ off (* i relocation-entry-size) 4) owner)
    (put-u32! ba (+ off (* i relocation-entry-size) 8) target))
  ba)

(defn read-relocation-table
  [ba off n]
  (mapv (fn [i]
          {:position (get-u32 ba (+ off (* i relocation-entry-size)))
           :owner (get-u32 ba (+ off (* i relocation-entry-size) 4))
           :target (get-u32 ba (+ off (* i relocation-entry-size) 8))})
        (range n)))
