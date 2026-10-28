(ns eve.sab
  "ClojureScript's SharedArrayBuffers as eve's atoms hold them: the buffers
   to hand a worker, the views a worker builds over them, and the module
   slab's allocator as the eve.shared-atom test surface called it (get-env,
   alloc, free)."
  (:require [eve.atom :as a]
            [eve.deftype-proto.alloc :as alloc]
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
