(ns coord-wire-probe
  "THROWAWAY PROBE (eve/doc/network-atoms/SCOPE.md §2). Two clients increment one network atom M times each through
   vs.net.coordinator over the host's real `fetch` op (vs-term --bridge), against the stand-in broker at $BROKER_PORT
   (coord_standin.py). Rounds are scheduled like tl_02c: every unfinished client derefs, then every unfinished client
   CASes, so all but one CAS of a round conflict. The value rides as text in root_blob; the key is its SHA-256.
   Prints one line: `final:<value> conflicts:<n> cas-ok:<n>`. A correct broker ends at final = 2 x M."
  (:require [vs.net.coordinator :as c]
            [vs.net.s3-sign :as s3]))

(def M 5)
(def cfg {:endpoint (str "http://127.0.0.1:" (System/getenv "BROKER_PORT"))
          :domain-id "d1" :atom-id "a1" :api-key "probe-key"})

(defn- value-of [d]
  (let [b (:root-blob d)]
    (if (or (nil? b) (= b "")) 0 (parse-long b))))

(c/create-atom cfg)

(loop [done {0 0 1 0} conflicts 0 oks 0 rounds 0]
  (let [live (filter (fn [i] (< (get done i) M)) [0 1])]
    (if (or (empty? live) (> rounds 100))
      (println (str "final:" (value-of (c/deref-atom cfg)) " conflicts:" conflicts " cas-ok:" oks))
      (let [reads (into {} (map (fn [i] [i (c/deref-atom cfg)]) live))
            res (reduce (fn [[dn cf ok] i]
                          (let [d (get reads i)
                                nv (str (inc (value-of d)))
                                r (c/cas-root cfg {:old-key (:root-key d) :new-key (s3/sha256Hex nv) :root-blob nv})]
                            (if (= "ok" (:status r))
                              [(update dn i inc) cf (inc ok)]
                              [dn (inc cf) ok])))
                        [done conflicts oks] live)]
        (recur (nth res 0) (nth res 1) (nth res 2) (inc rounds))))))
