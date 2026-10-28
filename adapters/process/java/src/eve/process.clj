(ns eve.process
  "eve's process primitives (HOST-CONTRACT §5) over the JVM's java.lang (the JVM, babashka, jolt): the clock, a
   thread's identity, sleep, a periodic timer, a hook at normal exit, and
   files' existence, their exclusive creation and directories, and standard input's lines."
  (:refer-clojure :exclude [read-line]))

(defn now-ms []
  (System/currentTimeMillis))

(defn thread-id
  "The identity a pinned epoch is kept under (threads share a worker slot)."
  []
  (.getId (Thread/currentThread)))

(defn sleep-ms!
  "Block this thread for about ms milliseconds."
  [ms]
  (Thread/sleep (long ms)))

(defn start-timer!
  "Call f every period-ms until stop-timer!. Returns the host's handle."
  [period-ms f]
  (let [t (Thread. ^Runnable
                  (fn []
                    (loop []
                      (when (try (Thread/sleep (long period-ms))
                                 true
                                 (catch InterruptedException _ false))
                        (f)
                        (recur)))))]
    (.setDaemon t true)
    (.setName t "eve-heartbeat")
    (.start t)
    t))

(defn stop-timer! [h]
  (when h
    (.interrupt ^Thread h)))

(defn on-process-exit!
  "Run f when the process exits normally."
  [f]
  (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable f)))

(defn file-exists? [path]
  (.exists (java.io.File. ^String path)))

(defn create-exclusive!
  "Create the file at path, empty, if no file is there — one atomic step
   against every other process: true for the process that created it, false
   when it already existed (an mmap domain's creator is the one that gets true)."
  [path]
  (.createNewFile (java.io.File. ^String path)))

(defn ensure-parent-dir!
  "Create the directory a domain's files live in (the default domain's ./eve/)."
  [base-path]
  (let [root-file (str base-path ".root")]
    (when-let [dir (.getParentFile (java.io.File. ^String root-file))]
      (when-not (.exists dir) (.mkdirs dir)))))

(def ^:private rng (delay (java.security.SecureRandom.)))

(defn identity-hash
  "An object's identity hash: equal for one object, whatever its own equality
   or hash (a host object may answer none) — what eve's reference table keys
   by (FORMAT-DECISIONS.md §9)."
  [x]
  (System/identityHashCode x))

(defn random-bytes
  "n bytes from the operating system's entropy (java.security.SecureRandom), as a vector of 0..255 — the one crypto
   input no portable code can supply (eve.crypto/random-bytes)"
  [n]
  (let [a (byte-array n)]
    (.nextBytes ^java.security.SecureRandom @rng a)
    (mapv (fn [b] (bit-and b 255)) a)))

(defn read-line
  "the next line of the process's standard input, its line end dropped; nil at the end of the input. Blocking"
  []
  (clojure.core/read-line))
