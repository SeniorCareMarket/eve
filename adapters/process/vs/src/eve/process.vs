(ns eve.process
  "eve's process primitives (HOST-CONTRACT §5) over ValueScript's: the clock, sleep, and the running thread's identity
   (vs.thread/worker-id: 0 on the main thread, a mesh worker its id — answered + 1, so no thread is 0). Files
   over the runtime's wasi/path-* arms (file-exists?, create-exclusive!, ensure-parent-dir!). No timer
   thread and no exit hook: the fixed WASI surface has no timer or exit-hook arm, and a mesh's workers are
   a build-time shape, not a runtime spawn; standard input's lines (WASI fd 0).

   random-bytes is the language's own generator (vs.core's RANDOM rows, core.vs: xorshift128 seeded from the clocks,
   \"not cryptographically strong\"): a compiled VS program has no entropy source of its own. It serves the reference
   table's realm (FORMAT-DECISIONS §9: told apart from another process's); it is no source for eve.keyring's keys."
  (:refer-clojure :exclude [read-line])
  (:require [clojure.string :as str]
            [vs.thread :as th]))

(defn now-ms [] (System/currentTimeMillis))
(defn thread-id [] (inc (th/worker-id)))
(defn sleep-ms! [ms] (Thread/sleep ms))
(defn start-timer! [period-ms f] nil)
(defn stop-timer! [h] nil)
(defn on-process-exit! [f] nil)

;; ── files: the program runtime's wasi/path-* arms (the same arms
;; java.io.File is written over; a success answers a value, a failure
;; the WASI errno as a number — 44 ENOENT, 20 EXIST). Paths resolve in
;; the program's first preopened directory, a leading / stripped.

(defn file-exists? [path]
  (not (number? (wasi/path-filestat path))))

(defn create-exclusive!
  "Create the file at path, empty, if no file is there — one atomic step
   against every other process (wasi/path-write's create-exclusive mode):
   true for the process that created it, false when it already existed."
  [path]
  (let [r (wasi/path-write path "" 2)]
    (cond (zero? r) true
          (= 20 r) false
          :else (throw (ex-info (str "eve.process/create-exclusive!: WASI errno " r
                                     " creating " path)
                                {:path path :errno r})))))

(defn ensure-parent-dir!
  "Create the directory a domain's files live in: the parent of
   (str base-path \".root\"), segment by segment (an EXIST answer is the
   already-there case, ignored — the calls that need the directory
   surface any real failure)."
  [base-path]
  (let [parent (str/join "/" (drop-last (str/split (str base-path ".root") #"/")))]
    (when (seq parent)
      (loop [acc nil segs (filter seq (str/split parent #"/"))]
        (when (seq segs)
          (let [dir (if acc (str acc "/" (first segs)) (first segs))]
            (wasi/path-create-directory dir)
            (recur dir (rest segs)))))))
  nil)
(defn identity-hash
  "An object's identity hash: equal for one object, whatever its own equality or hash — what eve's reference table
   keys by (FORMAT-DECISIONS.md §9)."
  [x]
  ;; a referenced value is a block of the program's memory (a fn, a regex, a var, a volatile, an atom): its address
  ;; names it while it lives, and the reference table holds it for the process's life (eve.host/locking keys by the
  ;; same address)
  (vs.core/rt-addr x))
(defn random-bytes
  "n bytes of the language's generator (see the namespace doc), as a vector of 0..255."
  [n]
  (vec (repeatedly n (fn [] (rand-int 256)))))

(def ^:private stdin-buf (atom ""))   ;; text read from fd 0 past the last line answered

(defn read-line
  "the next line of the process's standard input, its line end dropped; nil at the end of the input. Blocking: WASI's
   fd_read on fd 0, 64 KB at a time"
  []
  (loop []
    (let [s (deref stdin-buf)
          i (str/index-of s "\n")]
      (if (some? i)
        (let [l (subs s 0 i)]
          (reset! stdin-buf (subs s (inc i)))
          (if (str/ends-with? l "\r") (subs l 0 (dec (count l))) l))
        (let [chunk (wasi/fd-read 0 65536)]
          (if (or (nil? chunk) (= "" chunk))
            (when (seq s) (reset! stdin-buf "") s)
            (do (reset! stdin-buf (str s chunk)) (recur))))))))
