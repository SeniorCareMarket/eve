(ns ^:no-doc datascript.pull-api
  (:require
    [clojure.string :as str]
    [datascript.pull-parser :as dpp]
    ;; PORT DELTA (F-NBB-MACROS round): as in datascript.conn — no
    ;; :cljs type refer; types are named qualified at their uses
    [datascript.db :as db]
    [datascript.lru :as lru]
    [datascript.util :as util]
    [me.tonsky.persistent-sorted-set :as set]
    ;; PORT DELTA (F-CLJE-HOST-REFERS): see datascript.util
    #?(:clje [eve.host :refer [transient persistent! conj! assoc! volatile!]]))
  #?(:clj
     (:import
       [clojure.lang ISeq]
       [datascript.db Datom DB]
       [datascript.pull_parser PullAttr PullPattern]) :cljr (:import
       [clojure.lang ISeq]
       [datascript.db Datom DB]
       [datascript.pull_parser PullAttr PullPattern])))

(declare pull-impl attrs-frame ref-frame ->ReverseAttrsFrame)

;; PORT DELTA (F-BB-PULL-SEQ): the :clj arms call ISeq methods by
;; interop (.first/.next/.cons). Under bb those compile to
;; reflective invocations, and bb's native image has no reflection
;; registration for clojure.lang.ISeq.first/next/cons -- every pull
;; dies with MissingReflectionRegistrationError (RESULTS-BB
;; wall 2). The :bb arms use the core fns, like :clje already
;; does: same methods, reached through SCI's own dispatch instead
;; of reflection.
;; PORT DELTA (F-JOLT-PULL-SEQ, fix-for-release C2): the :clj/:cljr
;; arms use the core fns too. jolt reads :clj, and its ISeq interop
;; on a clojure.lang.ArraySeq — what (sort-by ...) answers there,
;; and so the attrs a pull pattern walks — throws "No matching field
;; found: first/next for class clojure.lang.ArraySeq", so every pull
;; died on jolt; core first/next work on it. On the JVM the core
;; fns call the same ISeq methods (RT.first/RT.next on an ISeq are
;; .first/.next, and RT.conj on an ISeq is .cons), so the values are
;; unchanged there.
(defn- first-seq [#?(:bb xs :clj ^ISeq xs :cljs ^seq xs :clje xs :cljr xs)]
  (if (nil? xs)
    nil
    #?(:bb (first xs) :clj (first xs) :cljr (first xs) :cljs (-first xs) :clje (first xs))))

(defn- next-seq [#?(:bb xs :clj ^ISeq xs :cljs ^seq xs :clje xs :cljr xs)]
  (if (nil? xs)
    nil
    #?(:bb (next xs) :clj (next xs) :cljr (next xs) :cljs (-next xs) :clje (next xs))))

(defn- conj-seq [#?(:bb xs :clj ^ISeq xs :cljs ^seq xs :clje xs :cljr xs) x]
  (if (nil? xs)
    (list x)
    #?(:bb (cons x xs) :clj (conj xs x) :cljr (conj xs x) :cljs (-conj xs x) :clje (conj xs x))))

(defn- assoc-some! [m k v]
  (if (nil? v) m (assoc! m k v)))

(defn- conj-some! [xs v]
  (if (nil? v) xs (conj! xs v)))

(defrecord Context [db visitor])

(defn visit [^Context context pattern e a v]
  (when-some [visitor (.-visitor context)]
    (visitor pattern e a v)))

(defprotocol IFrame
  (-merge [this result])
  (-run [this context])
  (-str [this]))

(defn- attr-str [attr]
  (or (:as attr) (:name attr)))

(defrecord ResultFrame [value datoms]
  IFrame
  (-str [this]
    (str "ResultFrame<value=" value ">")))

(defrecord MultivalAttrFrame [acc #?@(:clj [^PullAttr attr] :cljr [^PullAttr attr] :cljs [^PullAttr attr] :clje [attr] :lpy [attr]) datoms]
  IFrame
  (-run [this context]
    (loop [acc acc
           datoms datoms]
      (util/cond+
        :let [#?@(:jank [^db/Datom datom] :clj [^Datom datom] :cljr [^Datom datom] :cljs [^db/Datom datom] :clje [datom] :lpy [datom]) (first-seq datoms)]

        (or (nil? datom) (not= (.-a datom) (.-name attr)))
        [(ResultFrame. (not-empty (persistent! acc)) (or datoms ()))]

        ; got limit, skip rest of the datoms
        (and (.-limit attr) (>= (count acc) (.-limit attr)))
        (loop [datoms datoms]
          (let [#?@(:jank [^db/Datom datom] :clj [^Datom datom] :cljr [^Datom datom] :cljs [^db/Datom datom] :clje [datom] :lpy [datom]) (first-seq datoms)]
            (if (or (nil? datom) (not= (.-a datom) (.-name attr)))
              [(ResultFrame. (persistent! acc) (or datoms ()))]
              (recur (next-seq datoms)))))

        :else
        (recur (conj! acc (.-v datom)) (next-seq datoms)))))
  
  (-str [this]
    (str "MultivalAttrFrame<attr=" (attr-str attr) ">")))

(defrecord MultivalRefAttrFrame [seen recursion-limits acc pattern #?@(:clj [^PullAttr attr] :cljr [^PullAttr attr] :cljs [^PullAttr attr] :clje [attr] :lpy [attr]) datoms]
  IFrame
  (-merge [this result]
    (MultivalRefAttrFrame.
      seen
      recursion-limits
      (conj-some! acc (.-value ^ResultFrame result))
      pattern
      attr
      (next-seq datoms)))
  
  (-run [this context]
    (util/cond+
      :let [#?@(:jank [^db/Datom datom] :clj [^Datom datom] :cljr [^Datom datom] :cljs [^db/Datom datom] :clje [datom] :lpy [datom]) (first-seq datoms)]

      (or (nil? datom) (not= (.-a datom) (.-name attr)))
      [(ResultFrame. (not-empty (persistent! acc)) (or datoms ()))]

      ; got limit, skip rest of the datoms
      (and (.-limit attr) (>= (count acc) (.-limit attr)))
      (loop [datoms datoms]
        (let [#?@(:jank [^db/Datom datom] :clj [^Datom datom] :cljr [^Datom datom] :cljs [^db/Datom datom] :clje [datom] :lpy [datom]) (first-seq datoms)]
          (if (or (nil? datom) (not= (.-a datom) (.-name attr)))
            [(ResultFrame. (persistent! acc) (or datoms ()))]
            (recur (next-seq datoms)))))

      :let [id (if (.-reverse? attr) (.-e datom) (.-v datom))]

      :else
      [this (ref-frame context seen recursion-limits pattern attr id)]))
  
  (-str [this]
    (str "MultivalAttrFrame<attr=" (attr-str attr) ">")))

(defrecord AttrsFrame [seen recursion-limits acc #?@(:clj [^PullPattern pattern] :cljr [^PullPattern pattern] :cljs [^PullPattern pattern] :clje [pattern] :lpy [pattern]) #?@(:clj [^PullAttr attr] :cljr [^PullAttr attr] :cljs [^PullAttr attr] :clje [attr]) attrs datoms id]
  IFrame
  (-merge [this result]
    (AttrsFrame.
      seen
      recursion-limits
      (assoc-some! acc (.-as attr) ((.-xform attr) (.-value ^ResultFrame result)))
      pattern
      (first-seq attrs)
      (next-seq attrs)
      (not-empty (or (.-datoms ^ResultFrame result) (next-seq datoms)))
      id))
  (-run [this context]
    (loop [acc    acc
           attr   attr
           attrs  attrs
           datoms datoms]
      (util/cond+
        ;; exit
        (and (nil? datoms) (nil? attr))
        [(->ReverseAttrsFrame seen recursion-limits acc pattern (first-seq (.-reverse-attrs pattern)) (next-seq (.-reverse-attrs pattern)) id)]

        ;; :db/id
        (and (some? attr) (= :db/id (.-name attr)))
        (recur (assoc! acc (.-as attr) ((.-xform attr) id)) (first-seq attrs) (next-seq attrs) datoms)

        :let [#?@(:jank [^db/Datom datom] :clj [^Datom datom] :cljr [^Datom datom] :cljs [^db/Datom datom] :clje [datom] :lpy [datom]) (first-seq datoms)
              cmp          (when (and datom attr)
                             (compare (.-name attr) (.-a datom)))
              attr-ahead?  (or (nil? attr) (and cmp (pos? cmp)))
              datom-ahead? (or (nil? datom) (and cmp (neg? cmp)))]

        ;; wildcard
        (and (.-wildcard? pattern) (some? datom) attr-ahead?)
        (let [datom-attr (lru/-get
                           db/pull-attrs-cache
                           (.-a datom)
                           #(dpp/parse-attr-name (.-db ^Context context) (.-a datom)))]
          (recur acc datom-attr (when attr (conj-seq attrs attr)) datoms))

        ;; advance datom
        attr-ahead?
        (recur acc attr attrs (next-seq datoms))

        :do (visit context :db.pull/attr id (.-name attr) nil)

        ;; advance attr
        (and datom-ahead? (nil? attr))
        (recur acc (first-seq attrs) (next-seq attrs) datoms)

        ;; default
        (and datom-ahead? (some? (#?(:clj .-default :cljr .-default :cljs :default :clje .-default) attr)))
        (recur (assoc! acc (.-as attr) (#?(:clj .-default :cljr .-default :cljs :default :clje .-default) attr)) (first-seq attrs) (next-seq attrs) datoms)

        ;; xform
        datom-ahead?
        (if-some [value ((.-xform attr) nil)]
          (recur (assoc! acc (.-as attr) value) (first-seq attrs) (next-seq attrs) datoms)
          (recur acc (first-seq attrs) (next-seq attrs) datoms))

        ;; matching attr
        (and (.-multival? attr) (.-ref? attr))
        [(AttrsFrame. seen recursion-limits acc pattern attr attrs datoms id)
         (MultivalRefAttrFrame. seen recursion-limits (transient []) pattern attr datoms)]

        (.-multival? attr)
        [(AttrsFrame. seen recursion-limits acc pattern attr attrs datoms id)
         (MultivalAttrFrame. (transient []) attr datoms)]

        (.-ref? attr)
        [(AttrsFrame. seen recursion-limits acc pattern attr attrs datoms id)
         (ref-frame context seen recursion-limits pattern attr (.-v datom))]

        :else
        (recur
          (assoc! acc (.-as attr) ((.-xform attr) (.-v datom)))
          (first-seq attrs)
          (next-seq attrs)
          (next-seq datoms)))))
  
  (-str [this]
    (str "AttrsFrame<id=" id ", attr=" (attr-str attr) ", attrs=" (str/join " " (map attr-str attrs)) ">")))

(defrecord ReverseAttrsFrame [seen recursion-limits acc pattern #?@(:clj [^PullAttr attr] :cljr [^PullAttr attr] :cljs [^PullAttr attr] :clje [attr] :lpy [attr]) attrs id]
  IFrame
  (-merge [this result]
    (ReverseAttrsFrame.
      seen
      recursion-limits
      (assoc-some! acc (.-as attr) ((.-xform attr) (.-value ^ResultFrame result)))
      pattern
      (first-seq attrs)
      (next-seq attrs)
      id))
  
  (-run [this context]
    (loop [acc   acc
           attr  attr
           attrs attrs]
      (util/cond+
        (nil? attr)
        [(ResultFrame. (not-empty (persistent! acc)) nil)]

        :let [name   (.-name attr)
              db     (.-db ^Context context)
              datoms (if (instance? #?(:jank db/DB :clj DB :cljr DB :cljs db/DB :clje db/DB) db)
                       (set/slice (.-avet #?@(:jank [^db/DB db] :clj [^DB db] :cljr [^DB db] :cljs [^db/DB db] :clje [db] :lpy [db])) (db/datom db/e0 name id db/tx0) (db/datom db/emax name id db/txmax))
                       (db/-search db [nil name id]))]

        :do (visit context :db.pull/reverse nil name id)

        (and (empty? datoms) (some? (#?(:clj .-default :cljr .-default :cljs :default :clje .-default) attr)))
        (recur (assoc! acc (.-as attr) (#?(:clj .-default :cljr .-default :cljs :default :clje .-default) attr)) (first-seq attrs) (next-seq attrs))

        (empty? datoms)
        (recur acc (first-seq attrs) (next-seq attrs))

        (.-component? attr)
        [(ReverseAttrsFrame. seen recursion-limits acc pattern attr attrs id)
         (ref-frame context seen recursion-limits pattern attr (.-e #?@(:clj [^Datom (first-seq datoms)] :cljr [^Datom (first-seq datoms)] :cljs [^db/Datom (first-seq datoms)] :clje [(first-seq datoms)] :lpy [(first-seq datoms)])))]

        :else
        [(ReverseAttrsFrame. seen recursion-limits acc pattern attr attrs id)
         (MultivalRefAttrFrame. seen recursion-limits (transient []) pattern attr datoms)])))
  
  (-str [this]
    (str "ReverseAttrsFrame<id=" id ", attr=" (attr-str attr) ", attrs=" (str/join " " (map attr-str attrs)) ">")))

(defn- auto-expanding? [#?@(:clj [^PullAttr attr] :cljr [^PullAttr attr] :cljs [^PullAttr attr] :clje [attr] :lpy [attr])]
  (or
    (.-recursive? attr)
    (and
      (.-component? attr)
      (.-wildcard? #?(:clj ^PullPattern (.-pattern attr) :cljr ^PullPattern (.-pattern attr) :cljs ^PullPattern (.-pattern attr) :clje (.-pattern attr))))))

(defn ref-frame [context seen recursion-limits pattern #?@(:clj [^PullAttr attr] :cljr [^PullAttr attr] :cljs [^PullAttr attr] :clje [attr] :lpy [attr]) id]
  (util/cond+
    (not (auto-expanding? attr))
    (attrs-frame context seen recursion-limits (.-pattern attr) id)

    (seen id)
    (ResultFrame. {:db/id id} nil)

    :let [lim (recursion-limits attr)]

    (and lim (<= lim 0))
    (ResultFrame. nil nil)

    :let [seen' (conj seen id)
          recursion-limits' (cond
                              lim                      (update recursion-limits attr dec)
                              (.-recursion-limit attr) (assoc recursion-limits attr (dec (.-recursion-limit attr)))
                              :else                    recursion-limits)]

    :else
    (attrs-frame context seen' recursion-limits' (if (.-recursive? attr) pattern (.-pattern attr)) id)))

(defn attrs-frame [^Context context seen recursion-limits #?@(:clj [^PullPattern pattern] :cljr [^PullPattern pattern] :cljs [^PullPattern pattern] :clje [pattern] :lpy [pattern]) id]
  (let [db (.-db context)
        datoms (util/cond+
                 (and (.-wildcard? pattern) (instance? #?(:jank db/DB :clj DB :cljr DB :cljs db/DB :clje db/DB) db))
                 (set/slice (.-eavt #?@(:jank [^db/DB db] :clj [^DB db] :cljr [^DB db] :cljs [^db/DB db] :clje [db] :lpy [db])) (db/datom id nil nil db/tx0) (db/datom id nil nil db/txmax))
                 
                 (.-wildcard? pattern)
                 (db/-search db [id])

                 (nil? (.-first-attr pattern))
                 nil

                 :let [from (.-name #?(:clj ^PullAttr (.-first-attr pattern) :cljr ^PullAttr (.-first-attr pattern) :cljs ^PullAttr (.-first-attr pattern) :clje (.-first-attr pattern)))
                       to   (.-name #?(:clj ^PullAttr (.-last-attr pattern) :cljr ^PullAttr (.-last-attr pattern) :cljs ^PullAttr (.-last-attr pattern) :clje (.-last-attr pattern)))]
                 
                 (instance? #?(:jank db/DB :clj DB :cljr DB :cljs db/DB :clje db/DB) db)
                 (set/slice (.-eavt #?@(:jank [^db/DB db] :clj [^DB db] :cljr [^DB db] :cljs [^db/DB db] :clje [db] :lpy [db])) (db/datom id from nil db/tx0) (db/datom id to nil db/txmax))

                 :else
                 (->> (db/-seek-datoms db :eavt id nil nil nil))
                 (take-while
                   (fn [#?@(:jank [^db/Datom d] :clj [^Datom d] :cljr [^Datom d] :cljs [^db/Datom d] :clje [d] :lpy [d])]
                     (and
                       (= (.-e d) id)
                       (<= (compare (.-a d) to) 0)))))]
    (when (.-wildcard? pattern)
      (visit context :db.pull/wildcard id nil nil))
    (AttrsFrame.
      seen
      recursion-limits
      (transient {})
      pattern
      (first-seq (.-attrs pattern))
      (next-seq (.-attrs pattern))
      datoms
      id)))

(defn pull-impl [parsed-opts id]
  (let [{^Context context :context
         #?@(:clj [^PullPattern pattern :pattern] :cljr [^PullPattern pattern :pattern] :cljs [^PullPattern pattern :pattern] :clje [pattern :pattern] :lpy [pattern :pattern])} parsed-opts]
    (when-some [eid (db/entid (.-db context) id)]
      (loop [stack (list (attrs-frame context #{} {} pattern eid))]
        (util/cond+
          :let [last   (first-seq stack)
                stack' (next-seq stack)]

          (not (instance? ResultFrame last))
          (recur (reduce conj-seq stack' (-run last context)))

          (nil? stack')
          (.-value ^ResultFrame last)

          :let [penultimate (first-seq stack')
                stack''     (next-seq stack')]

          :else
          (recur (conj-seq stack'' (-merge penultimate last))))))))

(defn parse-opts
  ([db pattern] (parse-opts db pattern nil))
  ([db pattern {:keys [visitor]}]
   {:pattern (lru/-get db/pull-patterns-cache pattern #(dpp/parse-pattern db pattern))
    :context (Context. db visitor)}))

(defn pull
  "Supported opts:

   :visitor a fn of 4 arguments, will be called for every entity/attribute pull touches

   (:db.pull/attr     e   a   nil) - when pulling a normal attribute, no matter if it has value or not
   (:db.pull/wildcard e   nil nil) - when pulling every attribute on an entity
   (:db.pull/reverse  nil a   v  ) - when pulling reverse attribute"
  ([db pattern id] (pull db pattern id {}))
  ([db pattern id opts]
   {:pre [(db/db? db)]}
   (let [parsed-opts (parse-opts db pattern opts)]
     (pull-impl parsed-opts id))))

(defn pull-many
  ([db pattern ids] (pull-many db pattern ids {}))
  ([db pattern ids opts]
   {:pre [(db/db? db)]}
   (let [parsed-opts (parse-opts db pattern opts)]
     (mapv #(pull-impl parsed-opts %) ids))))
