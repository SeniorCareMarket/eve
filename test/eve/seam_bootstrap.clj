;; Run in its own process on bb and bb-minimal, before eve.core is loaded:
;; bb -cp "$(bb classpath bb)" test/eve/seam_bootstrap.clj
(require '; required for load as part of this namespace's cone (Eve's type/protocol registrations are load-time effects); no vars are referenced from it here 
          #_{:clj-kondo/ignore [:unused-namespace]}   [eve.crypto :as c] '; required for load as part of this namespace's cone (Eve's type/protocol registrations are load-time effects); no vars are referenced from it here 
                                                                          #_{:clj-kondo/ignore [:unused-namespace]}   [eve.hamt-util :as hu] '; required for load as part of this namespace's cone (Eve's type/protocol registrations are load-time effects); no vars are referenced from it here 
                                                                                                                                              #_{:clj-kondo/ignore [:unused-namespace]}   [eve.thread-host :as th])
(defn seam-startup [] (nth (sort [3 1 2]) 1))
(assert (= 2 (seam-startup)))
(assert (some? (resolve 'eve.core/nth)))
(println :nth-bootstrap-pass)
