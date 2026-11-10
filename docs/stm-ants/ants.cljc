(ns ants
  "The ants demo — Hickey's ants (ants-original.clj, filed beside
   this file) re-hosted on Eve STM at the disk tier
   (lane-m/stm-ants). The port keeps the original's defs, names and
   structure wherever Eve's STM carries them; the deltas are exactly
   the substrate ones, named here and in PORT-AUDIT.md:

   - The world is a flat vector of refs (the original's is 2d);
     `place` takes [x y] as in the original. Each ref's cell atom
     lives in its OWN persistent Eve domain on disk, so host
     PROCESSES contend on the same cells through the STM — the
     vector's index order is the lock order every process agrees on.
   - A Cell is a plain map {:food n :pher x :home bool :ants {...}}
     (the original's Cell record, plus :ants): cell values cross
     processes through Eve's slab codec on every seat, where plain
     data is the portable shape. :ants maps ant-id -> the original's
     Ant map {:dir d} (plus :food when carrying); ant-id is
     [process-tag k], the cross-process identity the original gets
     from its agent. Occupancy is one ant per cell by the behave
     rules, exactly as in the original.
   - create-ant/setup: initial cells are a deterministic function
     of position (the original's setup is random) because cell
     creation is lookup-or-create — first writer wins, so every
     process must construct identical initial values.
   - behave's self-resend (send-off *agent* #'behave) lives in the
     per-host mains: agents are per-seat machinery. behave-once! is
     the original's behave body, one run-in-transaction per dosync.
   - The Swing UI is the ASCII renderer below, drawn from the same
     one-transaction snapshot the original's render uses.

   All shared state lives in Eve atoms (the cells, the clock, the
   world config, the ant registry) — memory law. A process's host
   state is only its ant-location cache and its behave loops."
  (:require [eve.atom :as ea]
            [eve.process :as proc]
            [eve.stm :as stm]))

;; ---------------------------------------------------------------------------
;; small portable helpers

(defn- now-ms []
  #?(:cljs (js/Date.now)
     :lpy (proc/now-ms)
     :cljr (proc/now-ms)
     :default (System/currentTimeMillis)))

(defn sleep! [ms]
  #?(:jank (proc/sleep-ms! ms)
     :cljr (proc/sleep-ms! ms)
     :clj (Thread/sleep ms)
     ;; the CLJS seats are single-threaded: a Date.now spin is the
     ;; only wait there is; these are dedicated ant processes, so
     ;; the spin starves nothing that matters
     :cljs (let [end (+ (js/Date.now) ms)]
             (loop [] (when (< (js/Date.now) end) (recur))))
     :lpy (proc/sleep-ms! ms)
     :default nil)
  nil)

(defn- pad4 [i]
  (let [s (str i)]
    (str (subs "0000" 0 (max 0 (- 4 (count s)))) s)))

(defn- abs* [x] (if (neg? x) (- x) x))

;; ---------------------------------------------------------------------------
;; the world grid — the original's constants and construction, with
;; dim a parameter (144 per the ORDER) because every host process
;; opens the same world

;;number of ants = nants-sqrt^2 in the original's setup; here it is
;;the home block's side (clamped on toy grids smaller than the
;;original's world, where 7 home cells would not fit at dim/4)
(def nants-sqrt 7)

(defn- idx->xy [dim i] [(mod i dim) (quot i dim)])
(defn- xy->idx [dim x y] (+ (* y dim) x))

(defn place
  "the ref of the cell at [x y] (the original's place, over the
   flat ref vector)"
  [w [x y]]
  (nth (:refs w) (xy->idx (:dim w) x y)))

(defn home-off
  "offset of the home block from the corner — the original's
   (/ dim 4), as a fn of dim"
  [dim]
  (quot dim 4))

(defn- home-cell? [dim x y]
  (let [off (home-off dim)
        n (min nants-sqrt (- dim off))]
    (and (<= off x (+ off n -1)) (<= off y (+ off n -1)))))

