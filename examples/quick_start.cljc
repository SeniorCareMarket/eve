;; examples/quick_start.cljc — the README's quick start, as a file.
;;
;;   bb -cp "$(bb classpath bb)" examples/quick_start.cljc
;;   clojure -M examples/quick_start.cljc
(require '[eve.alpha :as e] '[eve.atom :as ea] '[eve.deftype.int-map :as im])

;; an atom in this process
(def counter (e/atom ::counter {:n 0}))
(swap! counter update :n inc)
(println "counter:" @counter)                     ; {:n 1}

;; an atom in a disk domain: any process (of any host) opening the same path shares it
(def shared (e/atom {:id :app/hits :persistent "/tmp/eve-readme/db"} {:n 0}))
(swap! shared update :n inc)
(println "shared:" @shared)
(ea/close-atom-domain! (ea/persistent-atom-domain "/tmp/eve-readme/db"))

;; eve collections live in an atom's own domain: build them inside swap!
(def idx (e/atom ::index {}))
(swap! idx (fn [_] {:ids (im/int-map)}))
(println "index:" @idx)
