(ns eve.net.pruned-swap-test
  "The cost of ONE swap! on a large network atom (eve.net.atom against the in-process broker and store,
   eve.net.stand-in), counted from outside the code under test so the counts mean the same before and after the swap is
   pruned to the changed path:
     - the store and broker requests the swap makes, from the stand-in's request log (store requests are logged as
       [method key], broker requests as [method url parsed-json-body]): HEADs, PUTs, GETs, presign calls by mode;
     - the block images encoded, by counting calls to eve.net.codec's put-block (every block image becomes its key there,
       a set root's too) and the snapshot blobs built (eve.net.snapshot/frame).
   Each scenario prints one line starting MD4-COUNT. The budget assertions (HEADs ≤ new blocks + 1 for the snapshot,
   put-block calls ≤ new blocks, one snapshot frame) are the pruned swap's done bar; the correctness assertions (the
   value a fresh handle reads, the broker's retired keys) hold before and after."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.set :as set]
            [eve.net.atom :as a]
            [eve.net.codec :as codec]
            [eve.net.snapshot :as snap]
            [eve.net.stand-in :refer [stand-in]]
            [eve.net.bytes :as b]
            [eve.net.run :as r]
            [eve.crypto :as prim]
            [eve.process :as process]))

(def ^:private cfg {:endpoint "https://dev.atom.broker" :domain-id "d1" :atom-id "a1" :api-key "k"})

(def ^:private FLAT-N "entries of the flat map scenario" 10000)
(def ^:private NESTED-N "entries of the nested map scenario" 10000)

(defn- setup
  "[handle stand-in] — eve.net.remote-atom-test's setup"
  []
  (let [s (stand-in {})
        p {:request (:request s) :sleep (fn [_] nil) :now-ms (fn [] 1700000000000) :seed 42}
        ra (a/remote-atom cfg p)]
    ((:request s) {:method "POST" :url (str (:endpoint cfg) "/v1/domains/d1/atoms/a1/create")
                   :headers {"Authorization" "Bearer k"} :body "{\"atom_id\":\"a1\"}"})
    [ra s]))

