(ns eve.flat.sister-test
  "Lane H5 (IMPL-PLAN §3.4) under the operator's two-kinds rule (2026-11-12):
   every type eve-deftype generates — Eve's own and user types alike —
   generates two kinds from the same expansion, the persistent kind and the
   flat kind (the generated sister); persistent turns to flat when it
   crosses or leaves an atom boundary. The two types below are fresh user
   types, defined in this namespace with ordinary eve-deftype and no
   flat-specific code anywhere: whatever flat behaviour they have, the
   macro generated. Requirement ids H5.1–H5.6, registered in
   test/eve/flat/requirements.edn.

   A .cljc since flat lanes U4a (2026-11-17): the sister is realized on the
   JVM, babashka and ClojureScript, and this suite runs on all three (the
   JVM's :jvm-test alias, bb's eve.runner.bb-main, Node's :flat-sister-test
   build). Its byte reads go through eve.flat-io. H5.5 (the atom boundary:
   transfer!, the image census) and H5.6 (the classpath) are the JVM-class
   hosts' — ClojureScript has no slab domain to transfer between and no
   classpath to ask."
  (:require [clojure.test :refer [deftest is testing]]
            ; required so the alpha surface loads for this sister test; the surface is exercised on the :clj pass 
            #_{:clj-kondo/ignore [:unused-namespace]}   [eve.alpha :as e]
            [eve.deftype :as edt]
            [eve.deftype-proto.data :as d]
            [eve.flat-io :as fio]
            [eve.flat.chunk :as chunk]
            [eve.flat.sister :as sister]
            #?@(:clj [[clojure.java.io :as io]
                      [eve.atom :as ea]
                      [eve.deftype-proto.serialize :as serialize]
                      [eve.deftype-proto.alloc :as alloc]
                      [eve.deftype.registry :as reg]
                      [eve.mem :as mem]
                      [eve.retire :as retire]
                      [eve.flat.contract :as contract]
                      [eve.flat.transfer :as st]])))

;; ---------------------------------------------------------------------------
;; the user types: a protocol of their own, fields of every class —
;; primitives (^:int32, ^:float64), a serialized field (label, note), and a
;; reference to another user type (^:Gadget part)
;; ---------------------------------------------------------------------------

(defprotocol IWidget
  (describe [this])
  (total [this])
  (part-desc [this]))

(defprotocol IFields
  (field-map [this]))

(edt/deftype Gadget [^:int32 k note]
  IWidget
  (describe [_] (str "gadget:" note))
  (total [_] k)
  (part-desc [_] nil)
  IFields
  (field-map [_] {:k k :note note}))

(edt/deftype Widget [^:int32 n ^:float64 score label ^:Gadget part]
  IWidget
  (describe [_] (str label "#" n))
  (total [_] (+ n score))
  (part-desc [_] (when part (describe part)))
  IFields
  (field-map [_] {:n n :score score :label label
                  :part (when part (field-map part))}))

(def ^:private widget-key "eve.flat.sister-test/Widget")
(def ^:private gadget-key "eve.flat.sister-test/Gadget")

(defn- widgets []
  [(->Widget 1 1.5 "one" (->Gadget 10 "g1"))
   (->Widget 2 2.5 "two" (->Gadget 20 "g2"))
   (->Widget 3 3.5 "three" (->Gadget 30 "g3"))])

(defn- opened [v]
  (chunk/open (chunk/chunk-bytes (chunk/pack v))))

;; ---------------------------------------------------------------------------
;; H5.1 — sister-roundtrip: a vector of instances → batch → instances,
;; equal by fields; a single instance → record → instance
;; ---------------------------------------------------------------------------

(deftest sister-roundtrip
  (let [ws (widgets)
        batch (opened ws)]
    (is (= 3 (count batch)) "the batch does not read back three rows")
    (doseq [i (range 3)]
      (is (= (field-map (nth ws i)) (field-map (nth batch i)))
          (str "row " i " differs by fields after the round trip"))))
  (testing "a single instance packs as a record and reads back equal by fields"
    (let [w (->Widget 7 7.5 "seven" nil)
          s (opened w)]
      (is (= (field-map w) (field-map s)))))
  (testing "a batch row whose reference field is nil round-trips too"
    (let [ws [(->Widget 1 1.5 "one" (->Gadget 10 "g1"))
              (->Widget 2 2.5 "two" nil)]
          batch (opened ws)]
      (is (= (field-map (nth ws 1)) (field-map (nth batch 1)))))))

;; ---------------------------------------------------------------------------
;; H5.2 — sister-protocols: the sister satisfies the type's own protocols,
;; read over the packed bytes, with no whole-chunk materialization
;; ---------------------------------------------------------------------------

