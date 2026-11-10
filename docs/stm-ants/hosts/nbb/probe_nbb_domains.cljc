(ns probe-nbb-domains
  "Discriminator 7 (F-ANTS-2 characterization): open three
   persistent domains in sequence; after each open, re-read the
   earlier domains' atoms — both through the original atom object
   and through a fresh lookup-or-create for the same name."
  (:require [eve.process-slab :as ps]
            [eve.atom :as ea]))

(defn- run [root]
  (let [d0 (ea/persistent-atom-domain (str root "/d0") :durable? false)
        a0 (ea/lookup-or-create-mmap-atom! d0 "probe/a" 0)]
    (println "E d0 opened; a0=" (pr-str @a0)) (flush)
    (let [d1 (ea/persistent-atom-domain (str root "/d1") :durable? false)
          a1 (ea/lookup-or-create-mmap-atom! d1 "probe/a" 100)]
      (println "E d1 opened; a1=" (pr-str @a1)
               " a0=" (pr-str @a0)
               " a0-fresh=" (pr-str @(ea/lookup-or-create-mmap-atom! d0 "probe/a" 0)))
      (flush)
      (let [d2 (ea/persistent-atom-domain (str root "/d2") :durable? false)
            a2 (ea/lookup-or-create-mmap-atom! d2 "probe/a" 200)]
        (println "E d2 opened; a2=" (pr-str @a2)
                 " a1=" (pr-str @a1)
                 " a0=" (pr-str @a0)
                 " a0-fresh=" (pr-str @(ea/lookup-or-create-mmap-atom! d0 "probe/a" 0))
                 " a1-fresh=" (pr-str @(ea/lookup-or-create-mmap-atom! d1 "probe/a" 100)))
        (flush))))
  (js/process.exit 0))

(defn -main [& _args]
  (-> (ps/init! :force true)
      (.then (fn [_] (run (first _args))))
      (.catch (fn [e] (println "PROBE-NBB-DOMAINS ERROR:" e) (js/process.exit 1)))))
