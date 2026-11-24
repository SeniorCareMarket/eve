(ns eve.process
  "eve's process primitives (HOST-CONTRACT §5) in a browser (ClojureScript; the OPFS disk tier spec, W6): the clock,
   a thread's identity, sleep, a periodic timer, a hook at normal exit, random bytes — as Node's adapter has them —
   and files over the origin private file system: eve.opfs's io worker, which the page mounts before its threads
   start. A file call blocks (Atomics.wait): a worker's call; the page's main thread opens no file.

   A browser has no process exit a worker sees and no standard input: on-process-exit! keeps nothing (a domain's
   durability is its journal's, forced at each commit, never an exit hook's) and read-line refuses."
  (:refer-clojure :exclude [read-line])
  (:require [eve.opfs :as opfs]))

(defn now-ms []
  (js/Date.now))

(defn thread-id
  "The identity a pinned epoch is kept under (threads share a worker slot)."
  []
  0)

;; A 4-byte SAB for Atomics.wait
;; made on first sleep: a page that is not yet cross-origin isolated has no SharedArrayBuffer at load
(def ^:private sleep-i32 (delay (js/Int32Array. (js/SharedArrayBuffer. 4))))

(defn sleep-ms!
  "Block this thread for about ms milliseconds (a worker: the page's main thread cannot block)."
  [ms]
  (js/Atomics.wait @sleep-i32 0 0 ms))

(defn start-timer!
  "Call f every period-ms until stop-timer!. Returns the host's handle (a browser's interval is a number: it never
   keeps a page alive)."
  [period-ms f]
  (js/setInterval f period-ms))

(defn stop-timer! [h]
  (when h
    (js/clearInterval h)))

(defn on-process-exit!
  "A browser worker sees no exit: f is not kept (see the namespace doc)."
  [_f]
  nil)

(defn file-exists? [path]
  (opfs/file-exists? path))

(defn file-size
  "The size in bytes of the file at path, or nil when there is none."
  [path]
  (opfs/file-size path))

(defn rename-file!
  "Rename the file at from to to (replacing a file at to); a failure throws. Neither may be open."
  [from to]
  (opfs/rename-file! from to))

(defn create-exclusive!
  "Create the file at path, empty, if no file is there: true for the caller that created it, false when it already
   existed. One step against every worker of the page (the io worker serves one request at a time); across tabs the
   domain's Web Lock decides (W7)."
  [path]
  (opfs/create-exclusive! path))

(defn ensure-parent-dir!
  "Create the directory a domain's files live in (the default domain's eve/)."
  [base-path]
  (let [root-file (str base-path ".root")
        i (.lastIndexOf root-file "/")]
    (when (pos? i)
      (opfs/ensure-dirs! (subs root-file 0 i)))))

(defn identity-hash
  "An object's identity hash: equal for one object, whatever its own equality or hash (a host object may answer
   none) — what eve's reference table keys by (FORMAT-DECISIONS.md §9)."
  [x]
  ;; cljs.core/hash of an object with no IHash of its own is its goog uid
  (hash x))

(defn random-bytes
  "n bytes from the browser's entropy (Web Crypto's getRandomValues, synchronous), as a vector of 0..255."
  [n]
  (let [a (js/Uint8Array. n)]
    ;; getRandomValues fills at most 65,536 bytes a call
    (loop [off 0]
      (when (< off n)
        (.getRandomValues js/crypto (.subarray a off (min n (+ off 65536))))
        (recur (+ off 65536))))
    (vec (js/Array.from a))))

(defn read-line
  "Refused: a browser has no standard input."
  []
  (throw (ex-info "eve.process: a browser has no standard input" {:eve.process/refused :read-line})))
