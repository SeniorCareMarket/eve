;; jvm_varhandle.clj — the JVM region's atomic i32 access (adapters/region/jvm-varhandle): ns a call through
;; eve.mem/-load-i32 and -cas-i32! on a heap region (the spread handles), and a volatile get through the same
;; VarHandle's method handle by invokeWithArguments (what the spread handles replaced). 2M calls a round after 2M of
;; warm-up; the median of 5 rounds. doc/hosts/jvm.md, CHANGELOG.md, the region's comment.
(require '[eve.mem :as mem] '[eve.region :as region])
(import '[java.lang.invoke MethodHandles VarHandle VarHandle$AccessMode] '[java.nio ByteBuffer ByteOrder])
(def r (region/make-heap-region 4096))
(def n 2000000)
(defn median-ns [f]
  (f) ; warm-up
  (let [ts (sort (repeatedly 5 #(let [t0 (System/nanoTime)] (f) (- (System/nanoTime) t0))))]
    (/ (double (nth ts 2)) n)))
(def vh (MethodHandles/byteBufferViewVarHandle (class (int-array 0)) ByteOrder/LITTLE_ENDIAN))
(def mh (.toMethodHandle ^VarHandle vh VarHandle$AccessMode/GET_VOLATILE))
(def bb (ByteBuffer/wrap (byte-array 4096)))
(println (format "eve.mem/-load-i32 (volatile get, spread handle): %.1f ns"
                 (median-ns #(loop [i 0 s 0] (if (< i n) (recur (inc i) (+ s (long (mem/-load-i32 r 64)))) s)))))
(println (format "eve.mem/-cas-i32! (spread handle): %.1f ns"
                 (median-ns #(loop [i 0] (when (< i n) (mem/-cas-i32! r 64 i (inc i)) (recur (inc i)))))))
(println (format "volatile get by invokeWithArguments: %.1f ns"
                 (median-ns #(loop [i 0 s 0] (if (< i n) (recur (inc i) (+ s (long (.invokeWithArguments ^java.lang.invoke.MethodHandle mh ^java.util.List (java.util.Arrays/asList (object-array [bb (int 64)])))))) s)))))
(println "java" (System/getProperty "java.version"))
