(ns eve.thread.fold
  "fold and =>> over eve.thread's pools (EVE-THREAD plan P§2.1, P§1.5, P§5.7, gap EVT-5).

     (fold reducef coll)                    clojure.core.reducers/fold on *pool* (else the :compute pool): coll
     (fold combinef reducef coll)           chunked, each chunk reduced with reducef from (combinef) as a descriptor on
     (fold n combinef reducef coll)         the pool, the chunks' values combined with combinef in order
     (=>> x form ...)                       ->>, its runs of registered transducers composed (injest's x>>), each run
                                            of chunk-separable ones folded over the pool, the others stepped here

   One protocol for every pool: a chunk is a thread descriptor (eve.thread.descriptor), so the same fold runs on a
   pool of one heap's threads, of isolates, and on a group — a domain pool (eve.thread.domain) whose members are
   runtimes of several processes and hosts. Where the pool's threads share this heap a chunk is a closure; where they
   do not only data travels (eve.thread.task): a registered task of this namespace with the chunk, and the user's fns
   as registered tasks' names — or, for =>>, the run's forms as a tfn where the pool allows forms.

   Chunks are sized from measurement, not a constant (P§5.7): elements per descriptor such that a descriptor's work is
   at least ten times its swaps' cost. The swaps' cost is the pool's cell's, measured on the pool (swap-cost): a fixed
   part per swap (:swap-us) and a part per element the descriptor carries (:carry-ns) — where the threads do not share
   this heap a chunk's elements travel in its descriptor, and moving them is part of its swaps (a domain cell measures
   both on its meta-atom and publishes them as the runtime's :measured, P§5.4). An element's cost is measured by the
   caller on the input's head, which it reduces itself first (its value the fold's first). Where no chunk is worth its
   swaps (an element costs less than ten times its carrying) the rest is reduced where the fold was called: the
   pool's cell cannot carry the work for less than doing it. *chunk* (or fold's n) sets the size instead, as
   cljs-thread's *chunk* and reducers' n did.

   The parallel set, corrected (P§1.5): injest registered dedupe, take-while, halt-when and random-sample as
   parallel, but they are not chunk-separable — per chunk, dedupe keeps a duplicate at a seam, take-while and halt-when
   keep what follows the first failure in later chunks, and random-sample's draws leave the order of one generator —
   so they are stepped here, in order, as ->> would; and disj! and dissoc!, in both of injest's registries, are no
   transducers at all (their one-argument arity answers its argument).

   The thread binding conveyance cljs-thread's =>> lacked: chunks run with the bindings of the call (the host's frame
   where the threads share a heap, the declared conveyed vars where they do not), wherever and whenever they are
   submitted."
  (:require [eve.process :as proc]
            [eve.thread-host :as h]
            [eve.thread.cell :as c]
            [eve.thread.pool :as pool]
            [eve.thread.task :as task]))

;; ---------------------------------------------------------------------------
;; The registry: Clojure's transducers, by name, the argument counts of their transducer arity, and which are
;; chunk-separable
;; ---------------------------------------------------------------------------

