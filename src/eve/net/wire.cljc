(ns eve.net.wire
  "The broker's wire (eve/doc/network-atoms/WIRE.md; production's b80bb555f:old/src/eve/coordinator.cljs and
   worker.cljs, as vs.net.coordinator speaks it) as sans-IO STEPS (eve.net.run): each function answers a step whose
   value is the answer map; eve.net.run/run carries it over a host's platform. Small JSON in and out (eve.net.json),
   blobs as base64 (eve.net.bytes). 409 is an answer ({:status \"conflict\" …}), 403 an answer ({:status \"denied\"
   …}); any other non-2xx where an answer is required throws. Config: {:endpoint :domain-id :atom-id :api-key}."
  (:require [eve.net.run :as r]
            [eve.net.json :as json]
            [eve.net.bytes :as b]))

(defn- atom-url [cfg path] (str (:endpoint cfg) "/v1/domains/" (:domain-id cfg) "/atoms/" (:atom-id cfg) path))
(defn- domain-url [cfg path] (str (:endpoint cfg) "/v1/domains/" (:domain-id cfg) path))
(defn- headers [cfg] {"Authorization" (str "Bearer " (or (:api-key cfg) "")) "Content-Type" "application/json"})

(defn- post [cfg url body] {:method "POST" :url url :headers (headers cfg) :body (json/write-str body)})

(defn- body-of [resp]
  (let [t (:body resp)]
    (when (and (string? t) (pos? (count t))) (json/read-str-or-nil t))))

(defn- ok! [resp method url]
  (let [s (:status resp)]
    (when (contains? resp :error) (throw (ex-info (str "fetch " method " " url " failed: " (:error resp)) {:error (:error resp)})))
    (when (or (nil? s) (< s 200) (>= s 300))
      (throw (ex-info (str "HTTP " s " " method " " url) {:status s :body (:body resp)})))
    resp))

(defn- blob [x] (when (string? x) (b/from-b64 x)))

(defn create
  "POST …/create → {:status \"ok\" :epoch n}"
  [cfg]
  (let [url (atom-url cfg "/create")]
    (r/request (post cfg url {"atom_id" (:atom-id cfg)})
               (fn [resp] (ok! resp "POST" url)
                 (r/done {:status (if (= 200 (:status resp)) "ok" "error") :epoch (or (get (body-of resp) "epoch") 0)})))))

(defn deref-atom
  "POST …/deref → {:root-key :root-blob (bytes) :epoch :lease-id :lease-ttl :root-count :graft-sha}"
  [cfg]
  (let [url (atom-url cfg "/deref")]
    (r/request (post cfg url {"atom_id" (:atom-id cfg)})
               (fn [resp]
                 (ok! resp "POST" url)
                 (let [bd (body-of resp)]
                   (r/done {:root-key (get bd "root_key") :root-blob (blob (get bd "root_blob"))
                            :epoch (or (get bd "epoch") 0) :lease-id (get bd "lease_id") :lease-ttl (get bd "lease_ttl")
                            :root-count (or (get bd "root_count") 0) :graft-sha (get bd "graft_sha")}))))))

(defn cas-root
  "POST …/cas-root → {:status \"ok\" :epoch} · {:status \"conflict\" :current-key :current-epoch :root-blob :graft-sha} ·
   {:status \"denied\" :reason :detail :by}; req {:old-key :new-key :root-blob (bytes) :root-count :retired-keys
   :graft-sha}"
  [cfg req]
  (let [url (atom-url cfg "/cas-root")
        body (cond-> {"atom_id" (:atom-id cfg) "old_key" (:old-key req) "new_key" (:new-key req)
                      "root_blob" (some-> (:root-blob req) b/to-b64) "root_count" (or (:root-count req) 0)
                      "retired_keys" (vec (:retired-keys req))}
               (:graft-sha req) (assoc "graft_sha" (:graft-sha req)))]
    (r/request (post cfg url body)
               (fn [resp]
                 (when (contains? resp :error) (ok! resp "POST" url))
                 (let [s (:status resp) bd (body-of resp)]
                   (r/done
                    (case s
                      200 {:status "ok" :epoch (or (get bd "epoch") 0)}
                      409 {:status "conflict" :current-key (get bd "current_key") :current-epoch (get bd "current_epoch")
                           :root-blob (blob (get bd "root_blob")) :graft-sha (get bd "graft_sha")}
                      403 {:status "denied" :reason (or (get bd "reason") "denied") :detail (get bd "detail") :by (get bd "by")}
                      (throw (ex-info (str "CAS root failed: HTTP " s) {:status s :body bd})))))))))

(defn release-reader
  "POST …/release-reader (best-effort: any answer is {:status \"ok\"})"
  [cfg lease-id]
  (r/request (post cfg (domain-url cfg "/release-reader") {"lease_id" lease-id}) (fn [_] (r/done {:status "ok"}))))

(defn renew-lease
  "POST …/renew-lease → {:status \"ok\" :lease-ttl} or {:status \"not-found\"}"
  [cfg lease-id]
  (r/request (post cfg (domain-url cfg "/renew-lease") {"lease_id" lease-id})
             (fn [resp] (r/done (if (= 200 (:status resp))
                                  {:status "ok" :lease-ttl (get (body-of resp) "lease_ttl")}
                                  {:status "not-found"})))))

(def PRESIGN-BATCH 100)

(defn presign
  "POST …/presign {keys mode ttl}, PRESIGN-BATCH keys at a time → {sha url} (B-creds, WIRE.md §8); mode read | write |
   head | delete"
  ([cfg mode shas] (presign cfg mode shas 300))
  ([cfg mode shas ttl]
   (let [url (domain-url cfg "/presign")]
     (letfn [(batch [left acc]
               (if (empty? left)
                 (r/done acc)
                 (let [ks (vec (take PRESIGN-BATCH left))]
                   (r/request (post cfg url {"keys" ks "mode" (name mode) "ttl" ttl})
                              (fn [resp]
                                (ok! resp "POST" url)
                                (batch (drop PRESIGN-BATCH left) (merge acc (select-keys (get (body-of resp) "urls") ks))))))))]
       (batch (vec (distinct shas)) {})))))
