(import '[java.nio.channels FileChannel] '[java.nio.file Paths StandardOpenOption])
(let [fc (FileChannel/open (Paths/get (first *command-line-args*) (into-array String []))
                           (into-array StandardOpenOption [StandardOpenOption/READ StandardOpenOption/WRITE]))
      fl (try (.tryLock fc) (catch Exception e (str "EXC " e)))]
  (println "JAVA-TRYLOCK:" (if fl "ACQUIRED (no exclusion!)" "BLOCKED/DENIED")) (flush)
  (System/exit 0))
