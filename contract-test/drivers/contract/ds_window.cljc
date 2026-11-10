(ns contract.ds-window
  "fs3run8 windowed flagship worker — John's run shape (Nov 7):
   RENDEZVOUS release + a fixed 60-SECOND contended window + CROSS-HOST
   dependent counter loops + per-host SPEED rows, all on ONE DataScript
   database in ONE shared Eve disk atom.

   Modes (argv after the mode word):
     seed     <dir>          after ds-flagship init: transact the five
                             counter rows (entities 6001..6005, :count 0)
     go       <dir>          transact the GO datom (entity 7000, :go true)
                             — the release every window worker waits for
     window   <dir> <w>      open the atom, print READY, wait for the GO
                             datom, then for 60 s from release:
                               phase A (fixed work): worker w's disjoint
                                 batch (10 swap!s of 10 entities = 200
                                 datoms) + its marks (20 datoms)
                               phase B (until the deadline), rotating:
                                 dependent: query counter c's value (a
                                   value the OTHER runtimes are moving),
                                   then transact +1 inside a swap!
                                 pure query against the live DB
                                 pure write of a fresh unique entity
                             Counters round-robin across ALL FIVE counter
                             rows. Prints one RESULT EDN line: window
                             start/stop (epoch ms), per-counter successful
                             increment counts, per-activity counts and
                             total ms (writes / queries / dependent).
     counters <dir>          print the five counter values + datom count

   Verdict arithmetic (orchestrator): each counter's final value must
   equal the sum of all five workers' reported successful increments
   on it — a shortfall is a lost update, reported with numbers."
  (:refer-clojure :exclude [swap!])
  (:require
   [eve.host :refer [swap!]]
   [eve.atom :as atom]
   [eve.core :as e]
   [datascript.core :as d]))

