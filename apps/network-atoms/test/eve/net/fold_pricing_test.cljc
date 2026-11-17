(ns eve.net.fold-pricing-test
  "The codec fold's acceptance, at the fold's own granularity (trunk audit entry 4 + the net extraction,
   Nov 14 2026). eve.net.pruned-swap-test priced the OLD imaging — codec/put-block tallies over encode-rec's
   block graph — and is retired with that machinery; its claims re-stated here over entry images:
   - a one-entry change is priced per entry image: the changed entry's trunk image uploads — when the
     entry is a collection and its image is counted for the first time — beside the swap's constants
     (the new root blob and the swap's graft), and a scalar entry has no image: its trunk bytes ride
     inline in the root blob, so only the constants move;
   - the retired set is exactly the old root and the images the new value no longer reaches;
   - a fetched image that decodes but is not the canonical trunk encoding of its value is refused
     (:non-canonical) — the fold's trust gate, beside the store's address check;
   - the handle is the NetAtom type above the trunk atom contract: deref / swap! / reset! /
     compare-and-set!, fields answering as before ((:platform ra))."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.net.atom :as a]
            [eve.net.bytes :as b]
            [eve.net.graft :as graft]
            [eve.net.run :as r]
            [eve.net.sha256 :as sha]
            [eve.net.snapshot :as snap]
            [eve.net.stand-in :refer [stand-in]]))

(def ^:private cfg {:endpoint "https://broker.example" :domain-id "d1" :atom-id "a1" :api-key "k"})

(defn- setup []
  (let [s (stand-in {})
        p {:request (:request s) :sleep (fn [_] nil) :now-ms (fn [] 1700000000000) :seed 42}
        ra (a/remote-atom cfg p)]
    ((:request s) {:method "POST" :url (str (:endpoint cfg) "/v1/domains/d1/atoms/a1/create")
                   :headers {"Authorization" "Bearer k"} :body "{\"atom_id\":\"a1\"}"})
    [ra s p]))

(defn- puts [s] (map second (filter (fn [e] (= "PUT" (first e))) @(:log s))))
(defn- img-key [v] (sha/sha256-hex (snap/trunk-bytes v)))
(defn- root-key [ra] (:root-key @(.-state ra)))

