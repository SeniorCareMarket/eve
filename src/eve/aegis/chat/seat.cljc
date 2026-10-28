(ns eve.aegis.chat.seat
  "One member of a chat room driven over standard input — the swarm's seat (eve/capstone/eve-swarm): eve's own client
   (eve.aegis.chat.client) on whatever dialect runs this, answering one JSON command line with one result line.
   Written once against eve's host interface (no reader conditionals): the lines are eve.process/read-line's, the codec
   eve.net.json's, an error eve.thread-host/attempt's, the clock eve.process/now-ms.

   The first line joins: [\"join\", cfg] — cfg {endpoint domain atom user room spec_text, and the member's own key: seed
   (64 hex) for a member, session {secret issued} for a guest}. The seat is given its own key only; the broker refuses a
   claim signed by any other key (claim/signature) or for a principal its rosters lack (claim/unknown-principal).
   Then, one per line:

     [\"say\", room, text]               room: public · private · announcements
     [\"poll\", question, [option …]]    create-poll
     [\"vote\", question, choice]        cast-vote in the poll with that question (a ring signature or a ballot, as
                                       the spec's group declares)
     [\"scratch\", title]                open-scratch-space
     [\"scratch-post\", title, text]     write-scratch into the space with that title (a guest's session)
     [\"audit\", action, target]         append-audit-log
     [\"add-member\"] [\"remove-member\"]  the seat itself in or out of the private room's members
     [\"view\"]                          the room as the seat reads it (below)
     [\"quit\"]

   Each answer is one line `SEAT {…}`: {ok status reason ms phases} for a write (ms: the whole write — the deref, the
   claim, the cas-root, a lost race's retries; phases: eve.aegis.chat.client's phase clock, ms by phase), {ok ms view} for a view — view {public private announcements: [[author
   text] …], polls: [[question {choice n}] …], members: [id …], spaces: [title …], scratch: [text …], audit: n,
   commits: n}."
  (:refer-clojure :exclude [read-line])
  (:require [clojure.string :as str]
            [eve.aegis.chat.client :as c]
            [eve.aegis.chat.g2 :as g2]
            [eve.net.json :as json]
            [eve.process :as process]
            [eve.thread-host :as th]))

(defn- session-of [cfg]
  (c/session (cond-> {:endpoint (str/replace (str (get cfg "endpoint")) #"/+$" "") :domain (get cfg "domain")
                      :atom-id (get cfg "atom") :user (get cfg "user") :spec-text (get cfg "spec_text")
                      :room-secret (g2/parse-room-secret (get cfg "room"))}
               true (assoc :phases (atom {}))
               (get cfg "seed") (assoc :seed (get cfg "seed"))
               (get cfg "session") (assoc :session {:secret (get-in cfg ["session" "secret"])
                                                    :issuance (get-in cfg ["session" "issued"])}))))

(defn- texts [ms] (mapv (fn [m] [(:author m) (:text m)]) ms))

(defn summary
  "the room as the seat reads it, as plain data (the ns docstring's view)"
  [v]
  {"public" (texts (:public v)) "private" (texts (:private v)) "announcements" (texts (:announcements v))
   "polls" (mapv (fn [p] [(:question p) (into {} (map (fn [[k n]] [(str k) n]) (:tally p)))]) (:polls v))
   "members" (vec (sort (map str (keys (:members v)))))
   "spaces" (mapv (fn [s] (str (:title s))) (get-in v [:scratch :spaces]))
   "scratch" (mapv (fn [p] (str (:text p))) (get-in v [:scratch :posts]))
   "audit" (count (:audit v)) "commits" (:commits v)})

(defn- by [k want xs] (first (filter (fn [x] (= want (str (get x k)))) xs)))

(defn- write [r]
  (cond-> {"ok" (= "ok" (:status r)) "status" (:status r) "reason" (some-> (:reason r) str)}
    (some? (:detail r)) (assoc "detail" (str (:detail r)))))

(defn run
  "one command (a vector, its head a string) on session s → the answer map (without its ms)"
  [s [op & args]]
  (case op
    "say" (write (c/say! s (keyword (first args)) (second args)))
    "poll" (write (c/post! s {"action" "create-poll" "question" (first args) "options" (vec (second args))}))
    "vote" (let [p (by :question (first args) (:polls (c/view s)))]
             (if p
               (write (c/post! s {"action" "cast-vote" "poll_id" (:id p) "choice" (str (second args))}))
               {"ok" false "status" "error" "reason" (str "no poll asks " (pr-str (first args)))}))
    "scratch" (write (c/post! s {"action" "open-scratch-space" "title" (first args)}))
    "scratch-post" (let [sp (by :title (first args) (get-in (c/view s) [:scratch :spaces]))]
                     (if sp
                       (write (c/post! s {"action" "write-scratch" "space" (:id sp) "text" (second args)}))
                       {"ok" false "status" "error" "reason" (str "no scratch space is titled " (pr-str (first args)))}))
    "audit" (write (c/post! s {"action" "append-audit-log" "audit_action" (first args) "target" (second args)}))
    "add-member" (write (c/post! s {"action" "add-member" "member_id" (:user s) "public_key" (:pk s)}))
    "remove-member" (write (c/post! s {"action" "remove-member" "member_id" (:user s)}))
    "view" {"ok" true "view" (summary (c/view s))}
    {"ok" false "status" "error" "reason" (str "unknown command " (pr-str op))}))

(defn- answer! [m] (println (str "SEAT " (json/write-str m))))

(defn- timed [s f]
  (reset! (:phases s) {})
  (let [t0 (process/now-ms)
        [ok v] (th/attempt f)
        ms (- (process/now-ms) t0)
        ph @(:phases s)]
    (cond-> (assoc (if ok v {"ok" false "status" "threw" "reason" (str v)}) "ms" ms)
      (seq ph) (assoc "phases" (into {} (map (fn [[k x]] [(name k) x]) ph))))))

(defn step
  "one command (a line's JSON, read) on session s — nil before the join → [the session after it, the answer map]; a
   seat that is not a process (capstone/eve-swarm/scittle's page) calls this a command at a time"
  [s [op arg :as cmd]]
  (cond
    (= "quit" op) [s {"ok" true "ms" 0}]
    (= "join" op) (let [t0 (process/now-ms)
                        [ok v] (th/attempt (fn [] (session-of arg)))]
                    [(when ok v) (if ok
                                   {"ok" true "pk" (:pk v) "user" (:user v) "ms" (- (process/now-ms) t0)}
                                   {"ok" false "status" "threw" "reason" (str v)})])
    (nil? s) [s {"ok" false "status" "error" "reason" "join first"}]
    :else [s (timed s (fn [] (run s cmd)))]))

(defn -main
  "the seat's loop: the join line, then one command a line until [\"quit\"] or the end of the input"
  [& _]
  (loop [s nil]
    (let [line (process/read-line)]
      (when (some? line)
        (if (str/blank? line)
          (recur s)
          (let [cmd (json/read-str line)
                [s a] (step s cmd)]
            (answer! a)
            (when-not (= "quit" (first cmd)) (recur s))))))))
