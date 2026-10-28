(ns eve.process
  "eve's process primitives (HOST-CONTRACT §5) over Node's (ClojureScript, nbb): the clock, a
   thread's identity, sleep, a periodic timer, a hook at normal exit, and
   files' existence, their exclusive creation and directories, and standard input's lines."
  (:refer-clojure :exclude [read-line]))

(defn now-ms []
  (js/Date.now))

(defn thread-id
  "The identity a pinned epoch is kept under (threads share a worker slot)."
  []
  0)

;; A 4-byte SAB for Atomics.wait: a sub-ms sleep on the main thread.
(def ^:private sleep-i32 (js/Int32Array. (js/SharedArrayBuffer. 4)))

(defn sleep-ms!
  "Block this thread for about ms milliseconds."
  [ms]
  (js/Atomics.wait sleep-i32 0 0 ms))

(defn start-timer!
  "Call f every period-ms until stop-timer!. Returns the host's handle. The timer never keeps the process (or a
   worker) alive, as the JVM's daemon timer thread does not: an unref'd interval, where the host has unref (Node;
   a browser's interval is a number)."
  [period-ms f]
  (let [h (js/setInterval f period-ms)]
    (when (some? (.-unref h)) (.unref h))
    h))

(defn stop-timer! [h]
  (when h
    (js/clearInterval h)))

(defn on-process-exit!
  "Run f when the process exits normally."
  [f]
  (.on js/process "exit" (fn [_] (f))))

(defn file-exists? [path]
  (.existsSync (js/require "fs") path))

(defn create-exclusive!
  "Create the file at path, empty, if no file is there — one atomic step
   against every other process: true for the process that created it, false
   when it already existed (an mmap domain's creator is the one that gets true).
   Here: open \"wx\": O_CREAT|O_EXCL."
  [path]
  (let [fs (js/require "fs")]
    (try (.closeSync fs (.openSync fs path "wx")) true
         (catch :default e
           (if (= "EEXIST" (.-code e)) false (throw e))))))

(defn ensure-parent-dir!
  "Create the directory a domain's files live in (the default domain's ./eve/)."
  [base-path]
  (let [root-file (str base-path ".root")]
    (let [path (js/require "path")
          fs (js/require "fs")
          dir (.dirname path root-file)]
      (when-not (.existsSync fs dir)
        (.mkdirSync fs dir #js {:recursive true})))))

(defn identity-hash
  "An object's identity hash: equal for one object, whatever its own equality
   or hash (a host object may answer none) — what eve's reference table keys
   by (FORMAT-DECISIONS.md §9)."
  [x]
  ;; cljs.core/hash of an object with no IHash of its own is its goog uid
  (hash x))

(defn random-bytes
  "n bytes from the operating system's entropy (the Web Crypto getRandomValues Node and every browser carry, which is
   synchronous), as a vector of 0..255 — the one crypto input no portable code can supply (eve.crypto/random-bytes)"
  [n]
  (let [a (js/Uint8Array. n)]
    ;; getRandomValues fills at most 65,536 bytes a call
    (loop [off 0]
      (when (< off n)
        (.getRandomValues js/crypto (.subarray a off (min n (+ off 65536))))
        (recur (+ off 65536))))
    (vec (js/Array.from a))))

(def ^:private stdin-buf (atom nil))   ;; bytes read from fd 0 past the last line answered (a Buffer)

(defn read-line
  "the next line of the process's standard input, its line end dropped; nil at the end of the input. Blocking: fd 0
   read with readSync (a non-blocking descriptor's EAGAIN waited out), split at the newline BYTE so a multi-byte
   character is never cut"
  []
  (let [fs (js/require "fs")]
    (loop []
      (let [buf (or (deref stdin-buf) (js/Buffer.alloc 0))
            i (.indexOf buf 10)]
        (if (>= i 0)
          (let [line (.toString (.subarray buf 0 i) "utf8")]
            (reset! stdin-buf (.subarray buf (inc i)))
            (if (.endsWith line "\r") (.slice line 0 -1) line))
          (let [chunk (js/Buffer.alloc 65536)
                n (try (.readSync fs 0 chunk 0 65536 nil)
                       (catch :default e (if (= "EAGAIN" (.-code e)) -1 (throw e))))]
            (cond
              (neg? n) (do (sleep-ms! 5) (recur))
              (zero? n) (when (pos? (.-length buf)) (reset! stdin-buf (js/Buffer.alloc 0)) (.toString buf "utf8"))
              :else (do (reset! stdin-buf (js/Buffer.concat (array buf (.subarray chunk 0 n)))) (recur)))))))))