(deftest sister-protocols
  (let [ws (widgets)
        batch (opened ws)
        s (nth batch 0)
        before (chunk/full-decodes batch)]
    (is (satisfies? IWidget s) "the sister does not satisfy the type's own protocol")
    (is (satisfies? IFields s) "the sister does not satisfy the type's own protocol")
    (is (= "one#1" (describe s)))
    (is (= 2.5 (total s)))
    (is (= "gadget:g1" (part-desc s))
        "the reference field did not read as the nested type's sister")
    (is (= widget-key (d/-sabp-type-key s)) "the sister does not carry the type key")
    (testing "a sister equals its twin sister, and hashes as it"
      (is (= s (nth batch 0)))
      (is (= (hash s) (hash (nth batch 0)))))
    (is (= before (chunk/full-decodes batch))
        "a protocol operation ran the whole-chunk decoder")))

;; ---------------------------------------------------------------------------
;; H5.3 — sister-fingerprint-mismatch: the same type key with a changed
;; layout (a different fingerprint in the chunk's type table) reads as a
;; generic record — not a crash, not wrong fields — and writes back unchanged
;; ---------------------------------------------------------------------------

(defn- flip-fingerprint
  "A copy of chunk bytes bs whose type-table entry for type key tkey has
   its layout fingerprint altered (the FORMAT-V1 §6 type table, walked as
   the format lays it out: varint count, then per type varint key length,
   the key, the 64-bit fingerprint, varint field count, per field varint
   name code, class byte, varint arg). Read and written through
   eve.flat-io, so the same walk runs on every host."
  [bs tkey]
  (let [c (fio/chunk bs)
        n (fio/chunk-len c)
        [nt p] (fio/varint c (fio/u32 c 28))
        fp-pos (loop [i 0 p p]
                 (when (< i nt)
                   (let [[kl p] (fio/varint c p)
                         fpp (+ p kl)]
                     (if (= tkey (fio/utf8 c p kl))
                       fpp
                       (let [[nf p] (fio/varint c (+ fpp 8))]
                         (recur (inc i)
                                (loop [j 0 p p]
                                  (if (< j nf)
                                    (let [[_ p] (fio/varint c p)
                                          p (inc p)
                                          [_ p] (fio/varint c p)]
                                      (recur (inc j) p))
                                    p))))))))
        w (fio/buf 4)]
    (fio/buf-bytes w bs 0 n)
    (fio/buf-seek w fp-pos)
    (fio/buf-u8 w (bit-xor (fio/u8 c fp-pos) 0xFF))
    (fio/chunk-bytes (fio/buf-chunk w n))))

(defn- byte-vec
  "The bytes of host byte array bs as a vector of their unsigned values."
  [bs]
  (let [c (fio/chunk bs)] (mapv #(fio/u8 c %) (range (fio/chunk-len c)))))

(deftest sister-fingerprint-mismatch
  (let [w (->Widget 4 4.5 "four" (->Gadget 40 "g4"))
        bs (chunk/chunk-bytes (chunk/pack w))
        changed (flip-fingerprint bs widget-key)
        s (chunk/open changed)]
    (is (chunk/generic-record? s)
        "a changed layout did not read as a generic record")
    (is (= widget-key (chunk/record-tag s)))
    (testing "the fields are all still there, read as the generic record"
      (is (= 4 (:n s)))
      (is (= "four" (:label s)))
      (is (= 4.5 (:score s))))
    (is (= (byte-vec changed) (byte-vec (chunk/chunk-bytes (chunk/pack s))))
        "the generic record did not write back unchanged")))

;; ---------------------------------------------------------------------------
;; H5.4 — sister-is-generated: no hand-written per-type flat code exists;
;; a second fresh type has a sister with zero additional code
;; ---------------------------------------------------------------------------

(deftest sister-is-generated
  (doseq [tkey [widget-key gadget-key]]
    (let [desc (sister/descriptor tkey)]
      (is (some? desc) (str "no sister was generated for " tkey))
      (is (= :eve-deftype (:generated-by desc))
          (str "the sister of " tkey " was not generated by eve-deftype"))
      (is (some? (:factory desc))
          (str "the sister of " tkey " has no generated factory"))))
  (testing "the second type, defined above with no flat code, reads as a sister"
    (let [gs [(->Gadget 1 "a") (->Gadget 2 "b")]
          batch (opened gs)
          s (nth batch 1)]
      (is (satisfies? IWidget s))
      (is (= "gadget:b" (describe s)))
      (is (= {:k 2 :note "b"} (field-map s))))))

;; ---------------------------------------------------------------------------
;; H5.5 — boundary-conversion: a value stored across an atom boundary goes
;; by its generated registrations — the layout registration for the image
;; (detach/place), the sister registration for the packed form. Without a
;; registration, the conversion has nothing to go by and refuses.
;; ---------------------------------------------------------------------------

#?(:clj (do
          (defn- image-census
            "The block census of image bytes bs (T1's suite's instrument)."
            [^bytes bs]
            (let [h (contract/read-header bs 0)
                  nb (quot (- (:shapetab h) (:symtab h)) contract/block-entry-size)
                  blocks (contract/read-block-table bs (:symtab h) nb)
                  size->class (zipmap alloc/class-block-sizes (range 6))]
              {:nblocks nb
               :by-class (frequencies (map (fn [b] (get size->class (:size b) 6)) blocks))}))

          (defn- reach-count
            "Blocks reachable from atom a's root in its own domain (the tally's
   walk — the same measure T1's suite uses)."
            [a]
            (let [ptr (mem/-load-i32 (:root-r (ea/-domain-state a))
                                     (d/atom-slot-offset (ea/-atom-slot-idx a)
                                                         d/ATOM_SLOT_PTR_OFFSET))]
              (count (retire/reach (ea/atom-sio a) ptr))))

          (defn- widget-in
            "The boundary test's Widget, built in sio's domain (an instance built
   anywhere else is foreign to an atom of that domain, and the store
   rule refuses it — as it should)."
            [sio]
            (->Widget sio 5 5.5 "five" (->Gadget sio 50 "g5")))

          (deftest boundary-conversion
            (testing "an instance crosses from atom A to atom B by transfer!"
              (let [a (e/atom nil)
                    b (e/atom nil)
                    w (widget-in (ea/atom-sio a))]
                (swap! a (fn [_] w))
                (st/transfer! b a)
      ;; the value model: outside an atom a deftype is flat — B's deref
      ;; delivers a Widget's flat sister, which answers Widget's protocols
                (is (instance? Widget__Flat @b) "what arrived in B is not a Widget")
                (is (= (field-map w) (field-map @b)))
                (is (= (reach-count a) (reach-count b))
                    "the copy in B is not self-contained: blocks it reads were left behind in A")))
            (testing "nested in a map, under the :zip foreign policy of a swap!"
              (let [a (e/atom nil)
                    b (e/atom nil)
                    w (widget-in (ea/atom-sio a))]
                (swap! a (fn [_] {:w w}))
                (ea/set-foreign-policy! b :zip)
                (swap! b (fn [_] (ea/read-pinned a (fn [v] v))))
                (is (instance? Widget__Flat (:w @b)))
                (is (= (field-map w) (field-map (:w @b))))))
            (testing "leaving the domain as a flat: the sister registration is the conversion"
              (let [desc (sister/descriptor widget-key)]
                (is (some? desc))
                (try
                  (sister/deregister! widget-key)
                  (is (thrown? Exception (chunk/pack [(->Widget 9 9.5 "nine" nil)]))
                      "without its sister registration the instance still packed")
                  (finally
                    (sister/register-descriptor! desc)))))
            (testing "without the generated layout registration, the boundary image is not self-contained"
    ;; Measured at this pin: the value spans 4 blocks (the instance,
    ;; its label block, the nested instance, its note block) and the
    ;; transfer walk's image carries all 4 while the layout is
    ;; registered. With the registration removed, the walk copies the
    ;; instance block as a leaf — the image carries 1 block, and what
    ;; arrives in B reads its fields only through pointers that still
    ;; address A's blocks in the shared slab: the copy aliases its
    ;; source. The census of T1's own image is the instrument; this
    ;; arm is the failure S5 excludes.
              (let [a (e/atom nil)
                    w (widget-in (ea/atom-sio a))
                    id [Widget-type-id (reg/name-hash-of widget-key)]
                    layouts @serialize/deftype-layouts
                    sizes @serialize/deftype-sizes]
                (swap! a (fn [_] w))
                (let [with-blocks (:nblocks (image-census (st/detach a)))]
                  (is (= (reach-count a) with-blocks)
                      "test premise: the value's span and its image disagree")
                  (try
                    (swap! serialize/deftype-layouts update (first id) dissoc (second id))
                    (swap! serialize/deftype-sizes update (first id) dissoc (second id))
                    (is (< (:nblocks (image-census (st/detach a))) with-blocks)
                        "the boundary image carried every block with no layout registered")
                    (finally
                      (reset! serialize/deftype-layouts layouts)
                      (reset! serialize/deftype-sizes sizes)))))))))

;; ---------------------------------------------------------------------------
;; H5.6 — cljc-first: the generation and the sister live in .cljc, one
;; implementation for every host; no host-only parallel implementation
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest cljc-first
     (testing "the sister, the macro and the shared realization are .cljc sources"
       (doseq [res ["eve/flat/sister.cljc"
                    "eve/flat/source.cljc"
                    "eve/deftype/macros.cljc"
                    "eve/deftype_proto/sio.cljc"]]
         (is (some? (io/resource res)) (str res " is not on the classpath as a .cljc"))))
     (testing "no .clj-only or .cljs-only parallel implementation of the sister exists"
       (is (nil? (io/resource "eve/flat/sister.clj")))
       (is (nil? (io/resource "eve/flat/sister.cljs"))))))
