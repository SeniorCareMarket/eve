(ns eve.read-region
  "The read region `$` opens (eve.alpha/$, eve.atom/$*): the dynamic extent of a body in which reads hold what they
   read — a disk or heap atom's deref pins its domain and answers the lazy, slab-backed value; a network atom's keeps
   its lease — until the outermost `$` exits, when the body's result has been materialized and everything held is let
   go together, last held first released. A `$` inside another joins it.

   Portable cljc; what a read holds is its namespace's own (`hold!`).")

(def ^:dynamic *region*
  "The read region of this thread's running `$`: {:held (atom [[k release] ...])}, or nil outside one."
  nil)

(defn active?
  "Is this thread inside a `$`?"
  []
  (some? *region*))

(defn hold!
  "Inside a `$`: (acquire) the first time k (compared by identity) is held in this region, and (release) once at the
   region's exit; answers true. Outside one: nothing done, nil."
  [k acquire release]
  (when-let [{:keys [held]} *region*]
    (when-not (some (fn [[k' _]] (identical? k k')) @held)
      (acquire)
      (swap! held conj [k release]))
    true))

(defn on-exit!
  "Inside a `$`: (release) run at the region's exit, with its holds; answers true. Outside one: nothing done, nil."
  [release]
  (when-let [{:keys [held]} *region*]
    (swap! held conj [release release])
    true))

(defn run
  "(f) as a read region: its result passed through materialize while everything read is still held, then every hold
   released (when f throws too). Inside a region already, (f) joins it: the outer region materializes and releases."
  [f materialize]
  (if *region*
    (f)
    (let [region {:held (atom [])}]
      (try
        (binding [*region* region]
          (materialize (f)))
        (finally
          (doseq [[_ release] (rseq @(:held region))]
            (release)))))))
