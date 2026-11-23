(ns eve.flat-smoke-test
  "The all-hosts flat smoke: the flat-abilities suite every Eve target
   runs through its own runner (the bb.edn test:<host> tasks, the JVM's
   test route, deps.edn's :node-test for ClojureScript), 60 s per host.
   Every row is an independent deftest: a host failing a row fails that
   row as data, never aborts the suite. Chunk and the freeze lane are
   resolved at runtime, never required statically, so a host where a
   layer does not load fails exactly its rows and the verdict always
   says which layer broke.

   The contract asserted is the documented one (doc/flat, the deref and
   construction laws): construction outside an atom produces the flat
   form; deref, swap! results, watcher notifications and validator
   inputs deliver the flat form; eve->clj (eve.deftype-proto.data) is
   the explicit materialization back to the persistent form; a flat
   value equals, and hashes as, its plain value; handle operations
   read and update through eve.core's surface, the collection surface
   every host's seat gives Eve values. The first three rows are the
   original seed rows, kept verbatim."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [eve.array :as e]
            [eve.atom :as ea]
            [eve.core :as ec]
            [eve.dataset :as ds]
            [eve.deftype-proto.data :as d]
            [eve.flat :as flat]
            ;; cljs only: satisfies? is a compile-time macro there, so
            ;; the two predicate helpers below name the protocols
            ;; directly instead of through the runtime resolution;
            ;; rsym likewise names the layer vars directly on cljs
            #?(:cljs [eve.flat.chunk])
            #?(:cljs [eve.flat.sister])))

(defrecord SmokeRec [alpha beta])

(defn- rsym
  "A var resolved at runtime, or nil where its namespace does not load
   on this host (the row then fails as data, naming the layer).
   On cljs there is no runtime resolution: the layer's vars are
   named directly (the namespaces are required for cljs above)."
  [sym]
  #?(:cljs (case sym
             eve.flat.chunk/pack (var eve.flat.chunk/pack)
             eve.flat.chunk/open (var eve.flat.chunk/open)
             eve.flat.chunk/chunk-bytes (var eve.flat.chunk/chunk-bytes)
             eve.flat.chunk/record-tag (var eve.flat.chunk/record-tag)
             eve.flat.chunk/pack-all (var eve.flat.chunk/pack-all)
             eve.flat.chunk/unpack (var eve.flat.chunk/unpack)
             eve.flat.sister/freeze (var eve.flat.sister/freeze)
             eve.flat.sister/thaw (var eve.flat.sister/thaw)
             nil)
     :default (try (requiring-resolve sym)
                   (catch #?(:cljd dynamic :cljr Exception :lpy python/BaseException :clje _ :glj go/any :jank jank.runtime.object_ref :default Throwable) _ nil))))

(defn- ensure-freeze!
  "Load eve.flat.freeze where the host loads namespaces at runtime
   (its load registers the atom deref-delivery hook). On cljs every
   namespace is compiled in statically and eve.flat already pulls
   the freeze lane in, so there is nothing to load."
  []
  #?(:cljs nil
     :default (try (require 'eve.flat.freeze)
                   (catch #?(:cljd dynamic :cljr Exception :lpy python/BaseException :clje _ :glj go/any :jank jank.runtime.object_ref :default Throwable) _ nil))))

(defn- flat-handle?
  "x is a flat chunk handle (eve.flat.chunk/IFlatHandle)."
  [x]
  #?(:cljs (satisfies? eve.flat.chunk/IFlatHandle x)
     :default (if-let [p (rsym 'eve.flat.chunk/IFlatHandle)]
                (satisfies? @p x)
                false)))

(defn- flat-form?
  "x is a flat value in any of its forms: a chunk handle, or a
   frozen in-atom value (eve.flat.freeze's root type, which
   satisfies eve.deftype-proto.data/IEveRoot)."
  [x]
  (or (flat-handle? x)
      #?(:cljs (satisfies? d/IEveRoot x)
         :default (if-let [p (rsym 'eve.deftype-proto.data/IEveRoot)]
                    (satisfies? @p x)
                    false))))

(defn- h=
  "Value equality through the surface the host's seat gives Eve
   values: plain = everywhere except the protocol hosts (bb,
   glojure), where clojure.core's = cannot see an Eve value from
   the plain side (it asks the host collection, and a flat handle
   is not one), so the seat's own equality — eve.core/equiv, which
   tries -equiv on each side — is the check. Handle-first plain =
   also works on those hosts and is asserted separately where it
   matters (the equality row)."
  [a b]
  #?(:bb (ec/equiv a b)
     :glj (ec/equiv a b)
     :default (= a b)))

;; ---------------------------------------------------------------------------
;; the seed rows (verbatim)
;; ---------------------------------------------------------------------------

(deftest construction-outside-a-domain-produces-flat
  (let [a (e/eve-array :int32 [10 20 30])]
    (is (= 3 (count a))))
  (let [d (ds/dataset {:qty (e/eve-array :int32 [100 200 300])})]
    (is (ds/dataset? d))
    (is (= 3 (ds/row-count d)))))

(deftest atom-deref-of-a-flat-value
  (let [a (ea/atom (ds/dataset {:qty (e/eve-array :int32 [1 2 3])}))]
    (is (ds/dataset? @a))
    (is (= 3 (ds/row-count @a)))))

(deftest chunk-pack-open-roundtrip
  (let [pack (rsym 'eve.flat.chunk/pack)
        open (rsym 'eve.flat.chunk/open)
        chunk-bytes (rsym 'eve.flat.chunk/chunk-bytes)]
    (is (some? pack) "eve.flat.chunk does not load on this host")
    (when pack
      (let [v {:a [1 2 3] :b "x"}
            bs (chunk-bytes (pack v))]
        ;; byte vectors, not seqs: on cljs the chunk bytes are a
        ;; Uint8Array whose seq does not answer an equiv-comparable
        ;; seq (measured: identical bytes, (seq =) false, (vec =)
        ;; true); the assertion is the bytes themselves, byte for
        ;; byte, on every host
        (is (= (vec bs) (vec (chunk-bytes (pack (open bs)))))
            "pack/open/pack is not byte-stable")))))

;; ---------------------------------------------------------------------------
;; construction of every flat kind, outside an atom
;; ---------------------------------------------------------------------------

(def ^:private flat-kinds
  {:vec    [1 2 3]
   :map    {:a 1 :b [1 2] :c {:d "x"}}
   :set    #{7 8 9}
   :list   (list 1 2 3)
   :nested {:v [1 {:m #{1 2}} (list 3)] :s "tail"}})

(deftest flat-kinds-pack-open
  (doseq [[kind v] flat-kinds]
    (testing (str kind)
      (let [h (flat/pack v)]
        (is (flat-handle? h) "pack did not answer a flat handle")
        (is (= h v) "the flat does not equal its plain value")
        (is (= (hash h) (hash v)) "the flat does not hash as its plain value")
        (let [h2 (flat/open (flat/bytes h))]
          (is (flat-handle? h2) "open did not answer a flat handle")
          (is (= h2 v) "the reopened flat does not equal the plain value"))))))

(deftest flat-record-and-tagged-pack-open
  (let [v (->SmokeRec 1 "two")
        h (flat/pack v)]
    (is (flat-handle? h) "a record did not pack to a flat handle")
    (is (= 1 (ec/get h :alpha)) "the record's first field does not read through the handle")
    (is (= "two" (ec/get h :beta)) "the record's second field does not read through the handle"))
  ;; A tagged literal is a leaf to the packer (at the top level it
  ;; is its own flat form, like a scalar — measured on the JVM).
  ;; Nested in a collection, the codec holds it by reference
  ;; (tag 0x15) and the chunk writer refuses reference-held values
  ;; in a scalar body on every host (chunk.cljc's writer guard:
  ;; "a value held by reference cannot be packed" — measured
  ;; identical on the JVM and bb). The row asserts the refusal,
  ;; with a try/catch rather than thrown-with-msg? — the latter's
  ;; expansion does not analyze on jank (measured).
  (is (try (flat/pack {:t (tagged-literal 'smoke/tag {:a 1})})
           false
           (catch #?(:cljs :default :cljd dynamic :cljr Exception :lpy python/BaseException :clje _ :glj go/any :jank jank.runtime.object_ref :default Throwable) e
             (boolean (re-find #"held by reference" (str (ex-message e))))))
      "a nested tagged literal was not refused as reference-held"))

(deftest record-tag-row
  (if-let [tag-fn (rsym 'eve.flat.chunk/record-tag)]
    (let [tag (@tag-fn (flat/pack (->SmokeRec 1 "two")))]
      ;; hosts with a type table (the JVM family) answer the type
      ;; name; hosts whose records pack as field maps in v1 — the
      ;; typed-value machinery's own posture (chunk.cljc: cljs,
      ;; jank and glojure write no type table) — answer nil
      (is (or (nil? tag)
              (and (string? tag) (str/ends-with? tag "/SmokeRec")))
          "the generic record's tag is not its type name"))
    (is false "eve.flat.chunk/record-tag does not resolve on this host")))

;; ---------------------------------------------------------------------------
;; atom delivery: deref, swap!, watchers, validators
;; ---------------------------------------------------------------------------

(deftest atom-deref-of-plain-collections-delivers-flat
  (let [a (ea/atom {:a [1 2 3] :s #{4 5}})]
    (is (flat-handle? @a) "deref did not deliver a flat value")
    (is (h= {:a [1 2 3] :s #{4 5}} @a) "the derefed flat does not equal the stored value")
    (is (flat-handle? (ec/get @a :a)) "a nested value read out of the flat is not flat")))

(deftest swap-roundtrip-delivers-flat
  ;; swap! returns the stored root's form: for a plain root that is
  ;; the persistent read-back (equal to the new value), and deref
  ;; delivers the flat form; for a frozen root the swap's return IS
  ;; the flat form (the seat's delivery record, FLAT-IN-ATOM S3).
  (let [a (ea/atom {:n 0 :v []})
        r (swap! a (fn [m] (-> m (assoc :n 1) (assoc :v [1 2]))))]
    (is (h= {:n 1 :v [1 2]} r) "swap!'s return does not equal the new value")
    (is (flat-handle? @a) "deref after swap! did not deliver a flat value")
    (is (h= {:n 1 :v [1 2]} @a) "the atom does not hold the swapped value"))
  (when-let [freeze (rsym 'eve.flat.sister/freeze)]
    (ensure-freeze!)
    (let [a (ea/atom {})
          sio (ea/atom-sio a)]
      (reset! a (@freeze sio [1 2 3]))
      (let [r (swap! a (fn [cur] (ec/conj cur 4)))]
        (is (flat-form? r) "swap! on a frozen root did not return the flat form")
        (is (h= [1 2 3 4] @a) "the frozen atom does not hold the swapped value")))))

(deftest watchers-and-validators-observe-flat
  (let [a (ea/atom {:x 0})
        seen (atom nil)
        vseen (atom nil)]
    (add-watch a :smoke (fn [_ _ _ nv] (reset! seen nv)))
    (swap! a assoc :x 1)
    (is (flat-handle? @seen) "the watcher was not notified with the flat form")
    (is (h= {:x 1} @seen) "the watcher's value does not equal the new value")
    ;; The validator observes the candidate the swap function
    ;; produced, in the form it produced it: over a frozen root the
    ;; candidate is the flat update of the frozen value.
    (when-let [freeze (rsym 'eve.flat.sister/freeze)]
      (ensure-freeze!)
      (let [b (ea/atom {})]
        (reset! b (@freeze (ea/atom-sio b) [1 2]))
        (set-validator! b (fn [v] (reset! vseen v) true))
        (swap! b (fn [cur] (ec/conj cur 3)))
        (is (flat-form? @vseen) "the validator did not observe the flat form")))))

;; ---------------------------------------------------------------------------
;; freeze / thaw
;; ---------------------------------------------------------------------------

(deftest freeze-thaw-roundtrip
  (let [freeze (rsym 'eve.flat.sister/freeze)
        thaw (rsym 'eve.flat.sister/thaw)]
    (is (some? freeze) "eve.flat.sister/freeze does not resolve on this host")
    (when freeze
      (ensure-freeze!)
      (let [a (ea/atom {})
            sio (ea/atom-sio a)
            v {:a [1 2 3] :s #{4 5}}
            f (@freeze sio v)]
        (is (h= v (@thaw f)) "thaw of the frozen value does not equal the original")
        (reset! a f)
        (is (h= v @a) "the frozen value installed in an atom does not deref to the original")))))

;; ---------------------------------------------------------------------------
;; pack / open at scale: multi-chunk chains and the wide chunk
;; ---------------------------------------------------------------------------

(deftest pack-open-multichunk-and-wide
  (let [pack-all (rsym 'eve.flat.chunk/pack-all)
        unpack (rsym 'eve.flat.chunk/unpack)]
    (is (some? pack-all) "eve.flat.chunk/pack-all does not resolve on this host")
    (when pack-all
      ;; 60 rows x ~1.2 KB payloads: ~72 KB total, still over the
      ;; ~64 KB chunk cap so pack-all splits, but with a fraction of
      ;; the per-row operations of the original 400-row shape — the
      ;; row measured 103 s on jank and 189 s on glj at 400 rows and
      ;; the suite budget is 60 s per host; the shape (multi-chunk
      ;; round-trip) is unchanged.
      (let [rows (mapv (fn [i] {:id i :payload (str (clojure.string/join (repeat 1200 "x")) "-" i)}) (range 60))
            chunks (@pack-all rows)]
        (is (< 1 (count chunks)) "400 rows of ~200 bytes did not split into multiple chunks")
        (is (= rows (vec (mapcat @unpack chunks))) "the multi-chunk value does not round-trip"))
      (let [big (clojure.string/join (repeat 70000 "y"))
            h (flat/pack [big])
            bs (flat/bytes h)]
        ;; the byte length of the chunk bytes: on cljs they are a
        ;; Uint8Array, which does not implement ICounted (measured:
        ;; (count bs) throws there); .-length is the same measure.
        (is (< 65535 #?(:cljs (.-length bs) :default (count bs))) "a 70 KB string did not make a wide chunk")
        (is (= [big] (flat/open bs)) "the wide chunk does not round-trip")))))

;; ---------------------------------------------------------------------------
;; predicates, materialization, handle operations, equality, churn
;; ---------------------------------------------------------------------------

(deftest flat-predicates
  (doseq [[kind v] flat-kinds]
    (testing (str kind)
      (is (flat-handle? (flat/pack v)) "pack's answer is not a flat handle")
      (is (not (flat-handle? v)) "a plain value answers as a flat handle")
      (is (not (flat-handle? (d/eve->clj (flat/pack v)))) "the materialized value answers as a flat handle"))))

(deftest eve-clj-materialization-equals-persistent
  (doseq [[kind v] flat-kinds]
    (testing (str kind)
      (is (= v (d/eve->clj (flat/pack v))) "eve->clj of the flat does not equal the plain value")))
  (let [h (flat/pack {:a [10 20 30]})]
    (is (= [10 20 30] (d/eve->clj (ec/get h :a))) "eve->clj of a nested flat does not equal the plain child")))

(deftest handle-operations
  (let [v (flat/pack [1 2 3])
        m (flat/pack {:a [10 20 30] :s #{7 8}})
        s (flat/pack #{7 8})
        l (flat/pack (list 1 2 3))]
    (is (= 3 (ec/count v)) "count of a flat vector")
    (is (= 20 (ec/nth (ec/get m :a) 1)) "nth into a nested flat vector")
    (is (= 2 (ec/count m)) "count of a flat map")
    (is (h= #{7 8} (ec/get m :s)) "get of a nested flat set")
    (is (= [1 2 3] (vec (ec/seq v))) "seq of a flat vector")
    (is (= 6 (ec/reduce + 0 v)) "reduce over a flat vector")
    (is (h= [1 2 3 4] (ec/conj v 4)) "conj on a flat vector")
    (is (h= [1 2 3] v) "conj changed the original flat vector")
    (is (h= {:a [10 20 30] :s #{7 8} :b 2} (ec/assoc m :b 2)) "assoc on a flat map")
    (is (h= {:s #{7 8}} (ec/dissoc m :a)) "dissoc on a flat map")
    (is (h= #{7} (ec/disj s 8)) "disj on a flat set")
    (is (= 1 (ec/peek l)) "peek of a flat list")
    (is (h= (list 2 3) (ec/pop l)) "pop of a flat list")
    (is (h= [1 2] (ec/pop v)) "pop of a flat vector")))

(deftest equality-and-hash-of-flat-and-plain
  (doseq [[kind v] flat-kinds]
    (testing (str kind)
      (let [h (flat/pack v)
            h2 (flat/open (flat/bytes h))]
        (is (= h v) "flat does not equal plain")
        (is (h= v h) "plain does not equal flat")
        (is (= (hash h) (hash v)) "flat and plain hash differently")
        (is (= h h2) "two handles over the same bytes are not equal")
        (is (not= h (flat/pack {:different 1})) "different flats answer equal")))))

(deftest churn-loop-over-an-atom-of-flat-values
  (let [a (ea/atom {:n 0 :items []})]
    (dotimes [_ 120]
      (swap! a (fn [m] (-> m (update :n inc) (update :items conj (:n m))))))
    (is (flat-handle? @a) "after churn, deref does not deliver a flat value")
    (is (= 120 (ec/get @a :n)) "the churn count is wrong")
    (is (= (vec (range 120)) (d/eve->clj (ec/get @a :items))) "the churned items are wrong")))
