(ns eve.release-suite-test
  "THE RELEASE SUITE — the bounded middle battery a release candidate runs on
   every clj* host: bigger than a smoke, far smaller than the full corpus.

   One namespace, one process per host, run through the tree's own per-host
   runner (bb test:<host> eve.release-suite-test; clojure -M:jvm-test -n
   eve.release-suite-test). Driven end to end by scripts/release-suite.sh,
   which caps each leg (default 60-120 s) and budgets the whole run at
   10-20 minutes across all hosts.

   Each area asserts PROPERTIES of a surface the release exercises, in the
   idioms the corpus already proves on that surface, and prints
   \"AREA <name> OK\" as it completes — a leg killed by its cap still shows
   which areas ran. Areas:

     seams        eve.math + eve.edn reader (the seams every consumer hits)
     mem-atom     in-memory atom: swap/reset/deref under repeated updates
     disk-atom    mmap domain: write, close, reopen, value + journal survive
     collections  1,000-entry map / vector / set built and read inside swaps
     codec        eve.net.codec wire format: deterministic, round-trips
     crypto       SHA-256 + HMAC-SHA-256 against the published vectors
     net-atom     a network atom over the in-process broker: a swap commits,
                  a second client reads it, the root key is the root's SHA
     proc-io      eve.process: exclusive create, file existence, clock, entropy
     thread       eve.thread promise + registered-task future

   The http serve area lives in eve.release-suite-http-test (its own
   namespace) so a host without an http-server adapter fails only that leg,
   never this one."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.atom :as ea]
            [eve.edn :as eedn]
            [eve.math :as emath]
            [eve.process :as proc]
            [eve.thread :as t]
            [eve.net.atom :as na]
            [eve.net.bytes :as b]
            [eve.net.codec :as codec]
            [eve.net.crypto :as ncrypto]
            [eve.net.sha256 :as sha]
            [eve.net.stand-in :refer [stand-in]]
            ;; eve.host is required unconditionally (every host has one):
            ;; the :clje swap!/reset! shims below alias to it, and a #?@
            ;; splice in this ns's :require crashes clojerl's analyzer.
            ; the alias is exercised on the other reader pass; the require loads on both 
            #_{:clj-kondo/ignore [:unused-namespace]}   [eve.host :as host]))

(defn- area! [n] (println "AREA" n "OK") (flush))

;; clojerl's clojure.core/swap! and reset! are hardcoded to clojerl.Atom
;; (.swap/.reset on the concrete type — no protocol dispatch), so on that
;; host the suite's swap!/reset! are eve.host's dispatching realization,
;; the same one every Eve namespace on the BEAM uses.
#?(:clje
   (do
     (defn swap! [& args] (apply host/swap! args))
     (defn reset! [a v] (host/reset! a v))))

;; ---------------------------------------------------------------- seams

(deftest seams-properties
  (is (= 3.0 (emath/sqrt 9)))
  (is (= {:a [1 2]} (eedn/read-string "{:a [1 2]}")))
  (is (number? (eedn/read-string "3.50")))
  (area! "seams"))

;; ---------------------------------------------------------------- in-memory atom

(deftest mem-atom-properties
  (let [a (ea/atom ::suite-mem {:count 0 :log []})]
    (dotimes [_ 200] (swap! a update :count inc))
    (is (= 200 (:count @a)))
    (swap! a update :log conj :done)
    (is (= [:done] (:log @a)))
    (reset! a {:count -1})
    (is (= -1 (:count @a)))
    (area! "mem-atom")))

;; ---------------------------------------------------------------- disk atom

(deftest disk-atom-properties
  (let [base (str "target/release-suite/disk-" (rand-int 1000000000) "/d")
        dom (ea/persistent-atom-domain base)
        a (ea/persistent-atom {:id ::suite-disk :persistent base} {:n 0})]
    (dotimes [_ 50] (swap! a update :n inc))
    (is (= 50 (:n @a)))
    (ea/close-atom-domain! dom)
    (testing "the domain's root file is on disk after close"
      (is (proc/file-exists? (str base ".root"))))
    (testing "a reopened domain joins what it wrote"
      (let [dom2 (ea/persistent-atom-domain base)
            a2 (ea/persistent-atom {:id ::suite-disk :persistent base} {:n -1})]
        (is (= 50 (:n @a2)))
        (ea/close-atom-domain! dom2)))
    (area! "disk-atom")))