(defn- swap-graft
  "the uploaded key whose bytes are this swap's graft — the GRFT V3 record naming old-root → new-root —
   or nil when no uploaded key decodes as that graft"
  [s uploaded old-root new-root]
  (first (filter (fn [k] (try (let [g (graft/decode (get @(:store s) k))]
                                (and (= old-root (:old-root g)) (= new-root (:new-root g))))
                              (catch #?(:cljs :default :lpy python/Exception :jank jank.runtime.object_ref :default Exception) _ false)))
                 uploaded)))

(deftest a-one-entry-change-is-priced-per-entry-image
  (testing "a collection entry: its image uploads beside the new root and the graft; the old root and its replaced image retire"
    (let [[ra s _] (setup)]
      (a/reset! ra {:a [1] :b [2] :c [3]})
      (let [before (set (keys @(:store s)))
            old-root (root-key ra)]
        (a/swap! ra assoc :b [20])
        (let [uploaded (set (remove before (keys @(:store s))))
              retired (set (get-in @(:state s) [:atoms "a1" :retired]))
              new-root (root-key ra)
              gk (swap-graft s uploaded old-root new-root)]
          (is (some? gk) "one of the uploads is this swap's graft, naming old-root → new-root")
          (is (= [(img-key [20])] (:blobs (graft/decode (get @(:store s) gk))))
              "the graft names exactly the changed entry's image as this swap's upload")
          (is (= #{(img-key [20]) new-root gk} uploaded)
              "the changed entry's image, the new root blob and the graft — nothing else is PUT")
          (is (= #{old-root (img-key [2])} retired)
              "exactly the old root and the replaced entry's image retire; unchanged entries' images stay")
          (is (= {:a [1] :b [20] :c [3]} (a/deref (a/remote-atom cfg (.-platform ra))))
              "and the value reads back whole, from a fresh handle")))))
  (testing "a scalar entry has no image: its bytes ride in the root blob, so only the root and the graft move"
    (let [[ra s _] (setup)]
      (a/reset! ra {:a 1 :b 2 :c 3})
      (let [before (set (keys @(:store s)))
            old-root (root-key ra)]
        (a/swap! ra assoc :b 20)
        (let [uploaded (set (remove before (keys @(:store s))))
              retired (set (get-in @(:state s) [:atoms "a1" :retired]))
              new-root (root-key ra)
              gk (swap-graft s uploaded old-root new-root)]
          (is (some? gk) "one of the uploads is this swap's graft")
          (is (= #{new-root gk} uploaded)
              "no entry image exists to PUT — the new root blob and the graft are the whole upload")
          (is (= #{old-root} retired) "only the old root retires")
          (is (= {:a 1 :b 20 :c 3} (a/deref (a/remote-atom cfg (.-platform ra))))
              "and the value reads back whole, from a fresh handle")))))
  (testing "an image another entry already counts is not uploaded again: counting is by frequency over entries"
    (let [[ra s _] (setup)]
      (a/reset! ra {:a [9] :b [2]})
      (let [before (set (keys @(:store s)))
            old-root (root-key ra)]
        (a/swap! ra assoc :b [9])
        (let [uploaded (set (remove before (keys @(:store s))))
              retired (set (get-in @(:state s) [:atoms "a1" :retired]))
              new-root (root-key ra)
              gk (swap-graft s uploaded old-root new-root)]
          (is (some? gk) "one of the uploads is this swap's graft")
          (is (= #{new-root gk} uploaded)
              "[9]'s image was already counted by :a, so it is not PUT again — the root and the graft only")
          (is (= #{old-root (img-key [2])} retired)
              "the old root and the image whose count fell to zero retire"))))))

(deftest a-non-canonical-image-is-refused
  (let [[_ s p] (setup)
        kb (snap/trunk-bytes :x)
        slot (fn [img] (snap/ref-slot (sha/sha256-hex img)))
        blob-of (fn [img] (snap/frame [[kb (slot img)]]))
        canonical (snap/trunk-bytes 5)
        ;; 5 imaged in the trunk's INT64 form: decodes to 5, re-encodes to the INT32 form — not canonical
        wide (b/join [[0xEE 0xDB 0x0F] (b/u32le 5) (b/u32le 0)])]
    (testing "the canonical image decodes"
      (is (= {:x 5} (:value (r/run p (a/value-step cfg (blob-of canonical)
                                                   {(sha/sha256-hex canonical) canonical}))))))
    (testing "the wide form of the same value is refused :non-canonical"
      (is (= :non-canonical
             (try (r/run p (a/value-step cfg (blob-of wide) {(sha/sha256-hex wide) wide}))
                  :no-throw
                  (catch #?(:cljs :default :lpy python/Exception :jank jank.runtime.object_ref :default Exception) e
                    (:type (ex-data e)))))))))

(deftest the-handle-answers-the-atom-contract
  (let [[ra _ p] (setup)]
    (is (= p (.-platform ra)) "the handle's fields answer as before the re-expression")
    (a/reset! ra {:a 1})
    (is (= {:a 1} (a/deref ra)))
    (is (true? (a/compare-and-set! ra {:a 1} {:a 2})) "CAS from the held value commits")
    (is (= {:a 2} (a/deref ra)))
    (is (false? (a/compare-and-set! ra {:a 1} {:a 3})) "CAS from a stale value refuses, one attempt")
    (is (= {:a 2} (a/deref ra)) "the refused CAS changed nothing")
    (is (= {:a 2 :b 1} (a/swap! ra assoc :b 1)) "swap! answers the committed value")))
