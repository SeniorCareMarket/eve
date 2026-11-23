(ns eve.deftype.host-deftype-ext-test
  "R0 extension (Lane A2): host-deftype for core types written with
   the CLJ/JVM API — the standard across all CLJC Eve (John, Nov 14
   2026). The fixtures mirror Lane B's stopped families AS THEY
   ARRIVE at migration: CLJ-spelled (the fan lanes respell the
   ClojureScript sections of the stopped bodies to the CLJ API;
   per-host name realization stays in the adapters/vocab). What this
   suite proves is the gap that stood even for CLJ bodies: the
   type's print implementation — an IPrintWithWriter section, the
   print spelling Eve's type bodies carry, or the type-io :print
   declaration — realized as the type's print-method, where the old
   verbatim host-type could not derive one (plan §6, G3).

     TransientShape  — rrbt's Transient (rrbt.cljc:1224), CLJ face:
                       clojure.lang transient heads, respelled
                       ^:volatile-mutable fields.
     PrintedSeq      — RSeq (rseq.cljc:11): JVM heads +
                       IPrintWithWriter + a java.lang.Object group.
     AtomShape       — MmapAtom (atom.cljc:1299): eve's own protocol
                       heads + print by the type-io :print option.
     ObjShape        — the Obj trio (obj/impl.cljc): own data
                       protocols + IPrintWithWriter + Object group.
     HeapArrayShape  — HeapEveArray (array.cljc:893): JVM collection
                       heads + IPrintWithWriter + Object group.
     ChunkedSeqShape — RRBChunkedSeq (rrbt.cljc:69), CLJ face:
                       IChunkedSeq/ISeq/IPersistentCollection/IMeta
                       heads over a respelled ^:volatile-mutable
                       hash__ field.
     RespelledShape  — the per-caller respell itself: a CLJ-spelled
                       hash__ field, set! through the body.

   The call sites themselves migrate in Lane B's line; this suite
   proves the macro capability they migrate onto, on the JVM.

   Fixture bodies construct their own type through the class
   constructor (PrintedSeq. …), the shape the in-tree bodies
   arrive in (RSeq. in rseq.cljc, RRBChunkedSeq. in rrbt.cljc):
   the positional factory self-reference (->PrintedSeq inside
   the body) is rejected by stock clojure.core/deftype itself —
   parity, not a realization gap (dev/proto_r0x_diag.clj: stock
   and host controls throw identically, the constructor control
   passes)."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.deftype-proto.macros :as pm] [clojure.string]))

;; ---------------------------------------------------------------------------
;; fixtures
;; ---------------------------------------------------------------------------

(pm/host-deftype TransientShape [^:volatile-mutable cnt ^:volatile-mutable items]
                 clojure.lang.ITransientCollection
                 (conj [this x] (set! items (conj items x)) (set! cnt (inc cnt)) this)
                 (persistent [_] items)
                 clojure.lang.Indexed
                 (nth [_ i] (nth items i))
                 (nth [_ i nf] (if (and (<= 0 i) (< i cnt)) (nth items i) nf))
                 clojure.lang.Counted
                 (count [_] cnt))

(pm/host-deftype PrintedSeq [items]
                 clojure.lang.Counted
                 (count [_] (count items))
                 clojure.lang.IPersistentCollection
                 (equiv [_ other] (and (instance? PrintedSeq other)
                                       (= items (.-items ^PrintedSeq other))))
                 clojure.lang.ISeq
                 (first [_] (first items))
                 (next [_] (let [r (next items)] (when r (PrintedSeq. r))))
                 (more [_] (or (next items) ()))
                 IPrintWithWriter
                 (-pr-writer [this w _opts] (.write w (str "#ext/seq " (pr-str (.-items this)))))
                 java.lang.Object
                 (toString [_] (str "#ext/seq " (pr-str items)))
                 (hashCode [_] (hash items)))

(defprotocol IAtomShape
  (-slot [x]))

(pm/host-deftype AtomShape [^:volatile-mutable state slot]
                 {:print (fn [x w _opts] (.write w (str "#ext/atom {:slot " (.-slot ^AtomShape x) "}")))}
                 clojure.lang.IDeref
                 (deref [_] state)
                 IAtomShape
                 (-slot [_] slot))

(defprotocol IObjParts
  (-parts [x]))

(pm/host-deftype ObjShape [m]
                 IObjParts
                 (-parts [_] m)
                 clojure.lang.ILookup
                 (valAt [_ k] (get m k))
                 (valAt [_ k nf] (get m k nf))
                 IPrintWithWriter
                 (-pr-writer [this w _opts] (.write w (str "#ext/obj " (pr-str (.-m this)))))
                 java.lang.Object
                 (toString [_] (str "#ext/obj " (pr-str m)))
                 (equals [_ other] (and (instance? ObjShape other)
                                        (= m (.-m ^ObjShape other))))
                 (hashCode [_] (hash m)))

(pm/host-deftype HeapArrayShape [elems]
                 clojure.lang.Counted
                 (count [_] (count elems))
                 clojure.lang.Indexed
                 (nth [_ i] (nth elems i))
                 (nth [_ i nf] (if (and (<= 0 i) (< i (count elems))) (nth elems i) nf))
                 clojure.lang.IPersistentCollection
                 (cons [_ x] (HeapArrayShape. (conj elems x)))
                 (empty [_] (HeapArrayShape. []))
                 (equiv [_ other] (and (instance? HeapArrayShape other)
                                       (= elems (.-elems ^HeapArrayShape other))))
                 IPrintWithWriter
                 (-pr-writer [this w _opts] (.write w (str "#ext/array " (pr-str (.-elems this)))))
                 java.lang.Object
                 (toString [_] (str "#ext/array " (pr-str elems)))
                 (equals [_ other] (and (instance? HeapArrayShape other)
                                        (= elems (.-elems ^HeapArrayShape other))))
                 (hashCode [_] (hash elems)))

(pm/host-deftype ChunkedSeqShape [node off m ^:volatile-mutable hash__]
                 clojure.lang.Seqable
                 (seq [this] this)
                 clojure.lang.IPersistentCollection
                 (cons [_ x] (cons x (seq (ChunkedSeqShape. node off m hash__))))
                 (empty [_] ())
                 (equiv [_ other] (and (instance? ChunkedSeqShape other)
                                       (= (subvec node off)
                                          (subvec (.-node ^ChunkedSeqShape other)
                                                  (.-off ^ChunkedSeqShape other)))))
                 clojure.lang.ISeq
                 (first [_] (nth node off))
                 (next [_] (when (< (inc off) (count node))
                             (ChunkedSeqShape. node (inc off) m hash__)))
                 (more [this] (or (next this) ()))
                 clojure.lang.IChunkedSeq
                 (chunkedFirst [_] (clojure.lang.ArrayChunk. (object-array (subvec node off))))
                 (chunkedNext [this] (seq (rest this)))
                 (chunkedMore [this] (or (seq (rest this)) ()))
                 clojure.lang.Counted
                 (count [_] (- (count node) off))
                 clojure.lang.IMeta
                 (meta [_] m)
                 clojure.lang.IObj
                 (withMeta [_ m2] (ChunkedSeqShape. node off m2 hash__))
                 IPrintWithWriter
                 (-pr-writer [this w _opts] (.write w (str "(" (clojure.string/join " " (seq this)) ")")))
                 java.lang.Object
                 (toString [this] (pr-str this))
                 (equals [_ other] (and (instance? ChunkedSeqShape other)
                                        (= (subvec node off)
                                           (subvec (.-node ^ChunkedSeqShape other)
                                                   (.-off ^ChunkedSeqShape other)))))
                 (hashCode [_] (hash (seq (ChunkedSeqShape. node off m hash__)))))

(defprotocol IReset
  (-reset! [x v]))

(pm/host-deftype RespelledShape [^:volatile-mutable hash__ items]
                 clojure.lang.Counted
                 (count [_] (count items))
                 clojure.lang.IHashEq
                 (hasheq [_] hash__)
                 IReset
                 (-reset! [this v] (set! hash__ v) this))

;; ---------------------------------------------------------------------------
;; tests
;; ---------------------------------------------------------------------------

(deftest transient-shape-test
  (let [t (->TransientShape 2 [10 20])]
    (testing "the CLJ transient heads land on the interfaces they name"
      (is (instance? clojure.lang.Counted t))
      (is (instance? clojure.lang.Indexed t))
      (is (instance? clojure.lang.ITransientCollection t)))
    (is (= 2 (count t)))
    (is (= 20 (nth t 1)))
    (is (= :nf (nth t 9 :nf)))
    (is (= [10 20 30] (persistent! (conj! t 30))))
    (is (= 3 (count t)) "respelled ^:volatile-mutable fields are set! through the body")))

(deftest printed-seq-shape-test
  (let [s (->PrintedSeq [1 2 3])]
    (is (= 3 (count s)))
    (is (= 1 (first s)))
    (is (= [2 3] (.-items (next s))))
    (testing "the IPrintWithWriter body is the type's print-method"
      (is (= "#ext/seq [1 2 3]" (pr-str s))))
    (is (= "#ext/seq [1 2 3]" (str s)) "the Object group's toString")
    (testing "equals is derived from the body's equiv; hashCode is its own"
      (is (= s (->PrintedSeq [1 2 3])))
      (is (= (hash [1 2 3]) (.hashCode s)))
      (is (not= s (->PrintedSeq [1 2]))))))

(deftest atom-shape-test
  (let [a (->AtomShape :v 7)]
    (is (= :v @a))
    (is (= 7 (-slot a)))
    (testing "the type-io :print declaration is the type's print-method"
      (is (= "#ext/atom {:slot 7}" (pr-str a))))))

(deftest obj-shape-test
  (let [o (->ObjShape {:a 1})]
    (is (= {:a 1} (-parts o)))
    (is (= 1 (get o :a)))
    (is (= :nf (get o :b :nf)))
    (is (= "#ext/obj {:a 1}" (pr-str o)))
    (is (= o (->ObjShape {:a 1})))
    (is (= (hash {:a 1}) (.hashCode o)))))

(deftest heap-array-shape-test
  (let [a (->HeapArrayShape [7 8])]
    (is (= 2 (count a)))
    (is (= 8 (nth a 1)))
    (is (= :nf (nth a 5 :nf)))
    (is (= [7 8 9] (.-elems (conj a 9))))
    (is (= "#ext/array [7 8]" (pr-str a)))
    (is (= a (->HeapArrayShape [7 8])))))

(deftest chunked-seq-shape-test
  (let [s (->ChunkedSeqShape [10 20 30] 0 {:k 1} nil)]
    (is (instance? clojure.lang.IChunkedSeq s))
    (is (= 3 (count s)))
    (is (= [10 20 30] (seq s)))
    (is (= 10 (first s)))
    (is (= [20 30] (seq (next s))))
    (is (= 3 (.count (.chunkedFirst s))) "the chunked head")
    (is (= 20 (.nth (.chunkedFirst s) 1)))
    (is (= {:k 1} (meta s)))
    (is (= {:k 2} (meta (with-meta s {:k 2}))))
    (is (= "(10 20 30)" (pr-str s)) "the print section is the print-method")
    (is (= s (->ChunkedSeqShape [10 20 30] 0 {:k 1} nil)))))

(deftest respelled-shape-test
  (let [r (->RespelledShape 42 [:x])]
    (is (= 1 (count r)))
    (is (= 42 (hash r)) "the respelled hash__ field reads")
    (-reset! r 43)
    (is (= 43 (hash r)) "and is set! through the body")))
