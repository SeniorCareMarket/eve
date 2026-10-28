(ns eve.aegis.chat.records
  "The aegis chat's state and records in portable cljc — one keyword-keyed shape, the VS client's
   (src/aegis/chat/broker_client.vs, IMPL-PLAN.md §5 R-B3.2): the chat state is a value in the atom's blocks (eve.net.pages
   reads it out of the broker's pages), every path the spec's own :path-vec (keywords), every record a keyword-keyed map
   that becomes one attested block (eve.net.codec/attest) when it is placed. Ids come from the caller's fresh random
   bytes and the clock, as there.

     The empty chat value, the old state of an atom's first write, is eve.aegis.claims/genesis (the one definition:
     the gate and the VS client read it there too).
     (prepare-post state post username rand-bytes now-ms)
       → {:status \"ok\" :action :path :record r} (an append) · {:status \"ok\" :action :path :key k :value v}
         (add-member) · {:status \"ok\" :action :path :key k} (remove-member) · {:status \"empty\"} · {:status \"denied\"
         :reason}
   post (string keys, the REPL's commands) is {\"action\" …} with: post-message / post-private-message /
   post-announcement {record} (the G.2 record, eve.aegis.chat.g2) · create-poll {question options} · cast-vote {poll_id
   choice} · add-member {member_id public_key} · remove-member {member_id} · open-scratch-space {title} · write-scratch
   {space text} · append-audit-log {audit_action target}."
  (:require [clojure.string :as str]))

(def PUBLIC-MESSAGES [:rooms :public :messages])
(def PUBLIC-POLLS [:rooms :public :polls])
(def PUBLIC-VOTES [:rooms :public :votes])
(def PRIVATE-MEMBERS [:rooms :private :members])
(def PRIVATE-MESSAGES [:rooms :private :messages])
(def ANNOUNCEMENTS [:rooms :announcements :messages])
(def SCRATCH-SPACES [:scratch-spaces :rooms])
(def SCRATCH-POSTS [:scratch-spaces :posts])
(def AUDIT-LOG [:audit-log])

(defn kpath "a spec path as the state's keys: keywords" [p] (mapv (fn [s] (if (keyword? s) s (keyword (str s)))) p))

;; the action → the path it writes and the primitive that owns that path's root
(def ACTIONS
  {"post-message" [PUBLIC-MESSAGES :p/merkle-tail-append]
   "create-poll" [PUBLIC-POLLS :p/merkle-tail-append]
   "cast-vote" [PUBLIC-VOTES :p/merkle-tail-append]
   "add-member" [PRIVATE-MEMBERS :p/key-assoc]
   "remove-member" [PRIVATE-MEMBERS :p/key-dissoc]
   "post-announcement" [ANNOUNCEMENTS :p/merkle-tail-append]
   "post-private-message" [PRIVATE-MESSAGES :p/merkle-tail-append]
   "open-scratch-space" [SCRATCH-SPACES :p/merkle-tail-append]
   "write-scratch" [SCRATCH-POSTS :p/merkle-tail-append]
   "append-audit-log" [AUDIT-LOG :p/merkle-tail-append]})

(defn- -get [m k] (when (map? m) (let [v (get m k)] (if (nil? v) (get m (keyword k)) v))))

(def ^:private B36 "0123456789abcdefghijklmnopqrstuvwxyz")
(defn- rand6 [rand-bytes] (apply str (map (fn [i] (str (nth B36 (mod (nth (vec rand-bytes) i) 36)))) (range 6))))
(defn- prefixed-id [prefix rand-bytes now-ms] (str prefix "-" now-ms "-" (rand6 rand-bytes)))

(defn message-record
  "the G.2 record (eve.aegis.chat.g2/encrypt-message: string keys) as the state holds it — keyword keys, the content
   field the ciphertext, no plaintext field"
  [r]
  (into {} (map (fn [[k v]] [(if (keyword? k) k (keyword (str k))) v]) r)))

(defn wire-record "a state record as eve.aegis.chat.g2 reads it (string keys)" [r]
  (into {} (map (fn [[k v]] [(if (keyword? k) (name k) (str k)) v]) r)))

(defn build-poll-record [author question options rand-bytes now-ms]
  {:id (prefixed-id "p" rand-bytes now-ms) :author author :ts now-ms :question (str question)
   :options (mapv str (if (sequential? options) options []))})

(defn build-vote-record
  "an ANONYMOUS vote {:id :poll :choice :ts} — no author; :poll is the record's :scope-key (spec.vs :records)"
  [poll-id choice rand-bytes now-ms]
  {:id (prefixed-id "v" rand-bytes now-ms) :poll (str poll-id) :choice (str choice) :ts now-ms})

(defn build-audit-record [moderator action target rand-bytes now-ms]
  {:id (prefixed-id "m" rand-bytes now-ms) :moderator moderator :action (str action) :target (str target) :ts now-ms})

(defn build-scratch-space-record [owner title rand-bytes now-ms]
  {:id (prefixed-id "ss" rand-bytes now-ms) :owner owner :ts now-ms :title (str title)})

(defn build-scratch-record
  "a scratch post {:id :space :ts :text} — no author: the guest holds a delegated session (spec.vs branch 6)"
  [space text rand-bytes now-ms]
  {:id (prefixed-id "s" rand-bytes now-ms) :space (str space) :ts now-ms :text (str text)})

(defn member-value "a member's Ed25519 public key as 64 lowercase hex (a 0x prefix dropped), or nil" [pk]
  (let [s (str/lower-case (str (or pk "")))
        s (if (str/starts-with? s "0x") (subs s 2) s)]
    (when (and (= 64 (count s)) (every? (fn [c] (some? (str/index-of "0123456789abcdef" (str c)))) s)) s)))

(defn member-present? [state id] (contains? (get-in state PRIVATE-MEMBERS) id))

(defn- trimmed [x] (str/trim (str (if (nil? x) "" x))))

(defn prepare-post
  "the intended write on the old state (the ns docstring); the state is read, never changed"
  [old post username rand-bytes now-ms]
  (let [action (str (-get post "action"))
        [path _] (get ACTIONS action)
        ok (fn [m] (merge {:status "ok" :action action :path path} m))
        empty {:status "empty" :action action}
        denied (fn [why] {:status "denied" :action action :by "client" :reason why})
        text-of (fn [k] (trimmed (-get post k)))]
    (cond
      (nil? path) (denied (str "unknown action " action))

      (contains? #{"post-message" "post-private-message" "post-announcement"} action)
      (let [r (-get post "record")]
        (if (map? r) (ok {:record (message-record r)}) (denied "a message posts its G.2 record (eve.aegis.chat.g2)")))

      (= action "create-poll")
      (let [q (text-of "question")]
        (if (= q "") empty (ok {:record (build-poll-record username q (-get post "options") rand-bytes now-ms)})))

      (= action "cast-vote")
      (let [poll (text-of "poll_id") choice (text-of "choice")]
        (if (or (= poll "") (= choice "")) empty (ok {:record (build-vote-record poll choice rand-bytes now-ms)})))

      (= action "add-member")
      (let [id (text-of "member_id") v (member-value (-get post "public_key"))]
        (cond (= id "") empty
              (member-present? old id) (denied (str "member-id '" id "' already in private room"))
              (nil? v) (denied (str "add-member '" id "': the value must be the member's Ed25519 public key (64 hex)"))
              :else (ok {:key id :value v})))

      (= action "remove-member")
      (let [id (text-of "member_id")]
        (cond (= id "") empty
              (not (member-present? old id)) (denied (str "member-id '" id "' not in private room"))
              :else (ok {:key id})))

      (= action "open-scratch-space")
      (let [t (text-of "title")]
        (if (= t "") empty (ok {:record (build-scratch-space-record username t rand-bytes now-ms)})))

      (= action "write-scratch")
      (let [space (text-of "space") t (text-of "text")]
        (if (or (= space "") (= t "")) empty (ok {:record (build-scratch-record space t rand-bytes now-ms)})))

      :else ;; append-audit-log
      (let [act (text-of "audit_action")]
        (if (= act "") empty (ok {:record (build-audit-record username act (text-of "target") rand-bytes now-ms)}))))))