(defn- food-at [dim x y]
  ;; deterministic food clusters, a pure function of position so
  ;; every process constructs identical initial cells (the
  ;; original's setup scatters food randomly — see the ns doc)
  (let [c1 [(quot dim 4) (quot dim 4)]
        c2 [(- dim 1 (quot dim 4)) (- dim 1 (quot dim 4))]
        d (fn [[cx cy]] (+ (abs* (- x cx)) (abs* (- y cy))))]
    (cond (<= (d c1) 2) (inc (mod (+ (* x 7) (* y 13)) 3))
          (<= (d c2) 2) (inc (mod (+ (* x 5) (* y 11)) 3))
          :else 0)))

(defn initial-cell [dim i]
  (let [[x y] (idx->xy dim i)
        home (home-cell? dim x y)]
    {:food (if home 0 (food-at dim x y))
     :pher 0
     :home home
     :ants {}}))

(defn food-total [dim]
  (reduce + (map (fn [i] (:food (initial-cell dim i))) (range (* dim dim)))))

(defn home-cells [dim]
  (vec (filter (fn [i] (:home (initial-cell dim i))) (range (* dim dim)))))

;; ---------------------------------------------------------------------------
;; world open / build

(defn open-world
  "Open (creating if needed) the world at root: seat the STM clock
   in the world's shared clock atom, claim this process's tag, and
   construct the ref table in index order (so :seq — the lock
   order — agrees across processes). Returns the world record."
  [root dim]
  (let [clock-domain (ea/persistent-atom-domain (str root "/clock") :durable? false)
        clock (ea/lookup-or-create-mmap-atom! clock-domain "eve.stm/clock" 0)
        procs (ea/lookup-or-create-mmap-atom! clock-domain "ants/procs" 0)
        world-atom (ea/lookup-or-create-mmap-atom! clock-domain "ants/world" nil)
        registry (ea/lookup-or-create-mmap-atom! clock-domain "ants/registry" #{})]
    (stm/seat-clock! clock)
    (let [tag (swap! procs inc)]
      (stm/seat-process-tag! tag)
      {:dim dim
       :root root
       :tag tag
       :clock clock
       :world-atom world-atom
       :registry registry
       :locs (atom {})
       :refs (mapv (fn [i]
                     (stm/make-ref (initial-cell dim i)
                                   {:seat {:domain-path (str root "/cell-" (pad4 i))
                                           :name "ants/cell"
                                           :durable? false}}))
                   (range (* dim dim)))})))

(defn await-built!
  "Host processes only: wait — opening NOTHING but the clock
   domain — until the driver has built the world and marked it
   ready, and only then let open-world run. F-ANTS-4: at 144x144,
   hosts that construct the ref table CONCURRENTLY with the
   driver race Eve's domain creation: a process that opens a
   domain whose root another creator is still writing hits
   eve.atom/await-domain-root!'s budget and throws (\"the domain's
   creator never finished writing its root\", spike144 driver
   death at cell-0820; a creator that dies mid-create orphans the
   root permanently). The driver is the sole creator of cell
   domains; hosts only ever REOPEN completed ones. Returns true
   when the world is ready; throws past the deadline."
  ([root] (await-built! root 7200000))
  ([root deadline-ms]
   (let [clock-domain (ea/persistent-atom-domain (str root "/clock") :durable? false)
         world-atom (ea/lookup-or-create-mmap-atom! clock-domain "ants/world" nil)
         deadline (+ (now-ms) deadline-ms)]
     (loop []
       (if (:ready @world-atom)
         true
         (do (when (> (now-ms) deadline)
               (throw (ex-info "ants: world never became ready (await-built!)" {})))
             (sleep! 2000)
             (recur)))))))

(defn build!
  "Driver only: publish the world config and mark the world ready.
   Initial cell values are already correct by construction (the
   deterministic initial-cell of whichever process created each)."
  [w]
  (swap! (:world-atom w)
         (fn [_] {:dim (:dim w)
                  :food-total (food-total (:dim w))
                  :ready true}))
  w)

(defn await-ready!
  "Wait for the driver's ready mark. The budget is generous on
   purpose: the driver's world build is dozens of domain creates
   behind a cold host start, and this wait is a rendezvous, not a
   measurement."
  [w]
  (let [deadline (+ (now-ms) 600000)]
    (loop []
      (when-not (:ready @(:world-atom w))
        (when (> (now-ms) deadline)
          (throw (ex-info "ants: world never became ready" {})))
        (sleep! 200)
        (recur))))
  w)

(defn place-ants!
  "Place this process's ant-count ants, one transaction per ant;
   register the ids in the shared registry. Returns the ant ids.
   Placement: on the full 144 board, each process seeds its ants
   inside its own 12-column group (the ORDER's column ownership —
   group = tag-1; the ants then wander the whole torus, so every
   group crossing is a cross-host transaction); on smaller boards
   ants go onto home cells round-robin by tag, as the founding
   smokes ran. (The original's setup creates one ant per home
   cell with a random dir; the demo's per-host ant counts and
   deterministic dirs are the named cross-process delta.)"
  [w ant-count]
  (let [dim (:dim w)
        spots (if (>= dim 144)
                (let [g (mod (dec (:tag w)) 12)
                      x0 (* 12 g)]
                  (mapv (fn [k]
                          (xy->idx dim
                                   (+ x0 (mod (+ k (:tag w)) 12))
                                   (mod (+ (* 37 k) (* 91 (:tag w))) dim)))
                        (range ant-count)))
                (let [homes (home-cells dim)]
                  (mapv (fn [k] (nth homes (mod (+ (dec (:tag w)) k)
                                                 (count homes))))
                        (range ant-count))))
        ids (mapv (fn [k] [(:tag w) k]) (range ant-count))]
    (doseq [[k id] (map-indexed (fn [k id] [k id]) ids)]
      (let [cell-idx (nth spots k)
            r (nth (:refs w) cell-idx)]
        (stm/run-in-transaction
         (fn []
           (stm/alter* r (fn [c] (assoc-in c [:ants id]
                                           {:dir (mod (+ (:tag w) k) 8)}))
                       [])))
        (swap! (:locs w) assoc id cell-idx)))
    (swap! (:registry w) (fn [s] (into s ids)))
    ids))

;; ---------------------------------------------------------------------------
;; ant behaviour — the original's fns, in the original's order.
;; turn/move/take-food/drop-food are transaction bodies (the
;; original calls them inside behave's dosync; nested dosync
;; flattens, and so does eve.stm's run-in-transaction).

(defn bound
  "returns n wrapped into range 0-b"
  [b n]
  (let [n (rem n b)]
    (if (neg? n)
      (+ n b)
      n)))

(defn wrand
  "given a vector of slice sizes, returns the index of a slice given a
   random spin of a roulette wheel with compartments proportional to
   slices."
  [slices]
  (let [total (reduce + slices)
        r (rand total)]
    (loop [i 0 sum 0]
      (if (< r (+ (slices i) sum))
        i
        (recur (inc i) (+ (slices i) sum))))))

;;dirs are 0-7, starting at north and going clockwise
;;these are the deltas in order to move one step in given dir
(def dir-delta {0 [0 -1]
                1 [1 -1]
                2 [1 0]
                3 [1 1]
                4 [0 1]
                5 [-1 1]
                6 [-1 0]
                7 [-1 -1]})

(defn delta-loc
  "returns the location one step in the given dir. Note the world is a torus"
  [[x y] dim dir]
  (let [[dx dy] (dir-delta (bound 8 dir))]
    [(bound dim (+ x dx)) (bound dim (+ y dy))]))

(defn- ant-at
  "the ant map of id in cell value c (the original's (:ant @p),
   keyed by id in the port's :ants)"
  [c id]
  (get (:ants c) id))

(defn- occupied?
  "the original's (:ant @place) test, over the port's :ants"
  [w loc]
  (boolean (seq (:ants (stm/deref-ref (place w loc))))))

(defn turn
  "turns the ant at the location by the given amount"
  [w loc id amt]
  (let [p (place w loc)
        c (stm/deref-ref p)
        ant (ant-at c id)]
    (stm/ref-set* p (assoc-in c [:ants id :dir] (bound 8 (+ (:dir ant) amt)))))
  loc)

(defn move
  "moves the ant in the direction it is heading. Must be called in a
   transaction that has verified the way is clear"
  [w loc id]
  (let [oldp (place w loc)
        oc (stm/deref-ref oldp)
        ant (ant-at oc id)
        newloc (delta-loc loc (:dim w) (:dir ant))
        p (place w newloc)]
    ;;move the ant
    (stm/ref-set* p (assoc-in (stm/deref-ref p) [:ants id] ant))
    ;;leave pheromone trail
    (stm/ref-set* oldp (cond-> (update oc :ants dissoc id)
                         (not (:home oc)) (assoc :pher (inc (:pher oc)))))
    newloc))

(defn take-food
  "Takes one food from current location. Must be called in a
   transaction that has verified there is food available"
  [w loc id]
  (let [p (place w loc)
        c (stm/deref-ref p)]
    (stm/ref-set* p (-> c (assoc :food (dec (:food c)))
                          (assoc-in [:ants id :food] true))))
  loc)

(defn drop-food
  "Drops food at current location. Must be called in a
   transaction that has verified the ant has food"
  [w loc id]
  (let [p (place w loc)
        c (stm/deref-ref p)]
    (stm/ref-set* p (-> c (assoc :food (inc (:food c)))
                          (update-in [:ants id] dissoc :food))))
  loc)

(defn rank-by
  "returns a map of xs to their 1-based rank when sorted by keyfn"
  [keyfn xs]
  (let [sorted (sort-by (comp float keyfn) xs)]
    (reduce (fn [ret i] (assoc ret (nth sorted i) (inc i)))
            {} (range (count sorted)))))

(defn behave-once!
  "the main function for the ant agent — the original's behave,
   one step: the original sleeps and re-sends itself from the agent
   loop (that loop lives in the host mains here); the body is the
   original's, inside one transaction. Returns the action taken
   (:drop, :take, :step or :turn)."
  [w id]
  (let [loc (idx->xy (:dim w) (get @(:locs w) id))
        outcome (atom nil)
        newloc (atom nil)
        step! (fn [nl] (reset! outcome :step) (reset! newloc nl))]
    (stm/run-in-transaction
     (fn []
       ;;outcome/newloc are host-local side channels, and the body
       ;;can run more than once (STM retry): reset them at the head
       ;;of EVERY attempt, so the post-transaction read below sees
       ;;exactly the committing attempt's result — never a stale
       ;;destination from an earlier attempt whose move did not
       ;;commit (F-ANTS-3: a retried move followed by a committed
       ;;turn once left :locs pointing at the uncommitted move's
       ;;destination; the ant itself was never lost — the audits,
       ;;which read the cells, stayed clean throughout)
       (reset! outcome nil)
       (reset! newloc nil)
       (let [p (place w loc)
             ant (ant-at (stm/deref-ref p) id)
             ahead (delta-loc loc (:dim w) (:dir ant))
             ahead-left (delta-loc loc (:dim w) (dec (:dir ant)))
             ahead-right (delta-loc loc (:dim w) (inc (:dir ant)))
             places [ahead ahead-left ahead-right]
             cell (fn [l] (stm/deref-ref (place w l)))
             ;;the original's choice vector [move turn-left
             ;;turn-right], each a fn of the location
             choices [move
                      (fn [w l id] (turn w l id -1))
                      (fn [w l id] (turn w l id 1))]
             choose (fn [ranks]
                      (let [res ((choices
                                  (wrand [(if (occupied? w ahead) 0 (ranks ahead))
                                          (ranks ahead-left) (ranks ahead-right)]))
                                 w loc id)]
                        (if (= res loc)
                          (reset! outcome :turn)
                          (step! res))))]
         (if (:food ant)
           ;;going home
           (cond
             (:home (cell loc))
             (do (drop-food w loc id)
                 (turn w loc id 4)
                 (reset! outcome :drop))

             (and (:home (cell ahead)) (not (occupied? w ahead)))
             (step! (move w loc id))

             :else
             (choose (merge-with +
                                 (rank-by (comp (fn [c] (if (:home c) 1 0)) cell) places)
                                 (rank-by (comp :pher cell) places))))
           ;;foraging
           (cond
             (and (pos? (:food (cell loc))) (not (:home (cell loc))))
             (do (take-food w loc id)
                 (turn w loc id 4)
                 (reset! outcome :take))

             (and (pos? (:food (cell ahead))) (not (:home (cell ahead)))
                  (not (occupied? w ahead)))
             (step! (move w loc id))

             :else
             (choose (merge-with +
                                 (rank-by (comp :food cell) places)
                                 (rank-by (comp :pher cell) places))))))))
    (when @newloc
      (swap! (:locs w) assoc id (xy->idx (:dim w) (first @newloc) (second @newloc))))
    @outcome))

;; ---------------------------------------------------------------------------
;; evaporation — a background agent's pass over all cells

(defn evaporate-pass! [w rate]
  (doseq [r (:refs w)]
    (stm/run-in-transaction
     (fn [] (stm/alter* r (fn [c] (update c :pher (fn [p] (* p rate)))) [])))))

;; ---------------------------------------------------------------------------
;; snapshot, audit, render

(defn snapshot
  "The grid as ONE transactional snapshot: a read-only transaction
   touching every cell ref, served as of one read point."
  [w]
  (stm/run-in-transaction
   (fn [] (mapv (fn [r] (stm/deref-ref r)) (:refs w)))))

(defn audit
  "Check a snapshot against the invariants. Two verdict classes:
   PER-SNAPSHOT (must hold at every instant, mid-run included) —
   :ants-ok? (no ant id appears in more than one cell: step
   atomicity) and :food-ok? (cell food + carried == the world's
   initial food); failures land in :violations.
   QUIESCED (holds only once every host has finished placing) —
   :registered-ok? (the set of ants in cells == the registry);
   mid-run mismatches are placement transients (a host places its
   ants one transaction each and registers them as a group, so a
   snapshot can legitimately catch placed-but-unregistered or
   registered-but-unplaced ants) and land in :transients."
  [w cells]
  (let [appearances (frequencies (mapcat (fn [c] (keys (:ants c))) cells))
        registered @(:registry w)
        missing (filter (fn [id] (not (contains? appearances id))) registered)
        duped (filter (fn [[_ n]] (not= n 1)) appearances)
        unknown (filter (fn [id] (not (contains? registered id))) (keys appearances))
        food-sum (reduce + (map :food cells))
        carrying (count (filter (fn [e] (:food e))
                                (mapcat (fn [c] (vals (:ants c))) cells)))
        cfg @(:world-atom w)]
    {:ants-ok? (empty? duped)
     :registered-ok? (and (empty? missing) (empty? unknown))
     :food-ok? (= (+ food-sum carrying) (:food-total cfg))
     :ant-count (count appearances)
     :registered (count registered)
     :food-sum food-sum
     :carrying carrying
     :food-total (:food-total cfg)
     :clock @(:clock w)
     :violations (cond-> []
                   (seq duped) (conj (str "duplicated ants: " (vec duped))))
     :transients (cond-> []
                   (seq missing) (conj (str "missing ants: " (vec missing)))
                   (seq unknown) (conj (str "unregistered ants: " (vec unknown))))}))

(def ant-glyphs
  "Board glyphs for a searching ant, by heading 0-7 (dir-delta
   order: 0 steps -y, up on the board, then clockwise)."
  ["↑" "↗" "→" "↘" "↓" "↙" "←" "↖"])

(def ant-glyphs-carrying
  "Board glyphs for an ant carrying food — the double-arrow set,
   same headings as ant-glyphs, visibly heavier."
  ["⇑" "⇗" "⇒" "⇘" "⇓" "⇙" "⇐" "⇖"])

(defn- cell-glyph
  "One board character for cell c. Priority: ant (heading and
   carrying state) > home zone > food quantity band > pheromone
   intensity band > empty. Pheromone bands sit on this ports
   scale: +1 per ant step over a cell, evaporating x0.99/s, so a
   single fresh deposit reads 1.0."
  [c]
  (let [ant (first (vals (:ants c)))]
    (cond
      ant
      (nth (if (:food ant) ant-glyphs-carrying ant-glyphs) (mod (:dir ant) 8))
      (:home c) "⌂"
      (pos? (:food c))
      (cond
        (>= (:food c) 4) "#"
        (= (:food c) 3) "+"
        (= (:food c) 2) ":"
        :else "·")
      (> (:pher c) 2.5) "█"
      (> (:pher c) 1.0) "▓"
      (> (:pher c) 0.25) "▒"
      (pos? (:pher c)) "░"
      :else " ")))

(defn render
  "Rich ASCII board from a snapshot: ants as heading arrows
   (double arrows when carrying food), the home block as ⌂,
   food in quantity bands (· 1, : 2, + 3, # 4+), pheromone
   trails as gradient blocks (░ faint, ▒ fresh, ▓ busy, █
   highway). One char per cell, exactly dim lines. (The pre-fix
   render built each row string and discarded it, returning a
   newline per row — the board printed blank on every platform;
   demo/render_check.clj is the regression check.)"
  [dim cells]
  (apply str
         (interpose "\n"
                    (map (fn [y]
                           (apply str
                                  (map (fn [x]
                                         (cell-glyph (nth cells
                                                          (xy->idx dim x y))))
                                       (range dim)))) (range dim)))))
