(ns eve.aegis.chat.repl
  "The aegis chat from a REPL: the commands a terminal chat client would have, as functions, on any eve host with
   the http feature — in the same rooms as the web page, reading and writing each other's messages (the eve-chat
   capstone, eve/capstone/eve-chat/README.md).

     (require '[eve.aegis.chat.repl :as chat])
     (chat/join! \"http://127.0.0.1:5173/#room=…&user=alice&key=…\")   ; the page's own invite link
     (chat/say \"hello from the REPL\")      (chat/pm \"…\")   (chat/announce \"…\")
     (chat/msgs)  (chat/msgs 50)  (chat/pms)  (chat/announcements)  (chat/new!)  (chat/tail! 60)
     (chat/poll! \"Lunch?\" [\"pizza\" \"sushi\"])  (chat/polls)  (chat/vote! 1 \"pizza\")
     (chat/members)  (chat/add-member! \"m-bob\" \"<64-hex public key>\")  (chat/remove-member! \"m-bob\")
     (chat/scratch! \"notes\")  (chat/scratches)  (chat/scratch-post! \"ss-…\" \"a guest's note\")
     (chat/audit! \"ban\" \"mallory\")  (chat/audit-log)  (chat/whoami)  (chat/status)  (chat/help)
   Every command prints what it did and answers a small value (nil, a count, or the action's result map). Times are
   UTC. One session per REPL, held in an atom."
  (:refer-clojure :exclude [long])
  (:require [clojure.string :as str]
            [eve.aegis.chat.client :as c]
            [eve.host :refer [long]]
            [eve.process :as process]))

(defonce ^:private state (atom {:session nil :seen #{}}))

(defn- s! [] (or (:session @state) (throw (ex-info "chat: not joined — (chat/join! \"<the page's invite link>\")" {}))))

(defn- two [n] (if (< n 10) (str "0" n) (str n)))
(defn- hhmm "UTC hh:mm of epoch ms" [ms]
  (if (number? ms) (let [m (quot (mod (quot (long ms) 1000) 86400) 60)] (str (two (quot m 60)) ":" (two (mod m 60)))) "--:--"))

(defn- line [tag {:keys [ts author text]}] (str (when tag (str "[" tag "] ")) (hhmm ts) " " author ": " text))

(defn- report [r]
  (if (= "ok" (:status r))
    (println (str "  ✓ " (:action r) " (" (:branch r) ", " (:auth r) ")"))
    (println (str "  ✗ " (or (:action r) "") " " (:status r) (when (:reason r) (str ": " (:reason r))))))
  r)

;; ── joining

(defn join!
  "join the room of a web invite link (http(s)://<page>/#room=…&user=…&key=…), or a cfg map (eve.aegis.chat.client)"
  [link-or-cfg]
  (let [s (c/join link-or-cfg)]
    (reset! state {:session s :seen #{}})
    (println (str "joined " (:atom-id s) " at " (:endpoint s) " as " (or (:user s) "a guest")
                  (when-not (:room s) " (no room secret: messages stay sealed)")))
    (:user s)))

(defn whoami [] (let [s (s!)] (println (str (:user s) " · " (:pk s) " · " (:atom-id s))) (:user s)))

;; ── messages

(defn say "post to the public room" [text] (report (c/say! (s!) :public text)))
(defn pm "post to the private room (its members only)" [text] (report (c/say! (s!) :private text)))
(defn announce "post an announcement (credential holders only)" [text] (report (c/say! (s!) :announcements text)))

(defn- show [room tag n]
  (let [ms (get (c/view (s!)) room) ms (if n (vec (take-last n ms)) ms)]
    (doseq [m ms] (println (line tag m)))
    (swap! state update :seen into (map :id ms))
    (count ms)))

(defn msgs "the public room's messages (the last n)" ([] (msgs 20)) ([n] (show :public nil n)))
(defn pms "the private room's messages" ([] (pms 20)) ([n] (show :private "private" n)))
(defn announcements "the announcements" ([] (announcements 20)) ([n] (show :announcements "announce" n)))

(defn new!
  "print every message in the three rooms not shown since the last call; the number printed"
  []
  (let [v (c/view (s!))
        ;; a vector, realized before :seen changes: clojerl's lazy `for` re-ran its :when on the second walk (the count
        ;; came out 0 after the swap!, eve-chat's e2e transcript, 2026-10-23)
        fresh (vec (for [[room tag] [[:public nil] [:private "private"] [:announcements "announce"]]
                         m (get v room) :when (not (contains? (:seen @state) (:id m)))]
                     [tag m]))]
    (doseq [[tag m] (sort-by (fn [[_ m]] (:ts m)) fresh)] (println (line tag m)))
    (swap! state update :seen into (map (fn [[_ m]] (:id m)) fresh))
    (count fresh)))

(defn tail!
  "poll the room every 2 s for `seconds`, printing new messages as they arrive (blocks the REPL meanwhile)"
  ([] (tail! 60))
  ([seconds]
   (let [end (+ (process/now-ms) (* 1000 seconds))]
     (loop [n 0]
       (let [n (+ n (new!))]
         (if (< (process/now-ms) end) (do (process/sleep-ms! 2000) (recur n)) n))))))

;; ── polls

(defn polls "the polls with their tallies, numbered" []
  (let [ps (:polls (c/view (s!)))]
    (doseq [[i p] (map-indexed vector ps)]
      (println (str (inc i) ". " (:question p) "  [" (:id p) " by " (:author p) "]"))
      (doseq [o (:options p)] (println (str "     " o ": " (get (:tally p) o 0)))))
    (count ps)))

(defn poll! "create a poll" [question options] (report (c/post! (s!) {"action" "create-poll" "question" question "options" (vec options)})))

(defn vote!
  "vote in a poll (its number in (polls), or its id): anonymous — a blind-signed ballot or a ring signature, as the
   group declares"
  [poll choice]
  (let [s (s!)
        id (if (number? poll) (:id (nth (:polls (c/view s)) (dec poll))) poll)]
    (report (c/post! s {"action" "cast-vote" "poll_id" id "choice" (str choice)}))))

;; ── the private room's members, scratch spaces, the audit log

(defn members [] (let [m (:members (c/view (s!)))] (doseq [[id pk] (sort m)] (println (str id "  " pk))) (count m)))
(defn add-member! [id public-key] (report (c/post! (s!) {"action" "add-member" "member_id" id "public_key" public-key})))
(defn remove-member! [id] (report (c/post! (s!) {"action" "remove-member" "member_id" id})))

(defn scratches "the scratch spaces and their posts" []
  (let [{:keys [spaces posts]} (:scratch (c/view (s!)))]
    (doseq [sp spaces]
      (println (str (get sp "title") "  [" (get sp "id") " by " (get sp "owner") "]"))
      (doseq [p posts :when (= (get sp "id") (get p "space"))] (println (str "     " (hhmm (get p "ts")) " guest: " (get p "text")))))
    (count spaces)))
(defn scratch! "open a scratch space" [title] (report (c/post! (s!) {"action" "open-scratch-space" "title" title})))
(defn scratch-post! "a guest's post into a scratch space, under the delegated session of the invite link" [space text]
  (report (c/post! (s!) {"action" "write-scratch" "space" space "text" text})))

(defn audit-log [] (let [es (:audit (c/view (s!)))]
                     (doseq [e es] (println (str (hhmm (get e "ts")) " " (get e "moderator") " " (get e "action") " " (get e "target"))))
                     (count es)))
(defn audit! "a moderator's audit-log entry" [action target]
  (report (c/post! (s!) {"action" "append-audit-log" "audit_action" action "target" target})))

;; ── status

(defn status [] (let [s (s!) v (c/view s)]
                  (println (str (:atom-id s) " · " (:commits v) " commits · " (count (:public v)) " messages · "
                                (count (:private v)) " private · " (count (:polls v)) " polls · " (count (:members v)) " members"))
                  (:commits v)))

(def ^:private HELP
  "  (join! link)  (whoami)  (status)
  (say t)  (pm t)  (announce t)
  (msgs [n])  (pms [n])  (announcements [n])  (new!)  (tail! [seconds])
  (poll! question options)  (polls)  (vote! n-or-id choice)
  (members)  (add-member! id public-key)  (remove-member! id)
  (scratch! title)  (scratches)  (scratch-post! space text)
  (audit! action target)  (audit-log)")

(defn help [] (println HELP) nil)
