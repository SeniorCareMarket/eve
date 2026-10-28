(ns eve-media.state
  "This process's member context, set once when it joins: the domain's atoms, its handle on the job atom, its rid and
   the directories. A process-local host atom (a member's own notes, never shared state).")

(defonce ctx (atom {}))

(defn library [] (:library @ctx))
(defn index [] (:index @ctx))
(defn control [] (:control @ctx))
