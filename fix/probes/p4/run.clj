(require 'datascript.test.lru)
(let [{:keys [fail error] :as res} (clojure.test/run-tests 'datascript.test.lru)]
  (println :RESULT res)
  (let [l (datascript.lru/lru 2) l2 (assoc l :a 1)]
    (doseq [[label thunk] {:count #(count l2) :seq #(seq l2) :empty #(empty l2)
                           :map? #(map? l2) :type #(type l2)}]
      (println label (try (thunk) (catch Throwable t (str "THREW " (ex-message t)))))))
  (System/exit (if (zero? (+ fail error)) 0 1)))
