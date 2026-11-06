(ns eve.host
  "eve's host interface (adapters/README.md, 'eve is portable'; HOST-CONTRACT §10) on ValueScript, whose core is
   Clojure's: every name a macro expanding to clojure.core's own call, as adapters/core/clojure's eve.host does.

   The one difference is where the expansion lives. VS's macro evaluator runs a macro body over special forms, macros
   and clojure.core fns; it cannot call a defn or read a def of the file defining the macro
   (VS's src/vs/analyzer.vs, THE MACRO EVALUATOR), so core/clojure's `defcore` — whose macros call `eve.host/expand`
   over the `names` def — is refused there (`macroexpand: Unable to resolve symbol: names`). Here the names and the
   expansion sit inside the one macro that writes the others, and each written macro's body is its own expansion.

   `locking` is the one name VS's core lacks: a re-entrant lock per value, its word in linear memory (`lock!`)."
  (:require [vs.thread :as th])
  (:refer-clojure :exclude [aget aset alength aclone byte-array short-array int-array
                            long-array float-array double-array object-array into-array
                            to-array unchecked-byte unchecked-short unchecked-int
                            unchecked-long unchecked-add-int unchecked-multiply-int
                            unchecked-inc-int unchecked-dec-int unchecked-add
                            unchecked-multiply unchecked-inc unsigned-bit-shift-right
                            long double class volatile! volatile? vswap! locking
                            transient persistent! conj! assoc! dissoc! disj! pop!
                            bytes shorts ints longs floats doubles objects booleans
                            swap! reset! compare-and-set!]))

(defmacro ^:private defcore []
  (let [names '[aget aset alength aclone byte-array short-array int-array long-array float-array
                double-array object-array into-array to-array unchecked-byte unchecked-short
                unchecked-int unchecked-long unchecked-add-int unchecked-multiply-int
                unchecked-inc-int unchecked-dec-int unchecked-add unchecked-multiply unchecked-inc
                unsigned-bit-shift-right long double class volatile! volatile?
                transient persistent! conj! assoc! dissoc! disj! pop! bytes shorts ints longs floats
                doubles objects booleans swap! reset! compare-and-set!]
        core (fn [s] (symbol "clojure.core" (name s)))]
    (concat
     (list 'do)
     (map (fn [n] (list 'defmacro n '[& args] (list 'cons (list 'quote (core n)) 'args))) names)
     (list
      (list 'defmacro 'token '[] (list 'list (list 'quote (core 'volatile!)) nil))
      (list 'defmacro 'const-bytes '[& args] (list 'cons (list 'quote (core 'byte-array)) 'args))
      (list 'defmacro 'const-objects '[& args] (list 'cons (list 'quote (core 'object-array)) 'args))
      (list 'defmacro 'defdelay '[nm & body]
            (list 'list (list 'quote (core 'defonce)) 'nm (list 'list* (list 'quote (core 'delay)) 'body)))
      (list 'defmacro 'vswap! '[v f & more]
            (list 'let '[sym (gensym "vol")]
                  (list 'list ''let '[sym v]
                        (list 'list (list 'quote (core 'vreset!)) 'sym
                              (list 'list* 'f (list 'list (list 'quote (core 'deref)) 'sym) 'more)))))))))

(defcore)

(defmacro extend-default
  "(extend-protocol proto nil impls... Object impls...): proto for every value, nil included (core/clojure's
   extend-default; VS's root type is Object)."
  [proto & impls]
  (list* 'extend-protocol proto nil (concat impls (cons 'Object impls))))

;; ---------------------------------------------------------------------------
;; locking: reentrant mutual exclusion on a value, across the program's threads (HOST-CONTRACT §10), as Clojure's
;; monitor is. A value's lock is a slot of a table in linear memory — [key owner depth] — keyed by the value's slab
;; address (vs.core/rt-addr), claimed by CAS on first use and kept. The owner word holds the holding thread's id
;; (eve.process/thread-id's: 1 on the main thread, a mesh worker its id + 1), 0 when free; a waiter sleeps on it
;; (memory.atomic.wait32) and a release wakes one. Only the owner reads and writes the depth.
;; ---------------------------------------------------------------------------

(def ^:private LOCKS 65536)
(def ^:private lock-table (mmap/reserve! (* 16 LOCKS)))

(defn- lock-slot
  "x's slot: found by its key, or claimed for it"
  [x]
  (let [a (vs.core/rt-addr x)
        k (if (zero? a) -2147483648 a)]
    (loop [i (unsigned-bit-shift-right (bit-and (* k 2654435761) 0xFFFFFFFF) 16) n 0]
      (when (>= n LOCKS) (throw (ex-info "eve.host/locking: no lock slot left" {:slots LOCKS})))
      (let [s (+ lock-table (* 16 i))
            w (mmap/load-i32 s)]
        (cond (= w k) s
              (and (zero? w) (let [c (mmap/cas-i32! s 0 k)] (or (zero? c) (= c k)))) s
              :else (recur (bit-and (inc i) (dec LOCKS)) (inc n)))))))

(defn lock!
  "x's lock taken by this thread (again, when it holds it): x's slot, for unlock!."
  [x]
  (let [s (lock-slot x) o (+ s 4) d (+ s 8) me (inc (th/worker-id))]
    (loop []
      (let [w (mmap/load-i32 o)]
        (cond (= w me) (mmap/i32! d (inc (mmap/i32 d)))
              (and (zero? w) (zero? (mmap/cas-i32! o 0 me))) (mmap/i32! d 1)
              :else (do (when-not (zero? w) (mmap/wait-i32 o w 10)) (recur)))))
    s))

(defn unlock!
  "The lock lock! took released once: free when every taking is released."
  [s]
  (let [d (+ s 8) n (dec (mmap/i32 d))]
    (mmap/i32! d n)
    (when (zero? n)
      (mmap/store-i32! (+ s 4) 0)
      (mmap/notify! (+ s 4) 1))
    nil))

(defmacro locking
  "(locking x body...): body under x's lock (lock!), released however body ends."
  [x & body]
  (let [s (gensym "lock")]
    (list 'let [s (list 'eve.host/lock! x)]
          (list 'try (cons 'do body) (list 'finally (list 'eve.host/unlock! s))))))

(defmacro field
  "(field x f): the value of field f of x, a type's instance: (.-f x) (eve.host's name for a field read; on jank the
   completion's read by name)."
  [x f]
  (list (symbol (str ".-" (name f))) x))
