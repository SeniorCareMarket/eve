(ns contract.one-aot
  "The AOT entry for the DataScript shootout contract (compiled-jank lane).
   eve.host-completion loads first -- every module analyzed after it sees
   the names the completion interns into clojure.core (the eve.runner.jank-main
   / contract.jank-main launcher pattern, in its AOT form) -- then the
   contract. The process ends through eve.process/exit!: the exit hooks run
   (the domain's close), the output is flushed, and _exit ends the heartbeat
   timer thread, which crashes jank's own teardown when alive at a normal end.
   A thrown value out of the contract is printed and the exit status is 1.

     target/one <fresh-db-dir>          the contract (the shipped form)
     target/one --mat <fresh-db-dir>    the same job with residency marks (contract.one-mat)
     target/one --open-only <db-dir>    open/create + the empty DB, no transact (the journal proof)
     target/one --readback <db-dir>     reopen + count the datoms, no transact
     target/one --desc16k <fresh-db-dir> the contract with the domain at 16,384 descriptors (the CLJS seat's count)"
  (:require [eve.host-completion]
            [eve.process :as proc]
            [contract.one]
            [contract.one-mat]))

(defn -main [& args]
  (let [code (try (let [[flag dir] args]
                    (cond (= "--mat" flag) (contract.one-mat/-main dir)
                          (= "--open-only" flag) (contract.one-mat/open-only dir)
                          (= "--readback" flag) (contract.one-mat/readback dir)
                          (= "--desc16k" flag) (contract.one-mat/one-16k dir)
                          :else (apply contract.one/-main args)))
                  0
                  (catch jank.runtime.object_ref e
                    (println (pr-str {:mark :ONE-AOT-ERROR :error (ex-message e) :data (ex-data e)}))
                    1))]
    (proc/exit! code)))
