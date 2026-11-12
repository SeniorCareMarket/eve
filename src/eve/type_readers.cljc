(ns eve.type-readers
  "The EDN side of Eve types. Type declarations install their reader at load
   time, on the host's ordinary reader; callers need no :readers option.
   A custom printed representation must supply its inverse with :read."
  (:require [eve.encoding :as enc]
            [eve.deftype.registry :as registry]
            #?(:cljs [cljs.reader :as reader]
               :bb [clojure.edn :as edn]
               :lpy [clojure.edn :as edn]))
  #?(:lpy (:import io sys basilisp.lang.obj basilisp.lang.reader)))

(defprotocol ITypeFields
  (-type-fields [value] "The declared fields, in declaration order."))

(defn require-fields! [tag data]
  (when-not (map? data)
    (throw (ex-info "Eve tagged type expects a field map" {:tag tag :value data})))
  data)

(defn write-tagged!
  "Write #tag payload. With field-order, print the declared field map in that
   order on every host, even where a small map is not insertion ordered."
  ([writer tag value]
   (enc/write! writer (str "#" tag " " (pr-str value))))
  ([writer tag value field-order]
   (enc/write! writer
     (str "#" tag " {"
          (apply str (interpose ", " (map (fn [k] (str (pr-str k) " " (pr-str (get value k))))
                                          field-order)))
          "}"))))

(defn reader-options
  "Eve registrations, then the caller's dynamic and explicit readers."
  [opts]
  (let [readers (merge (registry/data-readers)
                       #?(:cljs {} :default *data-readers*) (:readers opts))
        opts (assoc opts :readers readers)]
    #?(:lpy
       ;; Basilisp's safe EDN reader sends unqualified application tags
       ;; directly to :default instead of consulting :readers. Normalize that
       ;; path too, preserving explicit per-call readers and fallback hooks.
       (let [fallback (:default opts)]
         (assoc opts :default
           (fn [tag value]
             (if-let [read-fn (get readers tag)]
               (read-fn value)
               (if fallback (fallback tag value)
                 (throw (ex-info "No reader function for tag" {:tag tag})))))))
       :default opts)))

#?(:bb
   ;; SCI's core reader uses its own dynamic environment. Its default hook
   ;; is public and is honored where changing *data-readers*' root is not.
   (let [previous *default-data-reader-fn*]
     (alter-var-root #'*default-data-reader-fn*
       (constantly
         (fn [tag value]
           (if-let [read-fn (get (registry/data-readers) tag)]
             (read-fn value)
             (if previous (previous tag value)
               (throw (ex-info "No reader function for tag" {:tag tag})))))))))

(defn register!
  "Install tag -> reader in the host registry (repeat loading replaces it).
   clojure.edn uses default-data-readers; core read uses *data-readers*.
   Updating roots preserves unrelated tags and current thread bindings."
  [type-key tag read-fn print-info]
  (registry/register-type! type-key
    {:type-key type-key :type-io {:tag tag :read read-fn :print print-info}})
  #?(:cljs (reader/register-tag-parser! tag read-fn)
     ;; clojerl var roots are backed by compiled module functions and
     ;; cannot be altered (its alter-var-root throws "unsupported").
     ;; On BEAM the registration above IS the installation: Eve's own
     ;; registry holds the tag, and every Eve read path consults it
     ;; (reader-options / eve.edn), which is the only reader surface
     ;; Eve controls there. Host read-string without explicit readers
     ;; does not see Eve tags on clje — a named limitation of the host,
     ;; not a branch in Eve's semantics.
     :clje nil
     :default
     (do
       (alter-var-root #'*data-readers* assoc tag read-fn)
       (when-let [defaults (resolve 'default-data-readers)]
         (alter-var-root defaults assoc tag read-fn))
       #?(:lpy
          ;; Basilisp snapshots its built-ins from this class member, not
          ;; from the Clojure var. Keep read-string with explicit bindings
          ;; consistent with the ordinary, unbound reader.
          (do
            (python/setattr basilisp.lang.reader/ReaderContext "_DATA_READERS"
                            (assoc (.-_DATA_READERS basilisp.lang.reader/ReaderContext) tag read-fn))))))
  nil)

#?(:lpy
   (do
     (defn- render [o]
       (let [w (io/StringIO)] (print-method o w) (.getvalue w)))
     (defn- ^{:kwargs :collect} type-lrepr [o _kwargs] (render o))
     (defn register-print!
       "Install the declared printer ahead of Python collection dispatch."
       [t]
       (.register basilisp.lang.obj/lrepr t type-lrepr)
       (python/setattr t "__repr__" render)
       (python/setattr t "__str__" render))))

#?(:bb
   ;; SCI's default-data-readers is not the native EDNReader var. Bridge the
   ;; ordinary public reader functions once, rather than requiring options in
   ;; every consumer. Explicit per-call readers still win.
   (let [native-read edn/read native-read-string edn/read-string
         options reader-options]
     (alter-var-root #'edn/read-string
                     (fn [_] (fn
                               ([s] (native-read-string (options {}) s))
                               ([opts s] (native-read-string (options opts) s)))))
     (alter-var-root #'edn/read
                     (fn [_] (fn
                               ([r] (native-read (options {}) r))
                               ([opts r] (native-read (options opts) r)))))))

#?(:lpy
   ;; Basilisp compiles ordinary function calls to Python module attributes,
   ;; not Var derefs. Updating only a Var root leaves those calls untouched.
   ;; Install the bridge on BOTH surfaces; keep Basilisp's native [input opts]
   ;; arities as well as the Clojure [opts input] contract.
   (let [native-read edn/read native-read-string edn/read-string
         install! (fn [sym f]
                    (alter-var-root (ns-resolve 'basilisp.edn sym) (constantly f))
                    (python/setattr (.get sys/modules "basilisp.edn")
                                    (munge (name sym)) f))]
     (install! 'read-string
       (fn
         ([s] (native-read-string s (reader-options {})))
         ([a b] (if (map? a)
                  (native-read-string b (reader-options a))
                  (native-read-string a (reader-options b))))))
     (install! 'read
       (fn
         ([] (native-read *in* (reader-options {})))
         ([r] (native-read r (reader-options {})))
         ([a b] (if (map? a)
                  (native-read b (reader-options a))
                  (native-read a (reader-options b))))))))
