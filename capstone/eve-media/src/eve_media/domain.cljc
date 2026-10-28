(ns eve-media.domain
  "eve-media's disk domain and its four atoms (eve-media.core), opened — the first opener creates each — by the
   launcher and by every member; and the same four reached over the network rung (eve.thread.net, through the gateway)
   by a member that must not write the disk domain itself.

   Why a member would not: babashka's regions have no atomic read-modify-write, so it writes a shared domain under the
   exclusive hold (eve.atom: ROOT_HOLD, and ROOT_INFLIGHT, the count of the lock-free hosts' writes in flight it waits
   to drain). A lock-free member killed inside a write leaves that count up for good, and the next babashka write holds
   the domain while it waits for ever — every other writer then waits on the hold (measured: a JVM member killed
   mid-transcode, ROOT_HOLD 3, ROOT_INFLIGHT 2, the domain still after ten minutes; doc/release-0.9 RELEASE-CHECKLIST
   9.14). The plan already reads babashka so on canonical eve (P§11 #4: it launches, reads, or uses the network rung),
   and so does eve-media: its babashka member joins over the network rung, and the launcher reads the disk and writes
   through the gateway once members run.

   An atom here is an eve atom or a rung (eve.thread.rung): read-atom and swap-atom! take either."
  (:refer-clojure :exclude [swap!])
  (:require [eve.host :refer [swap!]]
            [eve.atom :as ea]
            [eve.thread.rung :as rung]
            [eve-media.core :as em]))

(defn open-atoms
  "The domain at base and its atoms: {:domain :jobs :library :index :control}."
  [base]
  (let [d (ea/persistent-atom-domain base)]
    {:domain d
     :jobs (ea/lookup-or-create-mmap-atom! d em/jobs-name {:app "eve-media"})
     :library (ea/lookup-or-create-mmap-atom! d em/library-name {:files {}})
     :index (ea/lookup-or-create-mmap-atom! d em/index-name {:complete? false})
     :control (ea/lookup-or-create-mmap-atom! d em/control-name {:members {}})}))

(defn open-rungs
  "The same atoms as rungs made by (open-rung name): the network rung's, for a member that joins over it."
  [open-rung]
  {:jobs (open-rung em/jobs-name) :library (open-rung em/library-name) :index (open-rung em/index-name)
   :control (open-rung em/control-name)})

(defn- rung? [a] (satisfies? rung/IRung a))

(defn read-atom "The value of a (an eve atom: deref, materialized; a rung: its value)." [a]
  (if (rung? a) (rung/value a) @a))

(defn swap-atom!
  "a's value replaced by (apply f value args), atomically against every other writer of it. Answers nil."
  [a f & args]
  (if (rung? a)
    (rung/-swap! a (fn [v] (apply f v args)))
    (swap! a (fn [v] (apply f v args))))
  nil)

(defn meta-value
  "The job atom's metadata — eve.thread's registry, pools, descriptors, leases — read from its meta-atom (materialized)."
  [{:keys [jobs]}]
  (if (rung? jobs) (rung/value (rung/-meta-rung jobs {})) @(ea/meta-atom jobs)))
