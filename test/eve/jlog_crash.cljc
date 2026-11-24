(ns eve.jlog-crash
  "The kill -9 crash driver (eve-mmap-journal-plan §6.3), one program for the
   JVM, jolt and Node, over a disk domain on the commit log (eve.jlog):

     init   <base> <slots>                  create the domain (its creator) and close it
     writer <base> <tag> <start-ms> <dur-ms> from start-ms for dur-ms: TRY tag i, then
                                            (swap! own conj i) -> ACK own tag i, then
                                            (swap! shared update tag inc) -> ACK shared tag i;
                                            closes cleanly at the end (if not killed)
     age    <base>                          every ACTIVE worker slot's heartbeat made stale:
                                            the harness runs it once every writer is dead,
                                            standing in for HEARTBEAT_TIMEOUT_MS passing
     check  <base>                          reopen (recovering when no process holds the
                                            domain) and print each writer's own vector's
                                            length and shape and its shared count

   An ACK line is written through to stdout before the next step, so a line
   printed is a swap! that returned. scripts/jlog-crash.sh runs it across
   runtimes and kills; scripts/jlog_crash_check.clj judges every acknowledged
   item present exactly once and every count between acknowledged and tried."
  (:require [eve.atom :as a]
            [eve.mem :as mem]
            [eve.process :as proc]
            [eve.region :as region]
            [eve.deftype-proto.data :as d]
            #?@(:cljs [[eve.process-slab :as ps]])))

(defn- emit! [s]
  #?(:cljs (.writeSync (js/require "fs") 1 (str s "\n"))
     :default (do (println s) (flush))))

(defn- open [base slots]
  (if slots
    (a/persistent-atom-domain base :jlog-slots slots)
    (a/persistent-atom-domain base)))

(defn- writer! [base tag start-ms dur-ms]
  (let [dom (open base nil)
        own (a/lookup-or-create-mmap-atom! dom (str "own/" tag) [])
        shared (a/lookup-or-create-mmap-atom! dom "shared" {})]
    (loop [] (when (< (proc/now-ms) start-ms) (proc/sleep-ms! 1) (recur)))
    (let [end (+ start-ms dur-ms)]
      (loop [i 0]
        (when (< (proc/now-ms) end)
          (emit! (str "TRY " tag " " i))
          (swap! own conj i)
          (emit! (str "ACK own " tag " " i))
          (swap! shared update tag (fnil inc 0))
          (emit! (str "ACK shared " tag " " i))
          (recur (inc i)))))
    (a/close-atom-domain! dom)
    (emit! (str "CLOSED " tag))))

(defn- age! [base]
  (let [r (region/open-mmap-region (str base ".root") a/ROOT_BYTES)]
    (dotimes [i d/MAX_WORKERS]
      (let [o (+ d/ROOT_WORKER_REGISTRY_START (* i d/WORKER_SLOT_SIZE))]
        (when (== d/WORKER_STATUS_ACTIVE (mem/-load-i32 r (+ o d/OFFSET_WS_STATUS)))
          (mem/-store-i32! r (+ o d/OFFSET_WS_HEARTBEAT_LO) 1)
          (mem/-store-i32! r (+ o d/OFFSET_WS_HEARTBEAT_HI) 0))))
    (emit! "AGED")))

(defn- check! [base]
  (let [dom (open base nil)
        shared @(a/lookup-or-create-mmap-atom! dom "shared" {})]
    (doseq [tag (sort (keys shared))]
      (let [v @(a/lookup-or-create-mmap-atom! dom (str "own/" tag) [])]
        (emit! (str "OWN " tag " " (count v) " " (if (= (vec v) (vec (range (count v)))) "exact" "BROKEN")))
        (emit! (str "SHARED " tag " " (get shared tag)))))
    (a/close-atom-domain! dom)
    (emit! "CHECKED")))

(defn -main [& [mode base x y z]]
  (let [num #?(:cljs #(js/parseInt % 10) :default #(Long/parseLong %))]
    (case mode
      "init" (do (a/close-atom-domain! (open base (num x))) (emit! "INIT"))
      "writer" (writer! base x (num y) (num z))
      "age" (age! base)
      "check" (check! base))))

#?(:cljs
   (defn main [& args]
     (let [addon (or (unchecked-get (.-env js/process) "EVE_MMAP_ADDON")
                     (str (.cwd js/process) "/build/Release/mmap_cas.node"))]
       (region/load-native-addon! (js/require addon))
       (-> (ps/init! :force true)
           (.then (fn [_] (apply -main args)))
           (.catch (fn [e] (emit! (str "FATAL " e)) (js/process.exit 1)))))))
