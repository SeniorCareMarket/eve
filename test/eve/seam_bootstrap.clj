;; Run in its own process on bb and bb-minimal, before eve.core is loaded:
;; bb -cp "$(bb classpath bb)" test/eve/seam_bootstrap.clj
(require '[eve.crypto :as c] '[eve.hamt-util :as hu] '[eve.thread-host :as th])
(defn seam-startup [] (nth (sort [3 1 2]) 1))
(assert (= 2 (seam-startup)))
(assert (some? (resolve 'eve.core/nth)))
(println :nth-bootstrap-pass)