(def transducers
  "injest's registry (injest.data def-regs, read from injest 0.1.0-beta.8) less disj! and dissoc!: each name with the
   argument counts that make its form a transducer (:bare — the symbol itself, cat), and :parallel when applying it to
   each chunk and concatenating equals applying it to the whole: no state across elements, no early end, no draws."
  {:map {:arities #{1} :parallel true}
   :filter {:arities #{1} :parallel true}
   :remove {:arities #{1} :parallel true}
   :keep {:arities #{1} :parallel true}
   :mapcat {:arities #{1} :parallel true}
   :cat {:arities #{:bare} :parallel true}
   :replace {:arities #{1} :parallel true}
   ;; injest's parallel set had these four: not chunk-separable (P§1.5)
   :dedupe {:arities #{0} :parallel false}
   :take-while {:arities #{1} :parallel false}
   :halt-when {:arities #{1 2} :parallel false}
   :random-sample {:arities #{1} :parallel false}
   ;; stateful or ending: stepped here
   :drop-while {:arities #{1} :parallel false}
   :take {:arities #{1} :parallel false}
   :drop {:arities #{1} :parallel false}
   :take-nth {:arities #{1} :parallel false}
   :partition-all {:arities #{1} :parallel false}
   :partition-by {:arities #{1} :parallel false}
   :distinct {:arities #{0} :parallel false}
   :interpose {:arities #{1} :parallel false}
   :map-indexed {:arities #{1} :parallel false}
   :keep-indexed {:arities #{1} :parallel false}})

(def ^:private core-namespaces
  "The namespaces a transducer's head resolves into: Clojure's, ClojureScript's, and the host's own core as its
   syntax-quote names it."
  (set ["clojure.core" "cljs.core" (namespace `map)]))

(defn parallel?
  "Is the registered transducer k (a keyword: :map) chunk-separable."
  [k]
  (true? (:parallel (get transducers k))))

(defn- registered-key [qsym]
  (when (and (symbol? qsym) (contains? core-namespaces (namespace qsym)))
    (let [k (keyword (name qsym))] (when (contains? transducers k) k))))

(defn classify
  "A thread form's kind, its head qualified (qhead: the symbol its head resolves to where the form is written, or nil):
   [:par k] or [:seq k] for a registered transducer written with its transducer arity, else [:step nil]."
  [qhead form]
  (let [k (registered-key qhead)
        arities (:arities (get transducers k))
        xf? (and k (if (symbol? form)
                     (contains? arities :bare)
                     (and (seq? form) (contains? arities (count (rest form))))))]
    (if xf? [(if (parallel? k) :par :seq) k] [:step nil])))

(defn segments
  "=>>'s plan of a thread: each [form qhead] classified, the consecutive registered transducers grouped into runs
   ({:kind :par} chunk-separable, {:kind :seq} not), every other form a {:kind :step} of its own."
  [form-heads]
  (reduce (fn [segs [form qhead]]
            (let [[kind k] (classify qhead form)
                  last-seg (peek segs)]
              (if (and (not= kind :step) (= kind (:kind last-seg)))
                (conj (pop segs) (-> last-seg (update :forms conj form) (update :keys conj k)))
                (conj segs {:kind kind :forms [form] :keys [k]}))))
          []
          form-heads))

;; ---------------------------------------------------------------------------
;; Emitting a vector's elements ahead of more, as cells (no concat chain: basilisp walks nested concats on the Python
;; stack — a run of chunks past its recursion limit raised RecursionError [ran])
;; ---------------------------------------------------------------------------

(defn- emit
  "The elements of v (a vector, or anything vec takes) from i, then the seq (more) answers — each a cell of its own."
  [v i more]
  (if (< i (count v))
    (cons (nth v i) (lazy-seq (emit v (inc i) more)))
    (more)))

;; ---------------------------------------------------------------------------
;; Stepping a transducer here, written once (a host's own sequence is not used: basilisp's emits a buffering
;; transducer's buffer at each step — (sequence (partition-all 2) [1 2 3]) → ([1] [1 2] [3] [3]) — and fails on
;; halt-when [ran])
;; ---------------------------------------------------------------------------

(defn xf-seq
  "(sequence xf coll): a lazy seq of what transducer xf makes of coll, one input stepped at a time; the completion run
   once, when coll ends or a step answers reduced. coll is walked once (a host's lazy seq need not memoize: clojerl's
   do not, ET-1). The buffer is a host atom: a lazy seq may be realized on another thread than made it."
  [xf coll]
  (let [buf (atom [])
        rf (xf (fn ([acc] acc) ([acc x] (swap! buf conj x) acc)))
        drain (fn [] (let [b @buf] (reset! buf []) b))
        step (fn step [s]
               (lazy-seq
                (loop [s s]
                  (when-not (= ::done s)
                    (if-let [s' (seq s)]
                      (let [r (rf nil (first s'))]
                        (if (reduced? r)
                          (do (rf nil) (emit (drain) 0 (fn [] nil)))
                          (let [out (drain)]
                            (if (seq out) (emit out 0 (fn [] (step (rest s')))) (recur (rest s'))))))
                      (do (rf nil) (emit (drain) 0 (fn [] nil))))))))]
    (step coll)))

(defn seq-run
  "A run of =>>'s non-separable transducers (composed: xf) over in, stepped here, in order — as ->> would."
  [xf in]
  (xf-seq xf in))

;; ---------------------------------------------------------------------------
;; Measured sizes (P§5.7): a descriptor's work at least ten times its swaps' cost
;; ---------------------------------------------------------------------------

(def ^:dynamic *chunk*
  "Elements per descriptor (cljs-thread's *chunk*); nil — the default — sizes chunks from the pool's measured swap cost
   and the work's measured element cost."
  nil)

(def ^:dynamic *on-plan*
  "When bound to a fn, each fold and each =>> run gives it its plan (as data: the sizes and the measurements behind
   them), read at the call."
  nil)

(def probe-ms
  "How long the caller reduces the input's head itself to measure an element's cost (the clock is in ms: over this many
   its resolution is a few per cent)."
  5)

(def work-to-swaps
  "A descriptor's work is at least this many times its swaps' cost (P§5.7)."
  10)

(defn chunk-size
  "Elements per descriptor for a plan's measurements: the fewest n whose work — n × the probe's cost per element (:us /
   :elements) — is at least work-to-swaps × its swaps' cost: :swaps × :swap-us, and :carries × :carry-ns per element it
   carries (fold's elements travel once, =>>'s twice: in, and out as the chunk's value); at least 1. nil when no n is:
   an element costs no more than work-to-swaps times its carrying. Integer arithmetic, in ns (every host)."
  [{:keys [swap-us swaps carry-ns carries elements us]}]
  (let [carry (* work-to-swaps (or carries 1) (or carry-ns 0) elements)
        den (- (* 1000 us) carry)]
    (when (pos? den)
      (let [num (* work-to-swaps swaps swap-us 1000 elements)]
        (max 1 (quot (+ num (dec den)) den))))))

(defn- now [] (proc/now-ms))

(defn- ceil-quot [a b] (quot (+ a (dec b)) b))

;; the cells that measure their own swaps: a pool's form (:form of its descriptor) → (fn [p] {:swap-us :swaps})
(defonce swap-costs (atom {}))

(defn register-swap-cost!
  "Pools of form (their descriptor's :form — eve.thread.domain registers :domain) measure their swaps' cost with f:
   (f p) answers {:swap-us µs-per-swap :swaps swaps-per-descriptor :carry-ns ns-per-element-carried}."
  [form f]
  (swap! swap-costs assoc form f)
  nil)

(defn- noop [& _] nil)

(def carry-probe
  "The elements the carrying cost is measured with."
  256)

(defn- round-trips
  "[descriptors ms]: trivial descriptors with args submitted on p one after another, until 10 ms or 512 of them."
  [p args]
  (let [t0 (now)]
    (loop [k 0]
      (if (or (>= (- (now) t0) 10) (>= k 512))
        [k (- (now) t0)]
        (do (deref (pool/submit p 'eve.thread.fold/noop args)) (recur (inc k)))))))

(defn- warm!
  "Each of p's threads reached once, untimed — trivial descriptors as many as its size, at once (a routed pool deals
   them a member each): a pool whose threads are still starting would otherwise have its threads' start timed as its
   swaps (measured: compiled ClojureScript's isolates load the program for seconds, and a first fold on a fresh pool
   sized chunks of five million elements)."
  [p]
  (doseq [f (mapv (fn [_] (pool/submit p 'eve.thread.fold/noop [])) (range (max 1 (or (:size p) 1))))]
    (deref f)))

(defn- round-trip-cost
  "A rung-0 pool's cost of moving one descriptor (its cell's host-atom swaps and messages together, counted as one
   swap), µs, and of each element it carries, ns: trivial descriptors round trip on p — warmed first — with no data
   and with carry-probe elements. Kept in the pool's state: measured once per pool."
  [p]
  (or (get @(:state p) ::swap-cost)
      (let [_ (warm! p)
            [k0 ms0] (round-trips p [])
            [k1 ms1] (round-trips p [(vec (range carry-probe))])
            per0 (quot (* 1000000 ms0) k0)
            per1 (quot (* 1000000 ms1) k1)
            cost {:swap-us (max 1 (ceil-quot per0 1000)) :swaps 1 :carry-ns (max 0 (quot (- per1 per0) carry-probe))}]
        (swap! (:state p) assoc ::swap-cost cost)
        cost)))

(defn swap-cost
  "{:swap-us :swaps :carry-ns} of pool p, measured: µs per swap of its cell, the swaps one descriptor makes there, and
   ns per element a descriptor carries."
  [p]
  (if-let [f (get @swap-costs (:form (pool/pool-descriptor p)))]
    (f p)
    (round-trip-cost p)))

;; ---------------------------------------------------------------------------
;; What a chunk is: a closure in this heap, data across one
;; ---------------------------------------------------------------------------

(defn- shares-heap? [p] (= :local (:form (pool/pool-descriptor p))))

(defn- value?
  "May x cross a heap as data: no fn anywhere in it."
  [x]
  (cond (fn? x) false
        (map? x) (every? (fn [[k v]] (and (value? k) (value? v))) x)
        (coll? x) (every? value? x)
        :else true))

(defn- fn-ref
  "f as data: a registered task's name and its form's hash, or nil."
  [f]
  (when-let [s (task/task-of f)]
    (when-let [t (get @task/tasks s)] [::task s (:hash t)])))

(defn- arg-ref [x]
  (cond (fn? x) (fn-ref x)
        (value? x) [::value x]
        :else nil))

(defn- ref-fn [r] (if (= ::task (first r)) (task/task-fn (nth r 1) (nth r 2)) (nth r 1)))

(defn- a-fn [f] (if (symbol? f) (task/task-fn f) f))

(defn- reduce-items
  "items reduced with rf from init (a map's entries as [k v]: rf takes the accumulator, the key and the value)."
  [kv? rf init items]
  (if kv?
    (reduce (fn [acc e] (rf acc (nth e 0) (nth e 1))) init items)
    (reduce rf init items)))

(defn reduce-chunk
  "fold's chunk where it crosses a heap: chunk reduced from (combinef) with reducef, both registered tasks' refs."
  [cref rref kv? chunk]
  (reduce-items kv? (ref-fn rref) ((ref-fn cref)) chunk))

(defn into-chunk
  "=>>'s chunk: what transducer xf makes of chunk, as a vector."
  [xf chunk]
  (into [] xf chunk))

(defn- build-xf [[k & args]]
  (let [args (map ref-fn args)]
    (case k
      :cat cat
      :map (apply map args)
      :filter (apply filter args)
      :remove (apply remove args)
      :keep (apply keep args)
      :mapcat (apply mapcat args)
      :replace (apply replace args))))

(defn xf-chunk
  "=>>'s chunk where it crosses a heap: the run's transducers written as data ([k arg-ref ...] each, rebuilt here from
   the registry's own constructors) applied to chunk."
  [spec chunk]
  (into-chunk (apply comp (map build-xf spec)) chunk))

;; this namespace's tasks: what crosses a heap by name (registered as deftask would, without eve.thread's macro — which
;; requires this namespace; the hash is a version tag, the same on every host)
(task/register-task! 'eve.thread.fold/noop noop (task/form-hash '(noop [& _] 2)))
(task/register-task! 'eve.thread.fold/reduce-chunk reduce-chunk (task/form-hash '(reduce-chunk [cref rref kv? chunk] 1)))
(task/register-task! 'eve.thread.fold/xf-chunk xf-chunk (task/form-hash '(xf-chunk [spec chunk] 1)))

;; ---------------------------------------------------------------------------
;; The input, walked once: probe slices and chunks
;; ---------------------------------------------------------------------------

(defn- take-vec
  "[the first k items of s as a vector, the rest] — s a realized seq (or nil), each cell forced once."
  [s k]
  (loop [s s v []]
    (if (and s (< (count v) k)) (recur (next s) (conj v (first s))) [v s])))

(defn- items-of
  "coll as [kv? items]: a map's entries as [k v] vectors (reduce-kv's), a vector itself, anything else its seq."
  [coll]
  (cond (map? coll) [true (map (fn [e] [(key e) (val e)]) coll)]
        (vector? coll) [false coll]
        :else [false (seq coll)]))

(defn- slicer
  "A cursor over items: (next-slice! k) answers the next k items as a vector (fewer at the end, [] after), the input
   walked once — a vector by subvec, anything else by its seq."
  [items]
  (if (vector? items)
    (let [i (atom 0) n (count items)]
      (fn [k] (let [a @i b (min n (+ a k))] (reset! i b) (subvec items a b))))
    (let [s (atom (seq items))]
      (fn [k] (let [[v more] (take-vec @s k)] (reset! s more) v)))))

(defn- probe!
  "The caller's own share and the element cost: slices of 1, 2, 4, ... items taken from next-slice! and given to
   (run! acc slice), which answers the new acc, until probe-ms have passed or the input ends. {:acc :elements :us
   :exhausted}."
  [next-slice! run! acc0]
  (let [t0 (now)]
    (loop [acc acc0 k 1 elements 0]
      (let [slice (next-slice! k)
            exhausted? (< (count slice) k)
            acc (if (seq slice) (run! acc slice) acc)
            elements (+ elements (count slice))
            dt (- (now) t0)]
        (if (or exhausted? (>= dt probe-ms))
          {:acc acc :elements elements :us (* 1000 dt) :exhausted exhausted?}
          (recur acc (* 2 k) elements))))))

(defn- slice-seq
  "The rest of the input as a lazy seq of chunks of n, each chunk taken when realized (the input walked once)."
  [next-slice! n]
  (lazy-seq (let [v (next-slice! n)] (when (seq v) (cons v (slice-seq next-slice! n))))))

;; ---------------------------------------------------------------------------
;; Submission: semi-lazy, (pool/lookahead p) ahead, with the call's bindings
;; ---------------------------------------------------------------------------

(defn- with-call-bindings
  "f run with the bindings of the call: the host's frame (threads of one heap capture it at submission) and the
   declared conveyed vars' values (data work captures those at submission)."
  [{:keys [frame conveyed]} f]
  (h/call-with-frame frame (fn [] (task/call-with-conveyed conveyed f))))

(defn- call-bindings [] {:frame (h/capture-frame) :conveyed (task/capture-conveyed)})

(defn- results
  "The values of each [f args] of calls on pool p, in order, semi-lazily: realizing value i submits through call
   i + (lookahead p), each submission with the call's bindings b. The calls are walked once, by this cursor (as
   pmap-calls walks them: a host's lazy seq need not memoize); one consumer."
  [p b calls]
  (let [n (pool/lookahead p)
        st (atom {:futs [] :more calls})
        submit-through! (fn [upto]
                          (loop []
                            (let [{:keys [futs more]} @st]
                              (when (and (<= (count futs) upto) (some? more))
                                (if-let [s (seq more)]
                                  (let [[f args] (first s)
                                        fut (with-call-bindings b (fn [] (pool/submit p f args)))]
                                    (reset! st {:futs (conj futs fut) :more (rest s)}))
                                  (swap! st assoc :more nil))
                                (recur)))))
        step (fn step [i]
               (lazy-seq
                (submit-through! (+ i n))
                (when-let [f (get (:futs @st) i)]
                  (cons (deref f) (step (inc i))))))]
    (step 0)))

(defn- flat
  "The elements of a seq of vectors, lazily (empty vectors skipped without nesting)."
  [vs]
  (lazy-seq
   (loop [s (seq vs)]
     (when s
       (let [v (first s)]
         (if (seq v)
           (emit (if (vector? v) v (vec v)) 0 (fn [] (flat (rest s))))
           (recur (next s))))))))

(defn- plan! [on-plan plan] (when on-plan (on-plan plan)) plan)

;; ---------------------------------------------------------------------------
;; fold
;; ---------------------------------------------------------------------------

(defn- fold-call
  "A fold chunk's [f args] on p: a closure where its threads share this heap; else the registered task reduce-chunk
   with combinef's and reducef's names — or, when either is no registered task, the closure, which the pool refuses
   with the rule's message."
  [p kv? combinef reducef]
  (let [local? (shares-heap? p)
        cref (fn-ref combinef)
        rref (fn-ref reducef)
        cf (a-fn combinef)
        rf (a-fn reducef)]
    (if (or local? (nil? cref) (nil? rref))
      (fn [chunk] [(fn [] (reduce-items kv? rf (cf) chunk)) []])
      (fn [chunk] ['eve.thread.fold/reduce-chunk [cref rref kv? chunk]]))))

(defn fold-in
  "fold on pool p: coll in chunks of n elements (nil: sized from the pool's measured swap cost and the elements'
   measured cost — the caller reduces the input's head itself to measure it, its value the fold's first), each
   reduced from (combinef) by reducef as a descriptor on p, their values combined in order by combinef. combinef must
   be associative and (combinef) its identity; a map folds as reduce-kv (reducef takes the accumulator, the key and the
   value). Where p's threads do not share this heap, combinef and reducef are registered tasks (deftask) and coll's
   elements data."
  [p n combinef reducef coll]
  (let [on-plan *on-plan*
        b (call-bindings)
        [kv? items] (items-of coll)
        next-slice! (slicer items)
        cf (a-fn combinef)
        rf (a-fn reducef)
        base {:kind :fold :pool (pool/pool-name-of p)}
        ;; the probe's share is one chunk until reducef answers reduced — which ends a chunk, never the fold (as
        ;; reducers' fold): from then on each probe slice is a chunk of its own, combined in
        run (fn [[acc split?] slice]
              (let [stop (fn [a & xs] (let [r (apply rf a xs)] (if (reduced? r) (reduced r) r)))
                    r (reduce-items kv? stop (if split? (cf) acc) slice)
                    [v halted?] (if (reduced? r) [@r true] [r false])]
                [(if split? (cf acc v) v) (or split? halted?)]))
        probe (when (nil? n) (assoc (probe! next-slice! run [(cf) false]) :run run))
        head (first (:acc probe))
        plan (cond-> base probe (assoc :probe (select-keys probe [:elements :us])))]
    (if (:exhausted probe)
      (do (plan! on-plan (assoc plan :n nil :chunks 0)) head)
      (let [cost (when (nil? n) (assoc (swap-cost p) :carries 1))
            m (or n (chunk-size (merge cost probe)))]
        (if (nil? m)
          ;; no chunk is worth its swaps: the rest reduced here, as the probe reduced the head
          (let [r (first (reduce (fn [acc slice] ((:run probe) acc slice)) (:acc probe) (slice-seq next-slice! 4096)))]
            (plan! on-plan (merge (assoc plan :n nil :given false :chunks 0 :here true) cost))
            r)
          (let [vs (results p b (map (fold-call p kv? combinef reducef) (slice-seq next-slice! m)))
                ;; combined in order: the probe's share first; without one, the first chunk's value
                [r k] (reduce (fn [[a k] v] [(if (and (zero? k) (nil? probe)) v (cf a v)) (inc k)]) [head 0] vs)]
            (plan! on-plan (merge (assoc plan :n m :given (some? n) :chunks k) cost))
            (if (and (zero? k) (nil? probe)) (cf) r)))))))

(defn fold
  "clojure.core.reducers/fold on *pool* (read here, at the call), else the :compute pool: (fold reducef coll),
   (fold combinef reducef coll), (fold n combinef reducef coll) — n elements per descriptor, or *chunk*, or (nil, the
   default) sized from measurement (fold-in)."
  ([reducef coll] (fold reducef reducef coll))
  ([combinef reducef coll] (fold nil combinef reducef coll))
  ([n combinef reducef coll] (fold-in (pool/pool-for :compute) (or n *chunk*) combinef reducef coll)))

;; (Called as eve.thread.fold/fold — as reducers' is r/fold — not through eve.thread's api table: a var eve.thread/fold
;; would compile, in ClojureScript, over this namespace's object eve.thread.fold.)

;; ---------------------------------------------------------------------------
;; =>>'s chunk-separable runs
;; ---------------------------------------------------------------------------

(defn- par-call
  "A run's chunk's [f args] on p: a closure where its threads share this heap; else the run written as data
   (xf-chunk), when each of its arguments is a value or a registered task; else its forms (tfn), which the pool takes
   where it allows forms and its threads eval, and refuses otherwise."
  [p xf spec tfn]
  (let [data (when-not (shares-heap? p)
               (let [refs (mapv (fn [[k & args]] (into [k] (map arg-ref args))) spec)]
                 (when (every? (fn [r] (every? some? (rest r))) refs) refs)))]
    (cond
      (shares-heap? p) (fn [chunk] [(fn [] (into-chunk (xf) chunk)) []])
      data (fn [chunk] ['eve.thread.fold/xf-chunk [data chunk]])
      :else (fn [chunk] [tfn [chunk]]))))

(defn par-run
  "A run of =>>'s chunk-separable transducers over in on *pool* (else the :compute pool), read here at the call, with
   the call's bindings: lazily, chunk by chunk, (lookahead p) chunks ahead, in order. run: {:xf (fn [] composed
   transducer) :spec [[k arg ...] ...] :tfn (tfn [chunk] ...)} — what =>> writes."
  [{:keys [xf spec tfn]} in]
  (let [p (pool/pool-for :compute)
        n *chunk*
        on-plan *on-plan*
        b (call-bindings)
        base {:kind :=>> :pool (pool/pool-name-of p) :run (mapv first spec)}]
    (lazy-seq
     (let [next-slice! (slicer (if (vector? in) in (seq in)))
           x (xf)
           probe (when (nil? n)
                   (probe! next-slice! (fn [acc slice] (conj acc (into-chunk x slice))) []))]
       (if (:exhausted probe)
         (do (plan! on-plan (assoc base :probe (select-keys probe [:elements :us]) :n nil :chunks 0))
             (flat (:acc probe)))
         (let [cost (when (nil? n) (assoc (swap-cost p) :carries 2))
               m (or n (chunk-size (merge cost probe)))
               plan (cond-> (assoc base :n m :given (some? n))
                      probe (assoc :probe (select-keys probe [:elements :us]))
                      cost (merge cost))]
           (if (nil? m)
             ;; no chunk is worth its swaps: the rest transduced here, lazily, as the probe did the head
             (do (plan! on-plan (assoc plan :here true))
                 (flat (concat (:acc probe) (map (fn [slice] (into-chunk x slice)) (slice-seq next-slice! 512)))))
             (do (plan! on-plan plan)
                 (flat (concat (:acc probe) (results p b (map (par-call p xf spec tfn) (slice-seq next-slice! m)))))))))))))
