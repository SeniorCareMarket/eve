(ns cohost-media.domain
  "cohost-media's disk domain and its four atoms (cohost-media.core), opened — the first opener creates each — by the
   launcher and by every member; and the same four reached over the network rung (eve.thread.net, through the gateway)
   by a member that must not write the disk domain itself.

   Why a member would not: a host whose region cannot swap atomically in place writes a shared domain under the
   exclusive hold (eve.atom: ROOT_HOLD, and ROOT_INFLIGHT, the count of the lock-free hosts' writes in flight it waits
   to drain). A lock-free member killed inside a write leaves that count up for good, and the next hold-taking write
   waits for ever — every other writer then waits on the hold (measured: a JVM member killed mid-transcode,
   ROOT_HOLD 3, ROOT_INFLIGHT 2, the domain still after ten minutes).
   Babashka is such a writer, not a non-writer: since the Nov 6 fix its region swaps under a FileChannel lock (the
   jvm-nio-lock adapter), which is atomic read-modify-write at file-lock prices. cohost-media's babashka member
   therefore joins the disk domain directly, like every other member (John's order, 2026-11-15); the rung remains
   for a member that must not write the disk domain itself. The launcher reads the disk and writes through the
   gateway once members run.

   An atom here is an eve atom or a rung (eve.thread.rung): read-atom and swap-atom! take either."
  (:refer-clojure :exclude [swap!])
  (:require [eve.host :refer [swap!]]
            [eve.atom :as ea]
            [eve.thread.rung :as rung]
            [cohost-media.core :as em]))

(defn open-atoms
  "The domain at base and its atoms: {:domain :jobs :library :index :control}."
  [base]
  (let [d (ea/persistent-atom-domain base)]
    {:domain d
     :jobs (ea/lookup-or-create-mmap-atom! d em/jobs-name {:app "cohost-media"})
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
