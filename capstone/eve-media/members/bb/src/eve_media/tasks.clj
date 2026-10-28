(ns eve-media.tasks
  "The babashka member's jobs: the index (eve-media.work/index!), and the duplicate grouping when it is the coordinator
   (a run with no JVM).

   It joins over the network rung (eve.thread.net, through the gateway the launcher names), not the disk: babashka
   writes a shared disk domain under the exclusive hold, which a lock-free member killed mid-write leaves waiting for
   ever (eve-media.domain's namespace doc). It reads and writes the same atoms, as network atoms."
  (:require [eve.net.platform :as platform]
            [eve.process :as proc]
            [eve.thread :as t]
            [eve.thread.net :as tn]
            [eve-media.domain :as domain]
            [eve-media.work :as work]))

(defn- open-net
  "The domain's atoms as network rungs at gateway (the gateway may still be starting: retried for 90 s)."
  [gateway api-key]
  (let [cfg {:endpoint gateway :domain-id "eve-media" :api-key api-key}
        p (platform/platform)
        t0 (proc/now-ms)
        open (fn [nm] (tn/rung cfg nm p))]
    (loop []
      (let [r (try (let [rs (domain/open-rungs open)]
                     ;; one read through the gateway, so a gateway still starting is waited for here
                     (when-not (map? (domain/read-atom (:control rs))) (throw (ex-info "no control atom" {})))
                     rs)
                   (catch Throwable e e))]
        (cond (map? r) r
              (> (- (proc/now-ms) t0) 90000) (throw r)
              :else (do (proc/sleep-ms! 500) (recur)))))))

(def caps #{:index})
(def pools {:index 1})
;; the lease horizon: a network swap of the metadata costs this member hundreds of ms (its first, a cold read of every
;; block, seconds), where a disk member's costs µs — its lease is the longer for it
(def options {:open open-net :ttl 30000 :poll-ms 2000})

(defn attempt [f] (try [true (f)] (catch Throwable e [false e])))
(defn exit! [code] (System/exit code))
(defn init! [_ctx] nil)

(t/deftask index [job] (work/index! job))
(t/deftask group [job] (work/group! job))
