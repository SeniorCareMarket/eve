;; P5 — does it hold past maps and vectors? (research probe: A2, A4, A12, A13, Q1, Q9a)
;;
;; For each kind of eve value: (a) built in an append-only buffer and read from a copy truncated at its length;
;; (b) built in a slab domain, copied out by the relocating copier into one byte array, read in place from it;
;; (c) that image placed back into a fresh slab domain by one run claim per size class, and read there.
;; Kinds: list, set, sorted map and set (eve.sorted — eve-deftypes, a treap), rb-tree set, int-map, typed array,
;; dataset, a user eve-deftype with primitive, serialized, reference and mutable fields, and a DataScript database
;; from the repository's port (contract-test/lib-ds: DB, BTSet, Node, Leaf, Datom — all eve-deftypes).
;;
;; Run (from /home/user/eve):
;;   EXTRA_CP=contract-test/lib:contract-test/lib-ds:contract-test/lib-ds-deps \
;;   java -Xmx6g -cp "$(doc/static/probes/eve-cp.sh)" clojure.main doc/static/probes/p5_types.clj
(load-file "doc/static/probes/lib.clj")
(load-file "doc/static/probes/corpora.clj")
(ns p5-types
  (:require [static-probe.lib :as L :refer [bench]]
            [static-probe.corpora :as C]
            [eve.deftype-proto.alloc :as alloc]
            [eve.deftype-proto.data :as d]
            [eve.deftype-proto.serialize :as ser]
            [eve.deftype :as edt]
            [eve.sorted :as es]
            [eve.deftype.rb-tree :as rb]
            [eve.deftype.int-map :as im]
            [eve.array :as arr]
            [eve.dataset :as ds]
            [eve.list :as el]
            [eve.set :as eset]
            [eve.retire :as retire])
  (:import [java.util Arrays]))

(println "P5 — eve" (clojure.string/trim (slurp ".git/HEAD")) "| clojure" (clojure-version)
         "| java" (System/getProperty "java.version"))

(def results (atom []))
(defn note [label status & more]
  (swap! results conj [label status])
  (println (format "  %-64s %s%s" label (name status) (if (seq more) (str "  " (apply str more)) ""))))

