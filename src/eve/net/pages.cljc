(ns eve.net.pages
  "THE PAGE WRITER AND READER — a value as ExtCID v3 blocks in kernel pages, and back (aegis/docs/extcid-v3/FORMAT.md
   §2; IMPL-PLAN.md §5 B5: R-B5.1, R-B5.2). The kernel's own page tier (vs/src/vs/kernel/wasm/alloc.vs, eve.vs THE
   PAGE_CID MAPPING, eve_page_adopt_b64 / eve_page_b64_chunk / eve_slot_resolve): a page is a region of the slab,
   [its 64-byte header (magic 'PAGE', its 16-byte PAGE_CID, its class and block size)][bitmap][descriptor table]
   [blocks of one class]; a block is [its 256-byte own header][its body], a slot the child's 64-byte core — the child's
   region PAGE_CID, its absolute offset, VERSION 3, FLAGS (ACTIVE and the child's seal-equality bits), its 32-byte
   image. A reader resolves a child by PAGE_CID + offset (rebased into its copy of that region) and verifies by the
   images alone: every block's digest (§2.6, eve.net.codec's image; a record's with its attested band) equals the SHA
   its own header carries and the image its parent's slot carries.

   write-pages: a value → {:root the 64-byte core of its root block, :key its band hex, :pages [{:cid :base :bytes}],
   :known {band-hex {:cid :off :bits}}}. Only the blocks the receiver does not hold are written: `known` (read-pages'
   answer for the state the value replaces) names every block already in a page, and a slot to it is the core of that
   block. A record (eve.net.codec/attest) is sealed ATTESTED with its band in its header (§2.4 H); the root is stamped
   (`:stamp`: EPOCH, SPEC_ID, EVALUATOR, PREV_ROOT — outside its digest, invariant F). A page carries exactly the
   blocks placed in it (its TOTAL_BLOCKS), so its bytes end at its last block: the prefix of its region the kernel's
   eve_page_adopt_b64 decodes.

   read-pages: (the root's 64-byte core, {cid-hex page bytes}) → {:value :key :stamp :known :blocks}, every block
   reached verified first (a block that fails throws ex-info {:type :bad-page}); a record decodes with its header's
   attestation (eve.net.codec/attestation), a v2 header is refused (§2.3)."
  (:refer-clojure :exclude [volatile! vswap!])
  (:require [eve.host :refer [volatile! vswap!]]
            [eve.net.bytes :as b]
            [eve.crypto :as prim]
            [eve.net.codec :as c]))

;; ── the kernel's layout (FORMAT.md §2.1–§2.3; alloc.vs PHV2_*, hamt.vs, vec.vs, list.vs) ──────────────────────────

(def ^:private HEADER-SIZE 65536)      ;; the slab header: region k's base is HEADER-SIZE + k · REGION (alloc.vs)
(def ^:private REGION 1048576)         ;; REGION_SIZE
(def ^:private PAGE-MAGIC 1346455365)  ;; 'PAGE' little-endian
(def ^:private PAGE-HDR 64)            ;; PAGE_HDR_SIZE
(def ^:private PAGE-VERSION 4)         ;; PAGE_HEADER_VERSION: the descriptor table between the bitmap and the blocks
(def ^:private DESC-SIZE 28)           ;; alloc.vs DESC_SIZE
(def ^:private ADOPTED-OWNER 2147483646) ;; eve.vs PT_ADOPTED_OWNER: no thread's id — the page is the receiver's to read
(def ^:private CLASS-SIZES [4 8 16 32 64 128 288 576 832 1088 1600 2112 3200 4224 10240])   ;; class_to_block_size

(def ^:private OWN 256)                ;; EXTCID_OWN: the own header; the body (type tag, payload) starts here
(def ^:private CORE 64)                ;; EXTCID_CORE: a slot
(def ^:private VERSION 3)
(def ^:private SHA-OFF 32)
(def ^:private ATTEST-OFF 64)          ;; EPOCH ‖ SPEC_ID ‖ GROUP_ROOT ‖ PRINCIPAL_COMMIT ‖ EVALUATOR, 128 bytes
(def ^:private SPEC-ID-OFF 80)
(def ^:private EVALUATOR-OFF 176)
(def ^:private PREV-ROOT-OFF 192)

(def ^:private F-ACTIVE 1)
(def ^:private F-HAS-EPOCH 2)
(def ^:private F-HAS-SPEC-ID 4)
(def ^:private F-HAS-PREV 8)
(def ^:private F-HAS-GROUP 16)
(def ^:private F-HAS-PRINCIPAL 32)
(def ^:private F-DBL 64)
(def ^:private F-IDENT 128)
(def ^:private F-ALIAS 256)
(def ^:private F-XREP 512)
(def ^:private F-EXT 1024)
(def ^:private F-ATTESTED 2048)
(def ^:private SEQ-ALL 960)            ;; DBL | IDENT | ALIAS | XREP (MD-1's seal-equality bits)

(def ^:private TYPE-CID
  {:map-root (b/unhex "9999999999999999cccccccccccccccc") :node (b/unhex "ddddddddddddddddeeeeeeeeeeeeeeee")
   :vec-root (b/unhex "55555555555555556666666666666666") :vec-node (b/unhex "77777777777777778888888888888888")
   :list-root (b/unhex "11111111111111112222222222222222") :list-node (b/unhex "33333333333333334444444444444444")})
(def ^:private KIND-OF-CID (into {} (map (fn [[k v]] [v k]) TYPE-CID)))

;; the bytes past the sealed payload a root's allocation holds (outside every image): a map / set / list root's flag byte
;; @325 (bit 0 the set's) and metadata word @332 (hamt.vs ROOT_FLAGS_OFF, ROOT_META_OFF; list.vs LIST_META_OFF); a
;; vector root's owner stamp @269 and metadata word @404 (vec.vs VEC_OWNER_OFF, VEC_META_OFF) — all 0 here
(def ^:private ROOT-FLAGS-OFF 325)
(def ^:private MIN-SIZE {:map-root 336 :list-root 336 :vec-root 408})

(def ^:private T-MAP 0xED)
(def ^:private T-NODE 0x01)
(def ^:private T-VEC 0x12)
(def ^:private T-LIST 0x13)

(def ^:private STR-CID (b/unhex "329d9c9b9a999897aaaaaaaaaaaaaaaa"))
(def ^:private KWLF (b/unhex "666c776b"))
(def ^:private CHLF (b/unhex "666c6863"))
(def ^:private F64-LO (b/unhex "f6f6f6f6f6f6f6f6"))   ;; EVE_FLOAT64_CID_LO: a kernel FLOAT64 box, a program DOUBLE or LONG
(def ^:private DBL-HIS #{(b/unhex "f4f4f4f4f4f4f4f4") (b/unhex "5550413264626c20")})   ;; FLOAT64 unmarked, UPA2dbl
(def ^:private K-LONG-LO (b/unhex "f5f5f5f5f5f5f5f5"))

(def ^:private EE (vec (repeat 32 0xEE)))
(defn- zeros [n] (vec (repeat n 0)))
(defn- xor32 [x] (mapv (fn [p q] (bit-xor p q)) x EE))
(defn- u32 [x] (b/u32le x))
(defn- u48 [x] (into (b/u32le (mod x 4294967296)) [(mod (quot x 4294967296) 256) (mod (quot x 1099511627776) 256)]))
(defn- rd32 [bs i] (b/read-u32le bs i))
(defn- rd48 [bs i] (+ (rd32 bs i) (* 4294967296 (+ (nth bs (+ i 4)) (* 256 (nth bs (+ i 5)))))))
(defn- rd64 [bs i] (+ (rd32 bs i) (* 4294967296 (rd32 bs (+ i 4)))))
(defn- sub [bs from n] (subvec bs from (+ from n)))
(defn- flag? [f bit] (= 1 (mod (quot f bit) 2)))
(defn- popcount [x] (loop [x x c 0] (if (zero? x) c (recur (quot x 2) (+ c (mod x 2))))))

(defn- size->class "alloc.vs size_to_class" [n]
  (or (first (keep-indexed (fn [i s] (when (<= n s) i)) CLASS-SIZES))
      (throw (ex-info (str "pages: a block of " n " bytes is past the largest class") {:type :unencodable :size n}))))

(defn region-base "the region base of an absolute offset (eve.vs THE PAGE_CID MAPPING)" [off]
  (+ HEADER-SIZE (* REGION (quot (- off HEADER-SIZE) REGION))))

(defn- bm-bytes "alloc.vs bm_bytes_for" [n] (* 16 (quot (+ (quot (+ n 7) 8) 15) 16)))
(defn- desc-bytes "tally.vs desc_bytes_for" [n] (* 16 (quot (+ (* n DESC-SIZE) 15) 16)))
(defn- data-off [n] (+ PAGE-HDR (bm-bytes n) (desc-bytes n)))
(defn- page-cap
  "the most blocks of size bs one region holds. The walk down starts at the bound the unrounded sizes give,
   8·(region − header) / (8·(bs + desc) + 1) — every larger n fails before rounding — not at region / bs: from there a
   32-byte class walked ~30,000 candidates (vs.tier's -page-cap, the same fix; basilisp, MEASURED 2026-10-26: 217 ms a
   call under cProfile, 28 calls in four chat writes). The answer is the same"
  [bs]
  (loop [n (min (quot REGION bs) (quot (* 8 (- REGION PAGE-HDR)) (+ (* 8 (+ bs DESC-SIZE)) 1)))]
    (if (<= (+ (data-off n) (* n bs)) REGION) n (recur (dec n)))))

;; ── the image's slots (eve.vs THE SEAL IMAGE's per-kind inventory) ───────────────────────────────────────────────

(defn- image-kind
  "the block kind of a collection image (eve.net.codec's), or nil for a value block"
  [img]
  (let [tag (first img) n (count img)]
    (cond (and (= tag T-MAP) (= n 37)) :map-root
          (= tag T-NODE) :node
          (and (= tag T-VEC) (= n 81)) :vec-root
          (= tag T-VEC) :vec-node
          (and (= tag T-LIST) (= n 37)) :list-root
          (and (= tag T-LIST) (= n 69)) :list-node
          :else nil)))

(defn- image-slots
  "the image offsets of every slot of a collection image, empty ones included (each 32 bytes there, 64 in the block)"
  [img kind]
  (case kind
    (:map-root :list-root) [5]
    :list-node [5 37]
    :vec-root [17 49]
    :vec-node (mapv (fn [i] (+ 9 (* 32 i))) (range (quot (- (count img) 9) 32)))
    :node (let [dbm (rd32 img 5) nbm (rd32 img 9) cc (popcount nbm) dc (popcount dbm)]
            (into (mapv (fn [i] (+ 13 (* 32 i))) (range cc))
                  (loop [i 0 p (+ 13 (* 32 cc) (* 16 dc)) out []]
                    (if (= i dc)
                      out
                      (let [kl (rd32 img p) vl (rd32 img (+ p 4 kl)) v (+ p 8 kl)]
                        (if (= vl CORE) (recur (inc i) (+ v 32) (conj out v)) (recur (inc i) (+ v vl) out)))))))))

(defn- body-slots
  "the body offsets (from OWN) of every slot of a block body of kind, empty ones included; cap the vector node's slot
   count (its allocation's)"
  [body kind cap]
  (case kind
    (:map-root :list-root) [5]
    :list-node [5 69]
    :vec-root [17 81]
    :vec-node (let [occ (loop [n cap] (if (and (pos? n) (zero? (nth body (+ 9 (* CORE (dec n)) 22)))) (recur (dec n)) n))]
                (mapv (fn [i] (+ 9 (* CORE i))) (range occ)))
    :node (let [dbm (rd32 body 5) nbm (rd32 body 9) cc (popcount nbm) dc (popcount dbm)]
            (into (mapv (fn [i] (+ 13 (* CORE i))) (range cc))
                  (loop [i 0 p (+ 13 (* CORE cc) (* 16 dc)) out []]
                    (if (= i dc)
                      out
                      (let [kl (rd32 body p) vl (rd32 body (+ p 4 kl)) v (+ p 8 kl)]
                        (if (= vl CORE) (recur (inc i) (+ v CORE) (conj out v)) (recur (inc i) (+ v vl) out)))))))))

(defn- body-size
  "the bytes of a block body of kind from its fixed head (the payload _blk_payload sizes, the type tag with it)"
  [body kind cap]
  (case kind
    (:map-root :list-root) 69
    :list-node 133
    :vec-root 145
    :vec-node (+ 9 (* CORE (count (body-slots body kind cap))))
    :node (let [cc (popcount (rd32 body 9)) dc (popcount (rd32 body 5))]
            (+ 13 (* CORE cc) (* 16 dc) (+ (nth body 3) (* 256 (nth body 4)))))))

(defn- vec-cap "eve.vs eve_vec_node_cap: the slots a vector node's allocation holds" [bs]
  (if (< bs (+ 265 CORE)) 32 (min 32 (quot (- bs 265) CORE))))

;; ── the seal-equality bits a slot takes for its child (eve.vs _seq_tier1_bits / _seq_tier2_bits / _seq_flat_bits) ─

(defn- scalar-bits [bd]
  (let [lo (sub bd 0 8) hi (sub bd 8 8)]
    (cond (= lo F64-LO) (if (and (contains? DBL-HIS hi)
                                 (let [m (+ (rd32 bd 16) (* 4294967296 (mod (rd32 bd 20) 2147483648)))]
                                   (or (zero? m) (> m 9218868437227405312))))
                          (+ F-DBL F-ALIAS) F-ALIAS)
          (= lo K-LONG-LO) F-ALIAS
          :else 0)))

(defn- tier1-bits "a headed child's: its own seal-equality bits, and by its kind" [own kind]
  (+ (bit-and own SEQ-ALL)
     (if (and (= kind :vec-root) (not (flag? own F-XREP))) F-XREP 0)
     (if (and (#{:list-root :list-node} kind) (not (flag? own F-IDENT))) F-IDENT 0)))

;; ── the writer ───────────────────────────────────────────────────────────────────────────────────────────────────

(defn- value-size "the bytes of a value block (eve.net.codec's canonical image: [CID or typekey][count][…][bytes])" [img]
  (+ 24 (rd32 img 16)))

(defn- plan
  "every block of the value under root to write, children before parents: [{:band :img :kind :size}] — a collection, a
   value block or a scalar's box — and none of known's"
  [root blocks known]
  (loop [stack [[root false]] seen #{} out []]
    (if (empty? stack)
      out
      (let [[bd expanded?] (peek stack) stack (pop stack) k (b/hex bd)]
        (cond
          (or (contains? seen k) (contains? known k) (every? zero? bd)) (recur stack seen out)
          (c/scalar-band? bd) (recur stack (conj seen k) (conj out {:band bd :img bd :kind :box :size 32}))
          :else
          (let [img (or (get blocks k) (throw (ex-info (str "pages: no block under " k) {:type :bad-page :key k})))
                kind (image-kind img)]
            (if (nil? kind)
              (recur stack (conj seen k) (conj out {:band bd :img img :kind :value :size (value-size img)}))
              (let [kids (map (fn [o] (sub (vec img) o 32)) (image-slots img kind))]
                (if expanded?
                  (recur stack (conj seen k)
                         (conj out {:band bd :img img :kind kind
                                    :size (max (get MIN-SIZE kind 0) (+ OWN (count img) (* 32 (count (image-slots img kind)))))}))
                  (recur (into (conj stack [bd true]) (map (fn [x] [x false]) (reverse kids))) seen out))))))))))

(defn- cid-of "a page's CID: SHA-256(salt ‖ seq u64 ‖ class)[0..16], byte 0 the class (alloc.vs next_page_cid)" [salt sq cls]
  (let [d (prim/sha256 (b/join [salt (u32 sq) (zeros 4) [cls]]))]
    (into [cls] (subvec (vec d) 1 16))))

(defn- lay-out
  "the pages the planned blocks go in: [{:cid :base :class :bs :n :blocks [entry …]}] and {band-hex [cid off]}"
  [entries region salt]
  (let [by-class (group-by (fn [e] (size->class (:size e))) entries)]
    (loop [cs (sort (keys by-class)) j 0 pages [] where {}]
      (if (empty? cs)
        [pages where]
        (let [cls (first cs) bs (nth CLASS-SIZES cls)
              chunks (partition-all (page-cap bs) (get by-class cls))
              [pages where j]
              (reduce (fn [[pages where j] es]
                        (let [base (+ HEADER-SIZE (* REGION (+ region j)))
                              n (count es) d0 (data-off n) cid (cid-of salt (inc j) cls)
                              where (reduce (fn [w [i e]] (assoc w (b/hex (:band e)) [cid (+ base d0 (* i bs))]))
                                            where (map-indexed vector es))]
                          [(conj pages {:cid cid :base base :class cls :bs bs :n n :blocks (vec es)}) where (inc j)]))
                      [pages where j] chunks)]
          (recur (rest cs) j pages where))))))

(defn- slot-core "a 64-byte slot: the child's page CID, its offset, VERSION 3, ACTIVE | its bits, its image" [cid off bits img]
  (b/join [cid (u48 off) [VERSION 0] (u32 (+ F-ACTIVE bits)) (zeros 4) img]))

(defn- put-at [v off bs] (into (into (subvec v 0 off) bs) (subvec v (+ off (count bs)))))

(defn- block-bytes
  "[the block of entry e at off in its page of block size bs, its own seal-equality bits]: its own header (type CID,
   own offset, VERSION 3, FLAGS, SHA, the attested band or the root's stamp) and its body, every slot the child's core"
  [e off bs where bits-of stamp]
  (let [{:keys [img kind band]} e]
    (case kind
      :box [(into (vec band) (zeros (- bs 32))) 0]
      :value (let [n (rd32 img 16)]
               [(into (vec img) (zeros (- bs (+ 24 n)))) 0])
      (let [islots (image-slots img kind)
            [body seq-bits] (loop [os islots pos 0 out [] bits 0]
                              (if (empty? os)
                                [(into out (subvec img pos)) bits]
                                (let [o (first os) child (sub img o 32)
                                      [core bits] (if (every? zero? child)
                                                    [(zeros CORE) bits]
                                                    (let [[cid coff] (where (b/hex child)) cb (bits-of child)]
                                                      [(slot-core cid coff cb child) (bit-or bits cb)]))]
                                  (recur (rest os) (+ o 32) (-> out (into (subvec img pos o)) (into core)) bits))))
            at (c/attestation img)
            set? (and (= kind :map-root) (c/set-band? band img))
            sha (if at (vec band) (vec (prim/sha256 img)))
            ext (cond at (+ F-EXT F-ATTESTED F-HAS-EPOCH F-HAS-SPEC-ID (if (:group-root at) F-HAS-GROUP 0)
                            (if (:principal-commit at) F-HAS-PRINCIPAL 0))
                      stamp (+ F-EXT F-HAS-EPOCH F-HAS-SPEC-ID F-HAS-PREV)
                      :else 0)
            band128 (cond at (c/attest-band at)
                          stamp (b/join [(u32 (mod (:epoch stamp) 4294967296)) (u32 (quot (:epoch stamp) 4294967296)) (zeros 8)
                                         (vec (:spec-id stamp)) (zeros 64) (vec (:evaluator stamp))])
                          :else (zeros 128))
            hdr (b/join [(get TYPE-CID kind) (u48 off) [VERSION 0] (u32 (+ F-ACTIVE seq-bits ext)) (zeros 4) sha band128
                         (if (and stamp (not at)) (vec (or (:prev stamp) (zeros 32))) (zeros 32)) (zeros 32)])
            blk (into (into hdr body) (zeros (- bs (+ OWN (count body)))))
            blk (if set? (assoc blk ROOT-FLAGS-OFF 1) blk)]
        [blk seq-bits]))))

(defn write-pages
  "the pages that carry the value v (a map or a vector: an atom's state) — {:root core :key hex :pages [{:cid :base
   :bytes}] :known {band-hex {:cid :off :bits}}}. opts: :known (read-pages' answer for the state v replaces: its blocks
   are referenced, never written again), :salt (32 bytes; the page CIDs' — default the host's randomness), :region (the
   first region index the pages claim in the writer's address space, default 16: the addresses a slot names, which a
   receiver rebases by PAGE_CID), :stamp {:epoch n :spec-id 32 B :evaluator 16 B :prev 32 B | nil} (the root's),
   :record (read-pages' :record of the state v replaces, with :known: every part of v identical to that state's keeps
   its key, whatever writer imaged it, and only the changed paths are imaged — eve.net.codec/encode-rec; without it v is
   encoded whole, in this codec's forms), :memo (read-pages' :memo: the new keys' hashes are taken from it and kept)"
  ([v] (write-pages v {}))
  ([v {:keys [known salt region stamp record memo] :or {region 16}}]
   (when-not (or (map? v) (vector? v)) (throw (ex-info "pages: an atom's state is a map or a vector" {:type :unencodable})))
   (when (c/attestation v) (throw (ex-info "pages: a root is stamped, never attested (FORMAT.md §2.4 H)" {:type :unencodable})))
   (let [known (or known {})
         salt (vec (or salt (prim/random-bytes 32)))
         {:keys [root blocks]} (binding [c/*checked* memo]
                                 (if record
                                   (let [[r acc] (c/encode-rec v record)] {:root (c/rec-key r) :blocks acc})
                                   (c/encode v)))
         rb (b/unhex root)
         entries (plan rb blocks known)
         [pages where] (lay-out entries region salt)
         where-all (fn [k] (or (get where k) (let [e (get known k)] [(:cid e) (:off e)])))
         by-band (into {} (map (fn [e] [(b/hex (:band e)) e]) entries))
         bits (volatile! (into {} (map (fn [[k e]] [k (:bits e)]) known)))
         ;; a slot's bits for a child: a box's / a value's by its image, a collection's from its own (written first)
         bits-of (fn [bd] (let [k (b/hex bd)]
                            (or (get @bits k)
                                (if (c/scalar-band? bd) (scalar-bits bd) 0))))
         ;; children before parents: plan's order
         built (reduce (fn [m e]
                         (let [k (b/hex (:band e))
                               [cid off] (get where k)
                               page (first (filter (fn [p] (= cid (:cid p))) pages))
                               [blk own] (block-bytes e off (:bs page) where-all bits-of (when (= k root) stamp))]
                           (when-not (#{:box :value} (:kind e)) (vswap! bits assoc k (tier1-bits own (:kind e))))
                           (assoc m k blk)))
                       {} entries)
         page-bytes (fn [{:keys [cid class bs n blocks]}]
                      (let [d0 (data-off n)
                            bm (reduce (fn [bm i] (update bm (quot i 8) + (nth [1 2 4 8 16 32 64 128] (mod i 8))))
                                       (zeros (bm-bytes n)) (range n))]
                        (b/join (concat [(u32 PAGE-MAGIC) cid (u32 class) (u32 bs) (u32 REGION) (u32 n) (u32 PAGE-HDR) (u32 d0)
                                         (u32 ADOPTED-OWNER) (u32 PAGE-VERSION) (u32 0) (u32 0) (u32 0)]
                                        [bm (zeros (desc-bytes n))]
                                        (map (fn [e] (get built (b/hex (:band e)))) blocks)))))
         [rcid roff] (where-all root)]
     (when-not rcid (throw (ex-info "pages: the root has no block" {:type :bad-page})))
     {:root (slot-core rcid roff (bits-of rb) rb)
      :key root
      :pages (mapv (fn [p] {:cid (b/hex (:cid p)) :base (:base p) :bytes (page-bytes p)}) pages)
      :known (into known (map (fn [[k [cid off]]] [k {:cid cid :off off :bits (bits-of (b/unhex k))}]) where))})))

;; ── the reader ───────────────────────────────────────────────────────────────────────────────────────────────────

(declare b64-page)

(defn- bad [msg data] (throw (ex-info (str "pages: " msg) (assoc data :type :bad-page))))

(defn- block-at
  "[the page bytes, the block's local offset] of the block a slot names (PAGE_CID + absolute offset, rebased); pages a
   function of a page CID (hex) to its bytes"
  [pages cid off]
  (let [page (or (pages (b/hex cid)) (bad "a slot names a page not given" {:cid (b/hex cid)}))
        local (- off (region-base off))]
    (when (or (neg? local) (> (+ local 32) (count page))) (bad "a slot's offset lies outside its page" {:cid (b/hex cid) :off off}))
    [page local]))

(defn- value-image
  "the canonical image of the value block at local (eve.vs _slot_image tiers 3 / 3b: a string's bytes 20..23 imaged as
   zero, a char's code point kept)"
  [page local]
  (let [head (sub page local 24) n (rd32 head 16) text (sub page (+ local 24) n)]
    (cond (= (sub head 0 16) STR-CID) (b/join [(sub head 0 20) (zeros 4) text])
          (= (sub head 0 4) CHLF) (b/join [(sub head 0 4) (zeros 12) (sub head 16 8) text])
          (= (sub head 0 4) KWLF) (b/join [(sub head 0 4) (zeros 12) (sub head 16 4) (zeros 4) text])
          :else (bad "a value block of no kind this reader images" {:cid (b/hex (sub head 0 16))}))))

(defn- read-attest [hdr f]
  {:epoch (rd64 hdr ATTEST-OFF) :spec-id (sub hdr SPEC-ID-OFF 32)
   :group-root (when (flag? f F-HAS-GROUP) (sub hdr 112 32))
   :principal-commit (when (flag? f F-HAS-PRINCIPAL) (sub hdr 144 32))
   :evaluator (sub hdr EVALUATOR-OFF 16)})

(defn read-pages
  "the value under the root core (64 bytes: its page CID, offset and band), its blocks read out of pages ({cid-hex →
   the page's bytes from its region base, or its base64 text — decoded the first time a block in it is read: a /deref
   carries every page the atom spans}) and every one verified — {:value :record :key :stamp {:epoch :spec-id :evaluator
   :prev} | nil :known {band-hex {:cid :off :bits}} :blocks {band-hex image} :checked n :memo}. :record is the codec's
   record of the value (eve.net.codec/decode-rec: the tree checked canonical, its keys kept), which write-pages' :record
   takes. opts :prev, an earlier read of the same atom (its last value): a slot naming a block that read verified, at
   the same page and offset, names that block and everything under it as they were — pages are never written again —
   so its subtree is taken from that read unread, and decode-rec meets only the blocks the new value added (the read's
   :memo, eve.net.codec/*checked*). A read of the next value costs its changes, not the atom"
  ([root pages] (read-pages root pages nil))
  ([root pages {:keys [prev]}]
  (let [root (vec root)
        pk (:known prev)
        pb (:blocks prev)
        cache (volatile! {})
        ;; a name of its own, not `pages` again: glojure's let sees a binding inside its own init
        page-of (fn [k] (or (get @cache k)
                            (when-let [v (get pages k)]
                              (let [bs (if (string? v) (b64-page v) (vec v))] (vswap! cache assoc k bs) bs))))
        ;; a volatile: this read's, in this thread (on the BEAM a process value — an atom's every deref copies it whole)
        st (volatile! {:blocks {} :known {} :checked 0})
        ;; the subtree under k as the earlier read verified it: its entries copied, its images' child bands followed
        reuse (fn reuse [k]
                (when-not (contains? (:known @st) k)
                  (let [img (get pb k)]
                    (vswap! st (fn [s] (cond-> (assoc-in s [:known k] (get pk k)) img (assoc-in [:blocks k] img))))
                    (when-let [kind (when img (image-kind img))]
                      (doseq [o (image-slots img kind)]
                        (let [cb (sub img o 32)]
                          (when-not (every? zero? cb) (reuse (b/hex cb)))))))))
        visit (fn visit [core]
                (let [cid (sub core 0 16) off (rd48 core 16) band (sub core SHA-OFF 32) k (b/hex band)
                      bits (bit-and (rd32 core 24) SEQ-ALL)
                      was (get pk k)]
                  (if (and was (not (contains? (:known @st) k)) (= cid (vec (:cid was))) (= off (:off was)))
                    (reuse k)
                  (when-not (contains? (:known @st) k)
                    (vswap! st assoc-in [:known k] {:cid cid :off off :bits bits})
                    (when-not (c/scalar-band? band)
                      (let [[page local] (block-at page-of cid off)
                            kind (get KIND-OF-CID (sub page local 16))]
                        (if (nil? kind)
                          (let [img (value-image page local)]
                            (when-not (= band (vec (prim/sha256 img))) (bad "a value block's image is not its band" {:key k}))
                            (vswap! st (fn [s] (-> s (assoc-in [:blocks k] img) (update :checked inc)))))
                          (let [hdr (sub page local OWN)
                                _ (when-not (= VERSION (nth hdr 22)) (bad "a header that is not ExtCID v3" {:key k :version (nth hdr 22)}))
                                f (rd32 hdr 24)
                                bs (rd32 page 24)
                                rest-of (subvec page (+ local OWN) (min (count page) (+ local bs)))
                                n (body-size rest-of kind (vec-cap bs))
                                body (subvec rest-of 0 n)
                                slots (body-slots rest-of kind (vec-cap bs))
                                img (loop [os slots pos 0 out []]
                                      (if (empty? os)
                                        (into out (subvec body pos))
                                        (let [o (first os)]
                                          (recur (rest os) (+ o CORE) (-> out (into (subvec body pos o)) (into (sub body (+ o SHA-OFF) 32)))))))
                                sha (sub hdr SHA-OFF 32)
                                at (when (flag? f F-ATTESTED) (read-attest hdr f))
                                d (vec (prim/sha256 (if at (b/join [img (sub hdr ATTEST-OFF 128)]) img)))]
                            (when-not (= d sha) (bad "a block's digest is not the SHA its header carries" {:key k :kind kind}))
                            (when-not (or (= band sha) (and (= kind :map-root) (= band (xor32 sha))))
                              (bad "a block's digest is not the image its parent's slot carries" {:key k}))
                            (vswap! st (fn [s] (-> s (assoc-in [:blocks k] (if at (with-meta img {::c/attest at}) img))
                                                  (update :checked inc))))
                            (doseq [o slots]
                              (let [s (sub body o CORE)]
                                (when-not (every? zero? (sub s SHA-OFF 32)) (visit s)))))))))))
                (sub core SHA-OFF 32))
        band (visit root)
        rk (b/hex band)
        {:keys [blocks known checked]} @st
        [page local] (block-at page-of (sub root 0 16) (rd48 root 16))
        hdr (sub page local OWN)
        f (rd32 hdr 24)
        memo (or (:memo prev) (c/new-checked))
        [v rec] (binding [c/*checked* memo] (c/decode-rec band blocks))]
    {:value v
     :record rec
     :memo memo
     :key rk
     :stamp (when (and (flag? f F-EXT) (not (flag? f F-ATTESTED)))
              {:epoch (rd64 hdr ATTEST-OFF) :spec-id (sub hdr SPEC-ID-OFF 32) :evaluator (sub hdr EVALUATOR-OFF 16)
               :prev (sub hdr PREV-ROOT-OFF 32)})
     :known known :blocks blocks :checked checked})))

(defn page-b64 "a page's bytes as the base64 text eve_page_adopt_b64 decodes" [bytes] (b/to-b64 bytes))
(defn b64-page "the bytes of a page's base64 text (eve_page_b64_chunk's)" [text] (vec (b/from-b64 text)))