;; ---------------------------------------------------------------- collections through atoms

(deftest collections-properties
  (let [a (ea/atom ::suite-coll {})]
    (swap! a (fn [_] (into {} (map (fn [i] [(keyword (str "k" i)) i]) (range 1000)))))
    (is (= 1000 (count @a)))
    (is (= 999 (:k999 @a)))
    (swap! a dissoc :k0)
    (is (= 999 (count @a)))
    (is (nil? (:k0 @a)))
    (swap! a (fn [m] (assoc m :vec (vec (range 500)) :set (set (range 100)))))
    (is (= 499 (nth (:vec @a) 499)))
    (is (contains? (:set @a) 99))
    (area! "collections")))

;; ---------------------------------------------------------------- wire codec

(deftest codec-properties
  (let [v {:name "eve" :n 42 :items [1 2 {:x "héllo"}] :s #{1 2 3}}
        e1 (codec/encode v)
        e2 (codec/encode v)]
    (is (= e1 e2) "the same value images to the same blocks (deterministic)")
    (is (= v (codec/decode (:root e1) (:set-root? e1)
                           (fn [k] (get (:blocks e1) k))))
        "and decodes back to the same value")
    (area! "codec")))

;; ---------------------------------------------------------------- crypto

(deftest crypto-properties
  (is (= "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
         (sha/sha256-hex (b/utf8 ""))))
  (is (= "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
         (sha/sha256-hex (b/utf8 "abc"))))
  (is (= "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7"
         (b/hex (ncrypto/hmac-sha256 (vec (repeat 20 11)) (b/utf8 "Hi There"))))
      "HMAC-SHA-256, RFC 4231 case 1")
  (area! "crypto"))

;; ---------------------------------------------------------------- network atom

(def ^:private net-cfg {:endpoint "https://broker.example" :domain-id "d1" :atom-id "a1" :api-key "k"})

(deftest net-atom-properties
  (let [s (stand-in {})
        platform {:request (:request s) :sleep (fn [_]) :now-ms (fn [] 1700000000000) :seed 42}
        ra (na/remote-atom net-cfg platform)]
    ((:request s) {:method "POST" :url "https://broker.example/v1/domains/d1/atoms/a1/create"
                   :headers {"Authorization" "Bearer k"} :body "{\"atom_id\":\"a1\"}"})
    (is (= {} (na/deref ra)) "an empty network atom derefs to {}")
    (is (= {:n 1} (na/swap! ra assoc :n 1)))
    (testing "the broker holds epoch 1 and the root key is the root blob's SHA-256"
      (let [row (get-in @(:state s) [:atoms "a1"])
            blob (b/from-b64 (:root_blob row))]
        (is (= 1 (:epoch row)))
        (is (= (sha/sha256-hex blob) (:root_key row)))))
    (testing "a second client on the same broker reads the committed value"
      (let [ra2 (na/remote-atom net-cfg platform)]
        (is (= {:n 1} (na/deref ra2)))))
    (area! "net-atom")))

;; ---------------------------------------------------------------- proc + io surface

(deftest proc-io-properties
  (let [f (str "target/release-suite/io-" (rand-int 1000000000) "/probe")]
    (proc/ensure-parent-dir! f)
    (is (true? (proc/create-exclusive! f)) "first exclusive create wins")
    (is (false? (proc/create-exclusive! f)) "second exclusive create loses")
    (is (proc/file-exists? f)))
  (let [t0 (proc/now-ms)]
    (proc/sleep-ms! 15)
    (is (>= (- (proc/now-ms) t0) 5) "the clock advances across a sleep"))
  (is (= 32 (count (proc/random-bytes 32))))
  (is (= (proc/identity-hash :x) (proc/identity-hash :x)))
  (area! "proc-io"))

;; ---------------------------------------------------------------- thread

(t/deftask suite-task [] :done)

(deftest thread-properties
  (let [p (t/promise)]
    (is (false? (realized? p)))
    (t/deliver p :ok)
    (is (true? (realized? p)))
    (is (= :ok @p)))
  (let [f (t/future-call suite-task)]
    (is (= :done @f))
    (is (true? (realized? f))))
  (area! "thread"))