(defmacro attempt
  "Run body; on any throw, answer the string ':throws <err>' (the
   ds-flagship / contend.cljc convention)."
  [& body]
  `(try ~@body
     (catch #?(:cljs :default :lpy python/Exception :jank jank.runtime.object_ref :default Exception) t#
       (str :throws " " t#))))

(def ^:private atom-name "contract/ds-flagship")
(def ^:private window-ms 60000)
(def ^:private counter-ids [6001 6002 6003 6004 6005])
(def ^:private go-id 7000)

(defn- now-ms []
  #?(:cljs (js/Date.now)
     :default (System/currentTimeMillis)))

(defn- spin-ms [ms]
  #?(:cljs (let [end (+ (js/Date.now) ms)]
             (while (< (js/Date.now) end)))
     :default (Thread/sleep ms)))

(defn- base-tx []
  (mapv (fn [i] {:db/id i :name (str "base-" i) :age (+ 20 i)})
        (range 1 11)))

(defn- worker-tx [w i]
  {:db/id (+ 1000 (* w 1000) i) :name (str "w" w "-" i) :age i})

(defn- worker-batch [w b]
  (mapv (fn [i] (worker-tx w i)) (range (* b 10) (* (inc b) 10))))

(defn- mark-tx [w]
  (mapv (fn [e] {:db/id e (keyword (str "mark/w" w)) true})
        (range 5001 5021)))

(defn- open-atom [dir]
  (let [domain (atom/persistent-atom-domain dir)
        a (atom/lookup-or-create-mmap-atom! domain atom-name nil)]
    ;; the DB is constructed inside a swap so it allocates in the atom's
    ;; own domain (Eve's domain-construction contract, PORTING-DS D1)
    (when (nil? @a)
      (swap! a (fn [_] (d/empty-db))))
    a))

(defn- datom-count [db]
  (attempt (e/count (d/datoms db :eavt))))

(defn- go? [a]
  (boolean (:go (d/entity @a go-id))))

(defn- window! [dir w]
  (let [a (open-atom dir)]
    (println "READY" w (now-ms))
    (attempt (flush))
    ;; rendezvous: wait for the GO datom, up to 15 minutes
    (loop [waited 0]
      (when (and (not (go? a)) (< waited 900000))
        (spin-ms 250)
        (recur (+ waited 250))))
    (if-not (go? a)
      (println "RESULT" (pr-str {:w w :fatal :no-go}))
      (let [start (now-ms)
            deadline (+ start window-ms)
            fa0 (now-ms)
            _ (doseq [b (range 10)]
                (swap! a (fn [db] (d/db-with db (worker-batch w b)))))
            _ (swap! a (fn [db] (d/db-with db (mark-tx w))))
            fa1 (now-ms)
            state
            ;; NB: the increments map is bound as `incs`, NEVER `inc` —
            ;; binding it `inc` shadows clojure.core/inc throughout the
            ;; loop body AND its closures, so (inc i) called the map
            ;; (a lookup -> nil) and the next (mod i 3) threw the NPE
            ;; that killed every fs3run8 window worker at its second
            ;; phase-B iteration (stack trace: proof-diag-trace.log).
            (loop [i 0 dep-idx 0 incs {} inc-fail 0
                   dep-n 0 dep-ms 0 q-n 0 q-ms 0 x-n 0 x-ms 0]
              (if (>= (now-ms) deadline)
                {:inc incs :inc-fail inc-fail :dep-n dep-n :dep-ms dep-ms
                 :q-n q-n :q-ms q-ms :x-n x-n :x-ms x-ms}
                (case (mod i 3)
                  ;; dependent: query the counter (another runtime may
                  ;; have just moved it), then transact +1 in a swap!
                  0 (let [cid (nth counter-ids (mod dep-idx 5))
                          t0 (now-ms)
                          _seen (:count (d/entity @a cid))
                          r (attempt
                             (swap! a (fn [db]
                                        (d/db-with db [{:db/id cid
                                                        :count (inc (:count (d/entity db cid)))}]))))
                          t1 (now-ms)]
                      (if (string? r)
                        (recur (inc i) (inc dep-idx) incs (inc inc-fail)
                               (inc dep-n) (+ dep-ms (- t1 t0)) q-n q-ms x-n x-ms)
                        (recur (inc i) (inc dep-idx) (update incs cid (fnil inc 0)) inc-fail
                               (inc dep-n) (+ dep-ms (- t1 t0)) q-n q-ms x-n x-ms)))
                  ;; pure query against the live DB
                  1 (let [t0 (now-ms)
                          _r (attempt (count (d/q '[:find [?n ...]
                                                    :where [?e :name ?n]] @a)))
                          t1 (now-ms)]
                      (recur (inc i) dep-idx incs inc-fail
                             dep-n dep-ms (inc q-n) (+ q-ms (- t1 t0)) x-n x-ms))
                  ;; pure write: one fresh unique entity per swap!
                  2 (let [t0 (now-ms)
                          id (+ 20000 (* w 10000) x-n)
                          r (attempt
                             (swap! a (fn [db]
                                        (d/db-with db [{:db/id id
                                                        :name (str "x" w "-" x-n)
                                                        :age (mod x-n 90)}]))))
                          t1 (now-ms)]
                      (if (string? r)
                        (recur (inc i) dep-idx incs inc-fail
                               dep-n dep-ms q-n q-ms x-n x-ms)
                        (recur (inc i) dep-idx incs inc-fail
                               dep-n dep-ms q-n q-ms (inc x-n) (+ x-ms (- t1 t0))))))))]
        (println "RESULT" (pr-str (merge {:w w
                                          :t-start start
                                          :t-stop (now-ms)
                                          :fixed-ms (- fa1 fa0)
                                          :datoms-seen (datom-count @a)}
                                         state)))
        (attempt (flush))))))

(defn -main [& [mode dir w]]
  (try
    (case mode
      "seed"
      (let [a (open-atom dir)]
        (swap! a (fn [db] (d/db-with db (mapv (fn [cid] {:db/id cid :count 0})
                                              counter-ids))))
        (println (pr-str {:mode :seed
                          :datoms (datom-count @a)
                          :seal (atom/seal a)})))

      "go"
      (let [a (open-atom dir)]
        (swap! a (fn [db] (d/db-with db [{:db/id go-id :go true}])))
        (println (pr-str {:mode :go :t (now-ms)})))

      "window"
      (window! dir (parse-long w))

      "counters"
      (let [a (open-atom dir)
            db @a]
        (println (pr-str {:mode :counters
                          :counters (mapv (fn [cid] (:count (d/entity db cid)))
                                          counter-ids)
                          :go (boolean (:go (d/entity db go-id)))
                          :datoms (datom-count db)
                          :seal (atom/seal a)}))))
    (catch #?(:cljs :default :lpy python/Exception :jank jank.runtime.object_ref :default Exception) t
      (println (pr-str {:mode (keyword (or mode "none")) :fatal (str t)})))))
