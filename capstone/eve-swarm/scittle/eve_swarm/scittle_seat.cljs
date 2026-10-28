(ns eve-swarm.scittle-seat
  "The eve-swarm seat on Scittle (SCI in a browser, babashka/scittle; capstone/eve-swarm): eve's chat seat
   (eve.aegis.chat.seat) read from source (eve-swarm.scittle-load) and interpreted by Scittle in a page's dedicated
   worker, over eve's browser adapters (hosts.edn :scittle) — a worker, as a page's main thread cannot block
   (Atomics.wait throws there, and the client's backoff after a lost race sleeps) and a worker's XMLHttpRequest may be
   synchronous, as eve's http adapter's is.

   The seat's line protocol is a function here: globalThis.eveSeat takes one command line (JSON) and answers the
   answer's JSON — eve.aegis.chat.seat/step on this worker's session — which test/aegis/e2e_rows/swarm.py's PageSeat
   calls through Playwright."
  (:require [eve.aegis.chat.seat :as seat]
            [eve.net.json :as json]))

(def ^:private session (atom nil))

(defn answer
  "one command line (JSON) → its answer (JSON)"
  [line]
  (let [[s a] (seat/step @session (json/read-str line))]
    (reset! session s)
    (json/write-str a)))

(aset js/globalThis "eveSeat" answer)
