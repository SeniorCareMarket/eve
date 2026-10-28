(ns eve.thread.rung
  "Where a domain cell lives (lane ET-3): the rungs of the plan's transport ladder (P§5.5) that eve.thread.domain reads
   and swaps its meta-atom through, as one protocol — so the domain cell's moves (eve.thread.meta's, unchanged) run the
   same over every rung:

     :heap    an eve atom of an in-process domain: the threads of one heap
     :disk    an eve atom of an mmap domain on a durable file system: co-hosted processes (rung 1)
     :window  the same domain placed on tmpfs (/dev/shm): co-hosted processes, no writeback (rung 2) — chosen by
              configuration only (place, rendezvous): the code that opens, reads and swaps it is :disk's
     :net     a network atom (eve.net.atom) at a broker — or at a GATEWAY, any member serving its disk domain's atoms on
              eve.net's routes (eve.net.gateway): any machine (rung 3). Its opener is eve.thread.net's, registered
              when that namespace loads (a host with no http adapter loads none of it)

   Which rung an atom is on is its developer's choice, made where it is opened: nothing moves a handle from one rung to
   another (operator, 2026-10-19 ET: \"Atom kinds are the developer's choice — nothing switches between disk and network by
   availability\"). A rung is a value of IRung; (open (locate r)) re-opens it in another heap (an isolate's thread is
   handed its runtime's rung as data).

   A disk or window rung also carries DOORBELLS (ring!, wait-bell!): a small region beside the domain whose 32-bit
   words a writer bumps and notifies after it commits, and a waiter blocks on — a real futex where the host's region
   has one ((driving) :wake :futex), a poll where it does not (:wake :poll: the JVM's region parks only threads of its
   own process). A waiter on another process's commit is woken without a swap or a read of the meta-atom."
  (:refer-clojure :exclude [swap!])
  (:require [eve.host :refer [swap!]]
            [eve.atom :as ea]
            [eve.deftype-proto.data :as ed]
            [eve.encoding :as enc]
            [eve.int32 :as i32]
            [eve.mem :as mem]
            [eve.region :as region]
            [eve.thread-host :as h]))

