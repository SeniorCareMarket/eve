;; R9 acceptance: Transit walks flat objects nested in host
;; data. The far side must receive the logical content,
;; content-equal as plain data.
(require '[eve.flat.freeze :as freeze] '[eve.flat.corpora :as corpora]
         '[eve.flat.transit :as ft]
         '[cognitect.transit :as transit])
(import '[java.io ByteArrayOutputStream ByteArrayInputStream])

(def rows (vec (take 1500 (:records-10k (into {} (corpora/corpora :canonical))))))
(def dv (freeze/deliver-flat rows))
(defn round-trip [x]
  (let [out (ByteArrayOutputStream.)]
    (transit/write (transit/writer out :json) x)
    (transit/read (transit/reader (ByteArrayInputStream. (.toByteArray out)) :json))))
(def host {:data dv :n 5 :label "x"})
(def back (try (round-trip host) (catch Throwable t (str "THREW: " (.getMessage t)))) )
(println :transit {:dv-class (class dv)
                   :back-class (class back)
                   :msg (when (string? back) back) :equal (= back {:data rows :n 5 :label "x"})
                   :data-count (when (map? back) (count (:data back)))
                   :row700 (when (map? back) (:name (nth (:data back) 700)))})
(def deep {:outer [{:inner dv}]})
(def back2 (try (round-trip deep) (catch Throwable t (str "THREW: " t))))
(println :transit-deep {:equal (= back2 {:outer [{:inner rows}]})})

;; with eve.flat.transit handlers: plain flavor
(defn round-trip-h [x wopts ropts]
  (let [out (ByteArrayOutputStream.)]
    (transit/write (transit/writer out :json wopts) x)
    (transit/read (transit/reader (ByteArrayInputStream. (.toByteArray out)) :json ropts))))
(def backp (try (round-trip-h host {:handlers (ft/plain-write-handlers)} {})
                (catch Throwable t (str "THREW: " (pr-str (Throwable->map t))))))
(println :transit-plain-handlers {:equal (= backp {:data rows :n 5 :label "x"})
                                  :data-class (when (map? backp) (class (:data backp)))
                                  :msg (when (string? backp) backp)})
;; flat flavor: ships as flat, reopens as flat
(def backf (try (round-trip-h host {:handlers (ft/flat-write-handlers)}
                              {:handlers (ft/flat-read-handlers)})
                (catch Throwable t (str "THREW: " (.getMessage t)))))
(println :transit-flat-handlers {:equal (= backf host)
                                 :data-class (when (map? backf) (class (:data backf)))
                                 :data-equal (when (map? backf) (= (:data backf) dv))
                                 :msg (when (string? backf) backf)})
(println :DONE)
(System/exit 0)
