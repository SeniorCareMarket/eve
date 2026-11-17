(ns eve.sab
  "ClojureScript's SharedArrayBuffers as eve's atoms hold them: the buffers
   to hand a worker, the views a worker builds over them, and the module
   slab's allocator as the eve.shared-atom test surface called it (get-env,
   alloc, free)."
  (:require [eve.atom :as a]
            [eve.deftype-proto.alloc :as alloc]
            [eve.deftype-proto.data :as d]
            [eve.mem :as mem]
            [eve.process-slab :as ps]
            [eve.wasm-mem :as wasm-mem]))

(defn- slab-sab
  "Slab class 0's SharedArrayBuffer, or nil."
  []
  (let [sabs (try (ps/get-all-slab-sabs) (catch :default _ nil))]
    (when (and sabs (pos? (alength sabs))) (aget sabs 0))))

(defn get-env
  "The domain-state map of an atom, a domain, or the map itself, with :sab
   (slab class 0's SAB) for callers that used eve.shared-atom/get-env."
  [obj]
  (let [ds (if (map? obj) obj (a/-domain-state obj))
        sab (slab-sab)]
    (if sab (assoc ds :sab sab) ds)))

(defn alloc
  "Allocate size-bytes in the module slab: {:offset :descriptor-idx}."
  [_env size-bytes]
  (let [result (ps/alloc size-bytes)]
    (if (:error result)
      result
      (let [off (:offset result)]
        {:offset off :descriptor-idx off}))))

(defn free
  "Free a module-slab block."
  [_env slab-offset]
  (ps/free! slab-offset))

(defn init-worker-cache!
  "A worker's views over the slabs it was handed."
  [_env]
  (when-let [sab (slab-sab)]
    (wasm-mem/init-views-from-sab! sab)))

(defn sab-transfer-data
  "The SABs to hand a worker: :sab (class 0), :reader-map-sab (the domain's
   reader map, given an atom or a domain), :slab-sabs, :root-sab."
  ([] (sab-transfer-data nil))
  ([obj]
   (let [ds (when (and obj (satisfies? a/IEveAtom obj)) (a/-domain-state obj))
         rmap-sab (when-let [rmap-r (:rmap-r ds)] (.-sab ^js rmap-r))]
     {:sab (slab-sab)
      :reader-map-sab rmap-sab
      :slab-sabs (ps/get-all-slab-sabs)
      :root-sab (ps/get-root-sab)})))

;; ---------------------------------------------------------------------------
;; Cross-worker watch signaling — the adapter side of the trunk watch
;; spec (clause 5). The trunk owns watchers and notification; the
;; wait/notify machinery is here, on the region's side of the seam.
;; A successful swap bumps the root epoch word before it publishes, so
;; the epoch word is the domain's change signal: signaling is waking
;; its waiters, and waiting is Atomics.waitAsync on the same word.
;; ---------------------------------------------------------------------------

(defn signal-watchers!
  "Wake every worker waiting on this domain's root epoch word
   (eve.mem/-notify-i32! on the root region). No-op when the root
   cannot back a watch loop. Returns the number of waiters woken,
   or nil."
  [ds]
  (let [root-r (:root-r ds)]
    (when (and root-r (mem/-supports-watch? root-r))
      (mem/-notify-i32! root-r d/ROOT_EPOCH_OFFSET 2147483647))))

(defn install-watch-signaling!
  "Install the trunk notify listener (eve.atom/register-notify-listener!)
   that signals cross-worker watchers on every successful swap in a
   SAB-backed domain. Call once in each process that swaps."
  []
  (a/register-notify-listener!
   (fn [ds _slot _old _new] (signal-watchers! ds))))

(defn wait-for-change
  "A promise for the domain's next change: Atomics.waitAsync on the
   root epoch word — resolves :ok when a swap bumps it, :not-equal
   when the epoch already moved past the read. A waiting worker
   re-derefs on wake; the epoch is domain-wide, so a wake means some
   atom in the domain changed. nil when this root cannot back a
   watch loop (no SAB, or no Atomics.waitAsync on this host)."
  [ds]
  (let [root-r (:root-r ds)]
    (when (and root-r (.-sab ^js root-r) (exists? js/Atomics.waitAsync))
      (let [ia (js/Int32Array. (.-sab ^js root-r))
            idx (quot d/ROOT_EPOCH_OFFSET 4)
            seen (js/Atomics.load ia idx)
            r (js/Atomics.waitAsync ia idx seen)]
        (if (.-async r)
          (.then (.-value r) (fn [v] (keyword v)))
          (js/Promise.resolve (keyword (.-value r))))))))
