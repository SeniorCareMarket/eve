(ns probe-nbb-clock
  "Discriminator 5: what does an Eve atom holding a long actually
   deref to on the nbb seat, before and after (swap! a inc)?"
  (:require [eve.process-slab :as ps]
            [eve.atom :as ea]))

(defn- run [root]
  (let [cd (ea/persistent-atom-domain (str root "/clock") :durable? false)
        clock (ea/lookup-or-create-mmap-atom! cd "eve.stm/clock" 0)]
    (println "C initial:" (pr-str @clock) "type:" (pr-str (type @clock))
             "number?" (number? @clock) "=0?" (= @clock 0)) (flush)
    (let [res (swap! clock inc)]
      (println "C swap-inc returned:" (pr-str res) "type:" (pr-str (type res))) (flush))
    (println "C after:" (pr-str @clock) "type:" (pr-str (type @clock))
             "number?" (number? @clock) "=1?" (= @clock 1)) (flush)
    (let [res2 (swap! clock (fn [x] (+ x 1)))]
      (println "C swap +1 returned:" (pr-str res2)) (flush))
    (println "C after2:" (pr-str @clock) "=2?" (= @clock 2)) (flush))
  (js/process.exit 0))

(defn -main [& _args]
  (-> (ps/init! :force true)
      (.then (fn [_] (run (first _args))))
      (.catch (fn [e] (println "PROBE-NBB-CLOCK ERROR:" e) (js/process.exit 1)))))
