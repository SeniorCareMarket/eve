(ns eve.http
  "eve's http feature (adapters/README.md) over Node's fetch — ClojureScript on Node and nbb. Node's only fetch is
   asynchronous, and eve.net's request is SYNCHRONOUS on every host (eve.net.run: deref and swap! answer the value,
   never a promise, and the block tier's prefetch runs inside a read), so this adapter blocks the calling thread the
   way production's future_fetch did: the fetches run on a worker thread, the caller parks on Atomics.wait, and the
   answers come back through receiveMessageOnPort. `request-all` hands the worker a batch, which it runs at once
   (Promise.all, as production's batch-blob-get) behind the one wait. The body conventions are eve.net.run's:
   :body-as :b64 sends the bytes its base64 :body names, :body-as :bytes the byte array (a Uint8Array) :body is; :as
   :b64 answers the response body as base64, :as :bytes as a Uint8Array (eve.encoding's, which eve.codec reads);
   otherwise bodies are UTF-8 text. A transport failure, or no answer within TIMEOUT-MS, answers {:error message}, as
   eve.net.wire reads it."
  (:require ["worker_threads" :as wt]))

(def TIMEOUT-MS 120000)

;; the worker's body: Node runs a worker from source text, so the fetch it makes is written in the worker's language
(def ^:private WORKER
  "const { parentPort } = require('worker_threads');
async function one(req) {
  try {
    const body = req.body == null ? undefined : (req.b64 ? Buffer.from(req.body, 'base64') : req.body);
    const r = await fetch(req.url, { method: req.method, headers: req.headers, body });
    const buf = Buffer.from(await r.arrayBuffer());
    const headers = {};
    r.headers.forEach((v, k) => { headers[k] = v; });
    return { status: r.status, headers,
             body: req.as64 ? buf.toString('base64') : (req.asBytes ? new Uint8Array(buf) : buf.toString('utf8')) };
  } catch (e) {
    return { error: String((e && e.cause && e.cause.message) || (e && e.message) || e) };
  }
}
parentPort.on('message', async ({ reqs, port, flag }) => {
  const out = await Promise.all(reqs.map(one));
  port.postMessage(out);
  port.close();
  const f = new Int32Array(flag);
  Atomics.store(f, 0, 1);
  Atomics.notify(f, 0);
});")

(def ^:private worker
  (delay (let [W (.-Worker wt)
               w (new W WORKER #js {:eval true})]
           (.unref w)
           w)))

(defn- js-req [{:keys [method url headers body as body-as]}]
  #js {:method (or method "GET") :url url
       :headers (clj->js (into {} (map (fn [[k v]] [(name k) (str v)]) headers)))
       :body body :b64 (= :b64 body-as) :as64 (= :b64 as) :asBytes (= :bytes as)})

(defn- answer [m]
  (if (.-error m)
    {:error (.-error m)}
    {:status (.-status m)
     :headers (into {} (map (fn [k] [k (unchecked-get (.-headers m) k)]) (js/Object.keys (.-headers m))))
     :body (.-body m)}))

(defn request-all
  "every request run at once on the worker (Promise.all); the caller blocks until all are in"
  [reqs]
  (if (empty? reqs)
    []
    (let [flag (js/SharedArrayBuffer. 4)
          i32 (js/Int32Array. flag)
          ch (new (.-MessageChannel wt))
          port (.-port2 ch)]
      (.postMessage @worker #js {:reqs (into-array (map js-req reqs)) :port port :flag flag} #js [port])
      (let [waited (js/Atomics.wait i32 0 0 TIMEOUT-MS)
            got ((.-receiveMessageOnPort wt) (.-port1 ch))]
        (.close (.-port1 ch))
        (if (nil? got)
          (mapv (fn [r] {:error (str "no answer from " (:url r) " (" waited ")")}) reqs)
          (mapv answer (.-message got)))))))

(defn request [req] (first (request-all [req])))

(defn platform [] {:request request :request-all request-all})
