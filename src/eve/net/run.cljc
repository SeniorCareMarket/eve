(ns eve.net.run
  "The sans-IO driver (eve/doc/network-atoms/IMPL-PLAN.md §6.0, §9 A11): eve.net's protocol is written as STEPS — pure
   data naming the next effect and how to continue — and this namespace runs them over a host's PLATFORM map. A host
   supplies only effects; the protocol is eve's.

   A step is one of
     {:done v}                      the result
     {:request req :k (fn [resp])}  an HTTP request — req {:method :url :headers :body :as :body-as}, answered
                                    {:status :headers :body} (:as :b64: the body as base64 text; :body-as :b64: the
                                    request body is base64 text of the bytes to send — vs.host's fetch convention;
                                    :as :bytes / :body-as :bytes: the host's byte array, eve.encoding's, which
                                    eve.codec reads and writes — no text between them)
     {:requests [req …] :k (fn [resps])}  requests answered together, a vector of answers in their order — the
                                    platform's :request-all runs them concurrently (the prefetch's parallelism:
                                    eve/doc/network-atoms/ADAPTERS.md §4.2), else :request runs each in turn; either
                                    way the call returns only when every answer is in
     {:sleep ms :k (fn [])}         a wait (the backoff)
   and :k answers the next step.

   The platform: {:request (fn [req] resp) :sleep (fn [ms]) :now-ms (fn []) :seed n}, and optionally :request-all
   (fn [reqs] resps), SYNCHRONOUS on every host. A
   network atom's deref and swap! answer the value, never a promise, on every Clojure-like — ClojureScript's threading
   semantics rest on that, and so does the block tier's prefetch (the fetch-on-miss and its sibling batch run inside
   a read). A host whose only fetch is asynchronous blocks the calling thread in its http adapter: Node's
   (adapters/http/node) runs the fetch on a worker thread and parks the caller on Atomics.wait, as production's
   future_fetch did. `run` is a loop: no stack grows.")

(defn done [v] {:done v})
(defn request [req k] {:request req :k k})
(defn requests [reqs k] {:requests (vec reqs) :k k})
(defn sleep [ms k] {:sleep ms :k k})

(defn then
  "the step that runs `step`, then (f its value) — f answers a step"
  [step f]
  (cond (contains? step :done) (f (:done step))
        (contains? step :request) {:request (:request step) :k (fn [r] (then ((:k step) r) f))}
        (contains? step :requests) {:requests (:requests step) :k (fn [rs] (then ((:k step) rs) f))}
        (contains? step :sleep) {:sleep (:sleep step) :k (fn [] (then ((:k step)) f))}
        :else (throw (ex-info "eve.net.run: not a step" {:step step}))))

(defn fmap "the step whose value is (f the value of step)" [step f] (then step (fn [v] (done (f v)))))

(defn- request-all [platform reqs]
  (if-let [ra (:request-all platform)] (vec (ra reqs)) (mapv (:request platform) reqs)))

(defn run
  "runs the step over the platform and answers its value"
  [platform step]
  (loop [s step]
    (cond (contains? s :done) (:done s)
          (contains? s :request) (recur ((:k s) ((:request platform) (:request s))))
          (contains? s :requests) (recur ((:k s) (request-all platform (:requests s))))
          (contains? s :sleep) (do ((:sleep platform) (:sleep s)) (recur ((:k s))))
          :else (throw (ex-info "eve.net.run: not a step" {:step s})))))

;; ── the seeded PRNG (the backoff's jitter): xorshift32 over the host's seed — portable, no host random ───────────
(defn rng "a PRNG state from a seed (any integer; 0 is moved off zero)" [seed]
  (let [s (mod seed 4294967296)] (if (zero? s) 2463534242 s)))

(defn- xs32 [x]
  (let [m (fn [v] (mod v 4294967296))
        x (m (bit-xor x (m (* x 8192))))                 ;; x ^= x << 13
        x (m (bit-xor x (quot x 131072)))                ;; x ^= x >> 17
        x (m (bit-xor x (m (* x 32))))]                  ;; x ^= x << 5
    x))

(defn next-double "[a double in [0, 1), the next state]" [state]
  (let [x (xs32 state)] [(/ x 4294967296.0) x]))