(defn- route "the last path segment of a broker url" [url] (last (str/split url #"/")))

(defn- tally
  "the counts of stand-in log entries: store requests by method, broker requests by route, presign calls by mode"
  [entries]
  (let [store (filter (fn [e] (= 2 (count e))) entries)
        broker (filter (fn [e] (= 3 (count e))) entries)
        presigns (filter (fn [[_ url _]] (= "presign" (route url))) broker)
        by-method (fn [m] (count (filter (fn [e] (= m (first e))) store)))]
    {:heads (by-method "HEAD")
     :puts (by-method "PUT")
     :gets (by-method "GET")
     :presign (count presigns)
     :presign-by-mode (into (sorted-map) (frequencies (map (fn [[_ _ req]] (get req "mode")) presigns)))
     :presign-keys-by-mode (into (sorted-map)
                                 (reduce (fn [m [_ _ req]] (update m (get req "mode") (fnil + 0) (count (get req "keys"))))
                                         {} presigns))
     :broker-by-route (into (sorted-map) (frequencies (map (fn [[_ url _]] (route url)) broker)))}))

(defn- measured-swap!
  "(apply a/swap! ra f args), counted: the tally of the requests it made, :put-block (codec/put-block calls), :frames
   (snap/frame calls), :ms (wall), :value (what swap! answered)"
  [ra s f & args]
  (let [mark (count @(:log s))
        put-block @#'codec/put-block
        frame snap/frame
        blocks (atom 0)
        frames (atom 0)
        t0 (process/now-ms)
        ;; put-block's two arities both: compiled ClojureScript calls a multi-arity fn's arity entry point directly
        v (with-redefs [codec/put-block (fn ([acc image] (swap! blocks inc) (put-block acc image))
                                          ([acc image set-root?] (swap! blocks inc) (put-block acc image set-root?)))
                        snap/frame (fn [entries] (swap! frames inc) (frame entries))]
            (apply a/swap! ra f args))
        ms (- (process/now-ms) t0)]
    (assoc (tally (subvec @(:log s) mark)) :put-block @blocks :frames @frames :ms ms :value v)))

(defn- block-keys [v] (set (keys (:blocks (codec/encode v)))))

(defn- report!
  "prints the scenario's MD4-COUNT line"
  [scenario n value-blocks new-blocks m]
  (println (str "MD4-COUNT scenario=" scenario " entries=" n
                " value-blocks=" value-blocks " new-blocks=" new-blocks
                " heads=" (:heads m) " puts=" (:puts m) " gets=" (:gets m)
                " presign=" (:presign m) " presign-by-mode=" (pr-str (:presign-by-mode m))
                " presign-keys-by-mode=" (pr-str (:presign-keys-by-mode m))
                " broker=" (pr-str (:broker-by-route m))
                " put-block=" (:put-block m) " frames=" (:frames m) " ms=" (:ms m))))

(defn- one-swap
  "reset! the atom to old, then ONE measured (apply a/swap! ra f args); prints the MD4-COUNT line and asserts the budget
   and correctness"
  [scenario n old f & args]
  (let [[ra s] (setup)
        _ (a/reset! ra old)
        old-root (get-in @(:state s) [:atoms "a1" :root_key])
        m (apply measured-swap! ra s f args)
        new (apply f old args)
        old-keys (block-keys old)
        new-keys (block-keys new)
        new-blocks (set/difference new-keys old-keys)]
    (report! scenario n (count new-keys) (count new-blocks) m)
    (testing (str scenario ": the budget (the pruned swap's done bar)")
      (is (<= (:heads m) (inc (count new-blocks))) "HEADs ≤ the new blocks + the snapshot")
      (is (<= (:put-block m) (count new-blocks)) "block images encoded ≤ the new blocks")
      (is (= 1 (:frames m)) "one snapshot frame"))
    (testing (str scenario ": correctness")
      (is (= new (:value m)) "swap! answers f's value")
      (is (= new (a/deref (a/remote-atom cfg (:platform ra)))) "a fresh handle reads the new value")
      (is (= (set (cons old-root (set/difference old-keys new-keys)))
             (set (get-in @(:state s) [:atoms "a1" :retired])))
          "the broker retires the old root and exactly the old blocks the new value no longer reaches"))))

(deftest one-key-swap-on-a-10k-map
  (testing "a flat map, keyword → int"
    (one-swap "flat" FLAT-N (into {} (map (fn [i] [(keyword (str "k" i)) i]) (range FLAT-N))) assoc :k5000 -1))
  (testing "a map of small nested maps"
    (one-swap "nested" NESTED-N (into {} (map (fn [i] [(keyword (str "k" i)) {:i i}]) (range NESTED-N))) assoc-in [:k5000 :i] -1)))

(deftest swap-replacing-one-subtree-retires-exactly-its-blocks
  (let [[ra s] (setup)
        big (into {} (map (fn [i] [(keyword (str "b" i)) i]) (range 1000)))
        old-a {:x [1 2 3] :y "s"}
        v {:big big :a old-a :b [1 2 3]}
        _ (a/reset! ra v)
        old-root (get-in @(:state s) [:atoms "a1" :root_key])
        m (measured-swap! ra s assoc :a {:z 2})
        new (assoc v :a {:z 2})
        old-keys (block-keys v)
        new-keys (block-keys new)
        retired (set (get-in @(:state s) [:atoms "a1" :retired]))
        a-keys (set (keys (second (codec/band-of old-a))))
        big-keys (set (keys (second (codec/band-of big))))
        b-keys (set (keys (second (codec/band-of (:b v)))))
        a-only (set/difference a-keys new-keys)]
    (report! "subtree" 1000 (count new-keys) (count (set/difference new-keys old-keys)) m)
    (println (str "MD4-COUNT scenario=subtree-retire a-blocks=" (count a-keys) " a-blocks-still-reached=" (count (set/intersection a-keys new-keys))
                  " a-blocks-retired=" (count (set/intersection a-keys retired)) " retired=" (count retired)))
    (is (= new (:value m)))
    (is (= (set (cons old-root (set/difference old-keys new-keys))) retired)
        "the old root and exactly the old blocks the new value no longer reaches")
    (is (seq a-only) "the old :a subtree has blocks the new value no longer reaches")
    (is (every? retired a-only) "every block of the old :a subtree the new value no longer reaches is retired")
    ;; :a's :x is [1 2 3], the same value as :b — its two blocks are :b's and stay live
    (is (not-any? retired b-keys) "none of :b's blocks (shared with the old :a's :x) is retired")
    (is (not-any? retired big-keys) "none of :big's blocks is retired")
    (is (= new (a/deref (a/remote-atom cfg (:platform ra)))) "a fresh handle reads the new value")))

;; ── C1: an encode against the record of the value it replaces is the full encode ─────────────────────────────────

(defn- rnd! "a draw in [0, n) from the seeded PRNG in st" [st n]
  (let [[d nxt] (r/next-double @st)] (reset! st nxt) (int (* d n))))

(defn- rnd-scalar [st]
  (case (rnd! st 6)
    0 (rnd! st 1000) 1 (str "s" (rnd! st 50)) 2 (keyword (str "v" (rnd! st 50))) 3 (+ 0.5 (rnd! st 100))
    4 nil 5 (- (* 10000 (rnd! st 1000000)) 5000000000)))

(defn- rnd-value [st depth]
  (if (or (zero? depth) (< (rnd! st 10) 5))
    (rnd-scalar st)
    (case (rnd! st 3)
      0 (into {} (map (fn [_] [(keyword (str "n" (rnd! st 40))) (rnd-value st (dec depth))]) (range (rnd! st 12))))
      1 (vec (map (fn [_] (rnd-value st (dec depth))) (range (rnd! st 70))))
      2 (into #{} (map (fn [_] (keyword (str "e" (rnd! st 60)))) (range (rnd! st 20)))))))

(defn- rnd-edit
  "a random edit of the collection x: an entry added, changed or removed, an element conj'd, popped or replaced, a nested
   collection edited in place"
  [st x depth]
  (cond
    (map? x) (let [ks (vec (keys x)) k (when (seq ks) (nth ks (rnd! st (count ks))))]
               (case (if k (rnd! st 4) 0)
                 0 (assoc x (keyword (str "n" (rnd! st 400))) (rnd-value st depth))
                 1 (assoc x k (rnd-value st depth))
                 2 (dissoc x k)
                 3 (let [v (get x k)] (if (and (coll? v) (pos? depth)) (assoc x k (rnd-edit st v (dec depth))) (dissoc x k)))))
    (vector? x) (case (if (seq x) (rnd! st 4) 0)
                  0 (into x (map (fn [_] (rnd-scalar st)) (range (inc (rnd! st 40)))))
                  1 (pop x)
                  2 (assoc x (rnd! st (count x)) (rnd-value st depth))
                  3 (let [i (rnd! st (count x)) v (nth x i)] (if (and (coll? v) (pos? depth)) (assoc x i (rnd-edit st v (dec depth))) (pop x))))
    (set? x) (if (and (seq x) (zero? (rnd! st 2))) (disj x (first x)) (conj x (keyword (str "e" (rnd! st 200)))))
    :else x))

(defn- reached "every key the tree under root reaches through images" [root images]
  (loop [frontier [root] seen #{}]
    (if (empty? frontier)
      seen
      (let [nxt (remove seen (mapcat (fn [k] (codec/child-keys (get images k))) frontier))]
        (recur (vec (distinct nxt)) (into seen frontier))))))

(deftest incremental-encode-is-the-full-encode
  (let [st (atom (r/rng 7))
        v0 (into {} (map (fn [i] [(keyword (str "n" i)) (rnd-value st 2)]) (range 40)))]
    (loop [i 0 v v0 [rec built] (codec/encode-rec v0 nil) images built bad []]
      (if (= i 120)
        (is (empty? bad) (str "every step's keys are the full encode's: " (pr-str (take 3 bad))))
        (let [v2 (rnd-edit st v 2)
              [rec2 built2] (codec/encode-rec v2 rec)
              full (codec/encode v2)
              images (merge images built2)
              ok (and (= (:root full) (codec/rec-key rec2))
                      (every? (fn [[k img]] (= img (get (:blocks full) k))) built2)
                      (= (set (keys (:blocks full))) (reached (:root full) images)))]
          (recur (inc i) v2 [rec2 built2] images (if ok bad (conj bad i)))))))
  (testing "a key replaced by another of the same key bytes (1 out, 1.0 in: two Clojure keys, one f64) in one change"
    (let [v {1 :a :b 2} [rec _] (codec/encode-rec v nil) v2 (assoc (dissoc v 1) 1.0 :a) [rec2 _] (codec/encode-rec v2 rec)]
      (is (= (:root (codec/encode v2)) (codec/rec-key rec2))))))

;; ── C3: a fetched tree is checked canonical before its keys are trusted ───────────────────────────────────────────

(def ^:private node-image @#'codec/node-image)
(def ^:private frag @#'codec/frag)
(def ^:private key-bytes @#'codec/key-bytes)

(defn- sha-hex [bs] (b/hex (prim/sha256 bs)))
(defn- slot [band] (vec band))   ;; ExtCID v3: a slot images as the child's 32 bytes alone (FORMAT.md §2.6)
(defn- entry [k v] (let [kb (key-bytes k)] {:kb kb :h (vec (prim/xxh3-128 kb)) :vb (first (codec/band-of v))}))
(defn- map-root [n child] (b/join [[0xED] (b/u32le n) (if child (slot (b/unhex child)) (repeat 32 0))]))

(defn- crafted
  "{:root key :images {key image}} of a map root over the bitmap node image `node` (with child node images), counted n"
  [n node & children]
  (let [nk (sha-hex node) root (map-root n nk)]
    {:root (sha-hex root) :images (into {nk node (sha-hex root) root} (map (fn [img] [(sha-hex img) img]) children))}))

(defn- two-sharing-frag0
  "two keyword keys whose hashes share their first 5-bit slice (they share a child node of the root)"
  []
  (let [ks (map (fn [i] (keyword (str "c" i))) (range 200))
        by (group-by (fn [k] (frag (:h (entry k 0)) 0)) ks)]
    (vec (take 2 (first (filter (fn [g] (<= 2 (count g))) (vals by)))))))

(defn- reason-of [{:keys [root images]}]
  (try (codec/decode-rec (b/unhex root) images) :accepted
       (catch #?(:cljs :default :lpy python/Exception :default Exception) e (or (:reason (ex-data e)) (ex-message e)))))

(defn- crafted-cases
  "[name, its expected reason, the crafted tree] — each one rule broken, every block under the key its bytes hash to"
  []
  (let [[k1 k2] (two-sharing-frag0)
        e1 (entry k1 1) e2 (entry k2 2) f1 (frag (:h e1) 0)
        lone (node-image {(frag (:h e1) 5) {:e e1}})
        other (first (remove (fn [f] (= f f1)) (range 32)))
        nil-child (let [img (node-image {f1 {:c {:k (apply str (repeat 64 "0"))}}})] img)
        good (codec/encode {k1 1})
        relaxed (let [{:keys [root blocks]} (codec/encode (vec (range 40)))
                      rimg (get blocks root)
                      leaf (b/hex (subvec (vec rimg) 49 81))   ;; the trie slot (v3: tail @17, trie @49)
                      bad-leaf (assoc (vec (get blocks leaf)) 1 1)
                      bk (sha-hex bad-leaf)
                      broot (vec (concat (subvec (vec rimg) 0 49) (b/unhex bk)))]
                  {:root (sha-hex broot) :images (assoc blocks bk bad-leaf (sha-hex broot) broot)})]
    [["a node below the root holding one entry" 9 (crafted 1 (node-image {f1 {:c {:k (sha-hex lone)}}}) lone)]
     ["a data entry off its hash's slice" 13 (crafted 1 (node-image {other {:e e1}}))]
     ["a root counting 2 over one entry" 14 (crafted 2 (node-image {f1 {:e e1}}))]
     ["a hash that is not its key's" 12 (crafted 1 (node-image {(frag (:h e2) 0) {:e (assoc e1 :h (:h e2))}}))]
     ["a nil child under a set bit" 8 (crafted 2 nil-child)]
     ["a node whose pad is not zero" 15 (let [img (assoc (vec (node-image {f1 {:e e1}})) 1 7)] (crafted 1 img))]
     ["an RRB relaxed vector node" 17 relaxed]
     ["the canonical tree" :accepted {:root (:root good) :images (:blocks good)}]]))

(deftest a-crafted-tree-is-refused-with-its-reason
  (doseq [[what want tree] (crafted-cases)]
    (is (= want (reason-of tree)) what)))

(deftest a-crafted-fetch-is-refused
  (testing "a snapshot naming a crafted tree: the deref refuses it, with its reason, before any key of it is kept"
    (doseq [[what want tree] (crafted-cases)]
      (let [[ra s] (setup)
            blob (snap/frame [[(snap/serialize-scalar :t nil) (snap/ref-slot (:root tree))]])
            k (sha-hex blob)]
        (swap! (:store s) merge (:images tree))
        (swap! (:state s) assoc-in [:atoms "a1"] {:root_key k :epoch 1 :root_blob (b/to-b64 blob) :root_count 1})
        (let [got (try (a/deref ra) (catch #?(:cljs :default :lpy python/Exception :default Exception) e
                                      (assoc (ex-data e) :thrown true)))]
          (if (= :accepted want)
            (is (= {:t {(first (two-sharing-frag0)) 1}} got) what)
            (do (is (= [:non-canonical want] [(:type got) (:reason got)]) what)
                (is (nil? (:rec @(:state ra))) (str what ": the handle keeps no record of it")))))))))

;; ── a reader syncs only what another writer changed ───────────────────────────────────────────────────────────────

(deftest a-second-writer-fetches-and-imaged-only-the-change
  (let [[ra s] (setup)
        rb (a/remote-atom cfg (:platform ra))
        v (into {} (map (fn [i] [(keyword (str "k" i)) {:i i :s (str "s" i)}]) (range 2000)))]
    (a/reset! ra v)
    (is (= v (a/deref rb)) "the second handle reads the value whole")
    (a/swap! ra assoc-in [:k7 :i] -7)
    (let [old-root (get-in @(:state s) [:atoms "a1" :root_key])
          old (assoc-in v [:k7 :i] -7)
          new (assoc-in old [:k9 :i] -9)
          m (measured-swap! rb s assoc-in [:k9 :i] -9)
          new-blocks (set/difference (block-keys new) (block-keys old))]
      (report! "second-writer" 2000 (count (block-keys new)) (count new-blocks) m)
      (is (= new (:value m)))
      (is (<= (:gets m) 2) "the second writer fetches only :k7's new map (its block, and the value block it names if new)")
      (is (<= (:heads m) (inc (count new-blocks))) "HEADs ≤ its new blocks + the snapshot")
      (is (= (set (cons old-root (set/difference (block-keys old) (block-keys new))))
             (set (get-in @(:state s) [:atoms "a1" :retired])))
          "it retires the old root and exactly the old blocks the new value no longer reaches")
      (is (= new (a/deref (a/remote-atom cfg (:platform ra))))))))