(defprotocol IRung
  (-kind [r] ":heap :disk :window :net")
  (-swap! [r f]
    "The atom's value replaced by (f value), atomically against every other writer of the atom on any rung; f may run
     more than once (an attempt that lost a race runs it again). Answers nothing.")
  (-read [r f]
    "(f value): under a pin on an eve atom (f's answer must not keep the value), a fresh read over the network.")
  (-meta-rung [r init] "The rung of this atom's meta-atom (eve.atom/meta-atom's pairing), made holding init on first use.")
  (-locate [r] "This rung as data: (open (-locate r)) re-opens it in any heap that can reach it.")
  (-bell [r] "The doorbell region beside the atom's domain, or nil (a heap or network rung has none)."))

(defn value "The atom's whole value, materialized." [r] (-read r ed/eve->clj))

;; ---------------------------------------------------------------------------
;; Placement: a domain on disk or in the window, by configuration only (C1)
;; ---------------------------------------------------------------------------

(def defaults
  "Where :placement puts a domain named n: <:disk-root>/n — the current directory when nothing says otherwise — or
   <:window-root>/n (tmpfs: POSIX shared memory)."
  {:placement :disk :disk-root "." :window-root "/dev/shm/eve"})

;; path prefix → placement: what a domain opened by path (not by a configuration) is (the window roots configured so
;; far, and tmpfs's usual mount)
(defonce ^:private window-roots (clojure.core/atom #{"/dev/shm/"}))

(defn- prefix? [s p] (and (>= (count s) (count p)) (= p (subs s 0 (count p)))))

(defn placement-of
  "The placement of a domain at path: :window under a window root, :disk elsewhere, :heap for an in-process domain."
  [path]
  (cond (nil? path) :heap
        (some (fn [p] (prefix? path p)) @window-roots) :window
        :else :disk))

(defn place
  "The base path of the domain a configuration names: {:name n :placement :disk|:window :disk-root :window-root}
   (defaults: defaults). Moving a domain between a durable file system and tmpfs is this map's :placement — no code
   changes."
  [config]
  (let [{:keys [name placement disk-root window-root]} (merge defaults config)
        root (case placement :window window-root :disk disk-root
                   (throw (ex-info (str "eve.thread.rung: no placement " placement) {:placement placement})))]
    (when (= :window placement) (clojure.core/swap! window-roots conj (str window-root "/")))
    (str root "/" name)))

;; ---------------------------------------------------------------------------
;; Eve atoms: heap, disk, window
;; ---------------------------------------------------------------------------

(def ^:private BELL_BYTES "a domain's doorbell region: 1,024 words" 4096)

;; base path → its doorbell region (one mapping per process)
(defonce ^:private bells (clojure.core/atom {}))

(defn- bell-region [path]
  (or (get @bells path)
      (let [r (region/open-mmap-region (str path ".bell") BELL_BYTES)]
        (get (clojure.core/swap! bells (fn [m] (if (contains? m path) m (assoc m path r)))) path))))

(defrecord EveRung [a kind path nm])

(extend-type EveRung
  IRung
  (-kind [r] (:kind r))
  (-swap! [r f] (swap! (:a r) f) nil)
  (-read [r f] (ea/read-pinned (:a r) f))
  (-meta-rung [r init]
    (let [m (ea/meta-atom (:a r) init)]
      (->EveRung m (:kind r) (:path r) (ea/registered-name m))))
  (-locate [r] {:rung (:kind r) :domain (:path r) :name (:nm r)})
  (-bell [r] (when (:path r) (bell-region (:path r)))))

(defn eve-rung
  "The rung of eve atom a (its kind from where its domain is: in-process, on disk, in the window)."
  [a]
  (let [path (:base-path (ea/-domain-state a))]
    (->EveRung a (placement-of path) path (ea/registered-name a))))

(defn open-atom
  "The rung of the atom named nm in the domain at path, made holding init when absent."
  [path nm init]
  (eve-rung (ea/lookup-or-create-mmap-atom! (ea/persistent-atom-domain path) nm init)))

(defn rendezvous
  "The rendezvous atom named nm (made holding init when absent) of the domain a configuration places (place): its
   rung."
  [config nm init]
  (open-atom (place config) nm init))

;; ---------------------------------------------------------------------------
;; Opening a located rung (an isolate's thread re-opens its runtime's)
;; ---------------------------------------------------------------------------

;; kind → (fn [location] rung): the eve kinds here; :net registered by eve.thread.net
(defonce openers (clojure.core/atom {}))

(defn register-opener! [kind f] (clojure.core/swap! openers assoc kind f) nil)

(defn- open-eve [loc]
  (when-not (:domain loc)
    (throw (ex-info "eve.thread.rung: an isolate attaches only to a domain on disk (this one is in-process)"
                    {:eve.thread/refused :in-process-domain})))
  (open-atom (:domain loc) (:name loc) nil))

(defn open
  "The rung a location (-locate) names."
  [loc]
  (let [f (or (get @openers (:rung loc))
              (when (contains? #{:disk :window :heap} (:rung loc)) open-eve)
              (throw (ex-info (str "eve.thread.rung: no opener for rung " (:rung loc)
                                   " (eve.thread.net registers :net)")
                              {:eve.thread/refused :no-opener :rung (:rung loc)})))]
    (f loc)))

;; ---------------------------------------------------------------------------
;; Doorbells (C2)
;; ---------------------------------------------------------------------------
;; Word 0 rings at every settled descriptor (a sweep waits on it); words 1-511 ring for the descriptors whose id hashes
;; there (a waiter on one descriptor waits on its word); words 512-1023 for the pools whose name hashes there (a
;; submission or re-queue rings its pool's word: idle pump threads wait on it).

(defn- fnv
  "FNV-1a over s's UTF-16 units & 0xFF — the registry's name hash (eve.atom), the same int32 on every host: every
   process rings and waits on the same word for one id"
  [s]
  (reduce (fn [h u] (i32/imul (bit-xor h (bit-and u 0xFF)) 0x01000193)) (i32/i32 0x811c9dc5) (enc/utf16-units (str s))))

(defn completion-word [] 0)
(defn descriptor-word [id] (* 4 (+ 1 (mod (fnv id) 511))))
(defn pool-word [pool] (* 4 (+ 512 (mod (fnv pool) 512))))

(defn bell-value
  "The doorbell word's count now (nil when r has no doorbells)."
  [r word]
  (when-let [b (-bell r)] (mem/load-i32 b word)))

(defn ring!
  "Bump each of words and wake every waiter on them (a host whose region has no futex wakes no other process's: its
   waiters poll — the bump is what they see)."
  [r words]
  (when-let [b (-bell r)]
    (doseq [w (distinct words)]
      (mem/add-i32! b w 1)
      (mem/notify-i32! b w 2147483647))))

(defn futex?
  "Does a wait on this rung's doorbells block in the kernel until a ring (this host's region has a futex: (driving)
   :wake :futex)?"
  [r]
  (and (some? (-bell r)) (= :futex (:wake (h/driving)))))

(defn wait-bell!
  "Wait up to ms for word to differ from seen (its value read before the condition was checked): a futex wait where
   the host's region has one, else nothing (the caller's own poll). :rung when it rang, :timeout otherwise."
  [r word seen ms]
  (if (and (futex? r) (pos? ms))
    (if (= :timed-out (mem/wait-i32! (-bell r) word seen ms)) :timeout :rung)
    :timeout))
