(ns eve.process
  "eve's process primitives (HOST-CONTRACT §5) over the :teavm seat: the
   clock, a thread's identity, sleep, a periodic timer, a hook at normal
   exit, and files' existence, their exclusive creation and directories,
   entropy, and standard input's lines.

   The routes are this seat's honest ones. The clock is TSystem's host
   import, reached by the same System/currentTimeMillis the JVM adapter
   calls; thread identity and the timer are the fork's own Thread;
   sleep is Thread/sleep, a kept-async root — it parks where the runner
   pumps teavmAsync and busy-polls in the desync'd arm. Files, entropy
   and stdin cross to the host through the eve.process import group,
   over the host's real filesystem — the fs the eve.journal imports
   serve — never the body's in-memory one: create-exclusive! is one
   O_CREAT|O_EXCL step against every other process on the host, which
   no in-body file can be. on-process-exit! is not on this seat (see
   its own note)."
  (:refer-clojure :exclude [read-line])
  (:import [EveProcess]
           [RegionBytes]))

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
  "Not on this seat: the body has no exit event to hook — the classlib's
   Runtime carries no shutdown-hook registration (javap on the stock
   classlib: exit, freeMemory, gc, totalMemory, availableProcessors,
   version — no addShutdownHook), System/exit is itself a build-time
   refusal in this tree, and the host runner owns the body's lifecycle,
   and with it the worker slot this hook would release. A hook
   registered here could never fire, so the name refuses instead of
   pretending."
  [_]
  (throw (ex-info "eve.process/on-process-exit! is not on the :teavm seat" {})))

(defn- push-name! [^String path]
  (EveProcess/nameReset)
  (let [^bytes bs (.getBytes path "UTF-8")]
    (dotimes [i (alength bs)]
      (EveProcess/nameByte (RegionBytes/get bs i)))))

(defn file-exists? [path]
  (push-name! path)
  (= 1 (EveProcess/fileExists)))

(defn create-exclusive!
  "Create the file at path, empty, if no file is there — one atomic step
   against every other process: true for the process that created it, false
   when it already existed (an mmap domain's creator is the one that gets true)."
  [path]
  (push-name! path)
  (let [r (EveProcess/fileCreateExclusive)]
    (cond (= r 1) true
          (= r 0) false
          :else (throw (ex-info "eve.process/create-exclusive!: the host could not create the file"
                                {:path path})))))

(defn ensure-parent-dir!
  "Create the directory a domain's files live in (the default domain's ./eve/)."
  [base-path]
  (let [root-file (str base-path ".root")
        i (.lastIndexOf root-file "/")]
    (when (>= i 0)
      (let [dir (subs root-file 0 (max 1 i))]
        (push-name! dir)
        (when (zero? (EveProcess/fileExists))
          (when (neg? (EveProcess/fileMkdirs))
            (throw (ex-info "eve.process/ensure-parent-dir!: the host could not create the directory"
                            {:dir dir}))))))))

(defn identity-hash
  "An object's identity hash: equal for one object, whatever its own equality
   or hash (a host object may answer none) — what eve's reference table keys
   by (FORMAT-DECISIONS.md §9)."
  [x]
  (System/identityHashCode x))

(defn random-bytes
  "n bytes from the host's entropy (the eve.process import group's fill;
   the Node host's crypto.randomBytes — the operating system's entropy),
   as a vector of 0..255 — the one crypto input no portable code can
   supply (eve.crypto/random-bytes). eve.crypto's own :teavm name stays
   a refusal until the crypto pass routes it here."
  [n]
  (EveProcess/entropyFill (int n))
  (loop [i 0 acc []]
    (if (>= i n)
      acc
      (recur (inc i) (conj acc (EveProcess/entropyByteAt (int i)))))))

(def ^:private stdin-buf (atom nil))   ;; bytes read from the host's fd 0 past the last line answered (a byte array)

(defn- newline-at [^bytes buf]
  (loop [i 0]
    (cond (>= i (alength buf)) -1
          (= 10 (RegionBytes/get buf i)) i
          :else (recur (inc i)))))

(defn read-line
  "the next line of the process's standard input, its line end dropped; nil at the end of the input. Blocking:
   the host reads its fd 0 through the import group, one byte at a time; the split is at the newline BYTE so a
   multi-byte character is never cut."
  []
  (loop []
    (let [^bytes buf (or @stdin-buf (byte-array 0))
          i (newline-at buf)]
      (if (>= i 0)
        (let [line (String. (java.util.Arrays/copyOfRange buf 0 (int i)) "UTF-8")]
          (reset! stdin-buf (java.util.Arrays/copyOfRange buf (int (inc i)) (alength buf)))
          (if (.endsWith line "\r") (.substring line 0 (dec (.length line))) line))
        (let [b (EveProcess/stdinByte)]
          (if (neg? b)
            (when (pos? (alength buf))
              (reset! stdin-buf (byte-array 0))
              (String. buf "UTF-8"))
            (let [nb (byte-array (inc (alength buf)))]
              (System/arraycopy buf 0 nb 0 (alength buf))
              (RegionBytes/put nb (alength buf) (int b))
              (reset! stdin-buf nb)
              (recur))))))))
