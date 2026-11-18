(ns cohost-media.domain
  "cohost-media's shared domain and its four atoms (cohost-media.core), opened — the first opener creates
   each — by the launcher and by every member; and the same four reached over the network (through the
   gateway) by a member that must not write the disk domain itself.

   Why a member would not: a host whose region cannot swap atomically in place writes a shared domain
   under an exclusive hold, and a lock-free member killed inside a write leaves the hold's count up for
   good — the next hold-taking write then waits for ever (measured: a JVM member killed mid-transcode,
   the domain still after ten minutes). Babashka is not such a writer: its region swaps under a file
   lock, atomic read-modify-write at file-lock prices, so cohost-media's babashka member joins the disk
   domain directly, like every other member (John's order, 2026-11-15); the network reach remains for a
   member that must not write the disk domain itself. The launcher reads the disk and writes through the
   gateway once members run.

   An atom here is a shared atom or its network reach: read-atom and swap-atom! (cohost.pool's) take
   either, and this namespace only names the four and opens them."
  (:require [cohost.pool :as pool]
            [cohost-media.core :as em]))

(defn open-atoms
  "The domain at base and its atoms: {:domain :jobs :library :index :control}."
  [base]
  (let [d (pool/open-domain base)]
    {:domain d
     :jobs (pool/domain-atom d em/jobs-name {:app "cohost-media"})
     :library (pool/domain-atom d em/library-name {:files {}})
     :index (pool/domain-atom d em/index-name {:complete? false})
     :control (pool/domain-atom d em/control-name {:members {}})}))

(defn open-rungs
  "The same atoms reached over the network (open-rung names one): for a member that joins through the
   gateway."
  [open-rung]
  (pool/open-rungs open-rung [[:jobs em/jobs-name] [:library em/library-name]
                              [:index em/index-name] [:control em/control-name]]))

(defn read-atom
  "The value of a (a shared atom: deref; its network reach: its read)."
  [a]
  (pool/read-atom a))

(defn swap-atom!
  "a's value replaced by (apply f value args), atomically against every other writer of it. Answers nil."
  [a f & args]
  (apply pool/swap-atom! a f args))