(defmacro check [label expr]
  `(try (if ~expr (note ~label :PASS) (note ~label :FAIL))
        (catch Throwable t# (note ~label :ERROR (.getSimpleName (class t#)) ": " (.getMessage t#)))))

;; a user type: a primitive, a serialized value, a reference to another instance, a mutable primitive
(edt/eve-deftype Tag [^:int32 id label])
(edt/eve-deftype Item [^:int32 n ^:mutable ^:int32 hits payload ^:Tag tag]
  clojure.lang.ILookup
  (valAt [this k] (case k :n n :hits hits :payload payload :tag tag nil))
  (valAt [this k nf] (case k :n n :hits hits :payload payload :tag tag nf))
  clojure.lang.Counted
  (count [this] n))

(defn item-plain [it] {:n (:n it) :hits (:hits it) :payload (d/eve->clj (:payload it))
                       :tag (when-let [t (:tag it)] (L/size-of nil 0) [(type t)])})

(defn make-item [sio]
  (->Item sio 7 0 {:k [1 2 3] :s "serialized"} (->Tag sio 42 "the tag")))

(defn truncated-view
  "The value at off of bump buffer sio, read from a copy of the buffer cut at its current length."
  [sio off]
  (let [len (.tail ^static_probe.lib.IBump sio)]
    (L/reopen (L/view-sio (Arrays/copyOfRange (.buf ^static_probe.lib.IBump sio) 0 (int len)) len) off)))

(defn through
  "Build with (make sio) in a bump buffer and in a recorded slab domain; answer the four readings of it:
   {:bump (the truncated view) :slab :image (read in place) :placed (the image placed in a fresh domain) :im image}."
  [make]
  (let [bs (L/bump-sio (* 4 1024 1024) true)
        vb (binding [d/*domain-sio* bs] (make bs))
        ctx (L/slab-domain)
        rs (L/rec-sio ctx)
        vs (binding [d/*domain-sio* rs] (make rs))
        soff (d/-root-header-off vs)
        im (L/image! ctx soff (fn [o] (L/size-of rs o)))
        tgt (L/slab-domain (L/caps-for im 3))
        poff (L/ingest! tgt im :runs)]
    {:bump (truncated-view bs (d/-root-header-off vb))
     :slab (L/reopen ctx soff)
     :image (L/image-view im)
     :placed (L/reopen tgt poff)
     :im im}))

(defn all-equal? [m f expected]
  (every? (fn [k] (= expected (f (get m k)))) [:bump :slab :image :placed]))

(println "== P5.1 the other collections: bump buffer, image read in place, image placed by run claim")
(let [xs (vec (range 200))
      kvs (zipmap (range 300) (map #(str "v" %) (range 300)))]
  (let [m (try (through (fn [sio] (L/reopen sio (L/build! sio (apply list xs))))) (catch Throwable t t))]
    (check "list of 200" (and (map? m) (all-equal? m #(vec (d/eve->clj %)) xs))))
  (let [m (try (through (fn [sio] (L/reopen sio (L/build! sio (set xs))))) (catch Throwable t t))]
    (check "hash set of 200" (and (map? m) (all-equal? m d/eve->clj (set xs)))))
  (let [m (try (through (fn [_] (apply es/sorted-map (mapcat identity kvs)))) (catch Throwable t t))]
    (check "eve.sorted sorted-map of 300 (a treap of eve-deftypes)"
           (and (map? m) (all-equal? m #(vec (map (fn [e] [(key e) (val e)]) (seq %))) (vec (sort-by first kvs)))))
    (when (map? m)
      (check "  sorted-map: lookup and subseq in place over the image"
             (and (= "v150" (get (:image m) 150)) (= 300 (count (:image m)))))))
  (let [m (try (through (fn [_] (apply es/sorted-set xs))) (catch Throwable t t))]
    (check "eve.sorted sorted-set of 200" (and (map? m) (all-equal? m #(vec (seq %)) xs))))
  (let [m (try (through (fn [_] (apply rb/sorted-set xs))) (catch Throwable t t))]
    (check "rb-tree sorted-set of 200" (and (map? m) (all-equal? m #(vec (seq %)) xs))))
  (let [m (try (through (fn [_] (apply im/int-map (mapcat identity kvs)))) (catch Throwable t t))]
    (check "int-map of 300" (and (map? m) (all-equal? m #(into (sorted-map) (seq %)) (into (sorted-map) kvs)))))
  (let [m (try (through (fn [sio] (arr/eve-array sio :float64 (mapv double xs)))) (catch Throwable t t))]
    (check "eve array :float64 of 200" (and (map? m) (all-equal? m #(vec (d/eve->clj %)) (mapv double xs)))))
  (let [m (try (through (fn [sio] (ds/dataset sio {:price (arr/eve-array sio :float64 (mapv double xs))
                                                   :qty (arr/eve-array sio :int32 xs)})))
               (catch Throwable t t))]
    (check "dataset of 2 columns x 200 rows"
           (and (map? m) (all-equal? m d/eve->clj {:price (mapv double xs) :qty xs})))
    (when (map? m)
      (check "  dataset: column read in place over the image" (= 199 (nth (ds/column (:image m) :qty) 199))))))

(println "== P5.2 a user eve-deftype (primitive, serialized, reference and mutable fields)")
(let [m (try (through make-item) (catch Throwable t t))
      fields (fn [it] [(:n it) (:hits it) (d/eve->clj (:payload it)) (:id-of-tag it) (count it)])]
  (check "user type: every field reads the same from all four"
         (and (map? m)
              (every? (fn [k] (let [it (get m k)]
                                (and (= 7 (:n it)) (= 0 (:hits it))
                                     (= {:k [1 2 3] :s "serialized"} (d/eve->clj (:payload it)))
                                     (some? (:tag it)) (= 7 (count it)))))
                      [:bump :slab :image :placed])))
  (when (map? m)
    (check "user type: the SAME class reads the image (no sister type was needed on this host)"
           (= (class (:slab m)) (class (:image m)) (class (:bump m))))
    (check "user type: default = is block identity, so image != slab original (census 1.6: equals = same offset)"
           (not= (:slab m) (:image m)))
    ;; A13: a mutable field written in the slab after the copy does not show in the image (a snapshot)
    (let [it (:slab m)
          sio (d/-eve-sio it)
          off (d/-root-header-off it)]
      (alloc/-sio-write-i32! sio off 8 99)     ;; hits is the second 4-byte slot (offset 8)
      (check "user type: a later in-place write to the slab original is not seen by the image (snapshot)"
             (and (= 99 (:hits (L/reopen sio off))) (= 0 (:hits (:image m))))))
    (check "user type: a write through a frozen view is NOT refused today (the view only refuses allocation)"
           (try (alloc/-sio-write-i32! (d/-eve-sio (:image m)) (d/-root-header-off (:image m)) 8 5)
                (= 5 (:hits (:image m)))
                (catch Throwable _ false)))))

(println "== P5.3 a DataScript database (the repository's port): DB / BTSet / Node / Leaf / Datom are eve-deftypes")
(try
  (require '[datascript.core :as dsc])
  (let [db-with (resolve 'datascript.core/db-with)
        empty-db (resolve 'datascript.core/empty-db)
        datoms (resolve 'datascript.core/datoms)
        q (resolve 'datascript.core/q)
        people (C/people 150)
        ctx (L/slab-domain)
        rs (L/rec-sio ctx)
        [db t-build] (L/ms (binding [d/*domain-sio* rs] (db-with (empty-db) people)))
        soff (d/-root-header-off db)
        tuples (fn [db] (mapv (fn [dt] [(:e dt) (:a dt) (:v dt)]) (datoms db :eavt)))
        expect (tuples db)
        query '[:find ?n :where [?e :age ?a] [(< ?a 3)] [?e :name ?n]]
        [im t-img] (L/ms (L/image! ctx soff (fn [o] (L/size-of rs o))))
        {:keys [census padded]} (L/class-census im)
        blocks (count (retire/reach ctx soff))]
    (println (format "  built %d people = %d datoms in %.0f ms; reach %d blocks; image %,d B (%,d blocks, %,d pointers); class-padded %,d B; %s"
                     (count people) (count expect) t-build blocks (:len im) (:nblocks im) (:nrel im) padded census))
    (println (format "  per datom: %.0f B in the slab (class-padded), %.0f B in the image; copy out %.1f ms"
                     (/ (double padded) (count expect)) (/ (double (:len im)) (count expect)) t-img))
    (check "DataScript: reach and image agree on the block count" (= blocks (:nblocks im)))
    (let [view (L/image-view im)]
      (check "DataScript: the DB read in place from the image has the same datoms" (= expect (tuples view)))
      (check "DataScript: a Datalog query runs over the image view" (= (q query db) (q query view)))
      (let [r (bench 2 5 #(tuples view)) r0 (bench 2 5 #(tuples db))]
        (println (format "  scan all datoms: slab %.1f ms, image view %.1f ms" (:min r0) (:min r)))))
    (let [tgt (L/slab-domain (L/caps-for im 3))
          [poff t-place] (L/ms (L/ingest! tgt im :runs))
          placed (L/reopen tgt poff)]
      (println (format "  image -> a fresh slab domain by run claims: %.1f ms (the build took %.0f ms)" t-place t-build))
      (check "DataScript: the image placed in a fresh domain has the same datoms" (= expect (tuples placed)))
      (check "DataScript: and can be transacted on there"
             (= (inc (count expect))
                (count (tuples (binding [d/*domain-sio* tgt] (db-with placed [[:db/add 1 :nickname "new"]]))))))))
  (catch Throwable t
    (note "DataScript section" :ERROR (.getSimpleName (class t)) ": " (.getMessage t))
    (.printStackTrace t)))

(println)
(let [rs @results]
  (println (format "P5 RESULT: %d PASS, %d FAIL, %d ERROR of %d"
                   (count (filter #(= :PASS (second %)) rs)) (count (filter #(= :FAIL (second %)) rs))
                   (count (filter #(= :ERROR (second %)) rs)) (count rs)))
  (doseq [[l s] rs :when (not= s :PASS)] (println "  " (name s) "-" l)))
(System/exit 0)
