(ns eve.flat
  "Flat Eve: immutable Eve bytes outside every domain, in two forms
   (FORMAT-V1.md §1). The IMAGE is how a value leaves a domain and enters
   one — its blocks as the slab holds them, with the block and relocation
   tables an allocator places by. The PACKED form is the wire and storage
   form: 64 KiB chunks read in place. Nothing here changes what @a returns;
   flat is asked for.

   This namespace is the public surface, frozen in Wave 0 (IMPL-PLAN §3.2,
   W0.1). Each verb delegates to its lane's namespace and answers a typed
   \"not built\" ex-info until that lane lands:

     detach      eve.flat.image/detach        (lane Z1)
     place       eve.flat.place/place         (lane Z3)
     transfer!   eve.flat.transfer/transfer!  (lane T1)
     pack        eve.flat.chunk/pack          (lane C1)
     open        eve.flat.chunk/open          (lane C1)
     open-checked eve.flat.check/open-checked (lane C4)
     bytes       eve.flat.chunk/bytes         (lane C1)"
  (:refer-clojure :exclude [open bytes])
  #?@(:cljs [(:require [eve.flat.image :as flat-image]
                       [eve.flat.chunk :as flat-chunk]
                        ;; the freeze lane loads with the facade on the
                        ;; static host: its load registers the atom
                        ;; deref-delivery hook and the sister freeze/thaw
                        ;; hooks, which on the runtime-require hosts are
                        ;; pulled in by the first explicit require of
                        ;; eve.flat.freeze — a cljs build has no such
                        ;; moment, so the facade is it.
                       [eve.flat.freeze])]
      :clje [(:require [eve.flat.freeze])]))

#?(:cljs
   (def ^:private cljs-lanes
     ;; CLJS has no requiring-resolve: the delegation table is flat.
     ;; Only lanes whose namespaces are in this tree are wired —
     ;; image (Z1) and chunk (C1); place (Z3), transfer (T1) and check
     ;; (C4) are not in the H4 tree, so their verbs keep the typed
     ;; not-built answer on this host until collation wires them.
     {'eve.flat.image/detach flat-image/detach
      'eve.flat.chunk/pack   flat-chunk/pack
      'eve.flat.chunk/open   flat-chunk/open
      'eve.flat.chunk/bytes  flat-chunk/bytes}))

(defn- delegate
  [lane-sym args]
  #?(:clj (let [v (try (requiring-resolve lane-sym)
                       (catch Throwable _ nil))]
            (if v
              (apply @v args)
              (throw (ex-info (str "eve.flat: " lane-sym " is not built yet")
                              {:eve.flat/not-built lane-sym}))))
     :cljs (if-let [f (get cljs-lanes lane-sym)]
             (apply f args)
             (throw (ex-info (str "eve.flat: " lane-sym " is not built yet")
                             {:eve.flat/not-built lane-sym})))))

(defn detach
  "The image of a value: (detach x), where x is an Eve atom (its committed
   value, copied under the pin) or a slab-backed value of some domain. The
   result is one byte array holding an image chunk (FORMAT-V1.md §9): the
   value's blocks, its block table and its relocation table. Lane Z1."
  [x]
  (delegate 'eve.flat.image/detach [x]))

(defn place
  "Place an image into atom a's domain inside a swap!: the value the image
   carries becomes a's value, laid into free blocks by the image's tables.
   Lane Z3."
  [a image]
  (delegate 'eve.flat.place/place [a image]))

(defn transfer!
  "Store x into atom a, where x is a slab-backed value of another domain:
   detached and zipped, no host collection built. Returns a. Lane T1."
  [a x]
  (delegate 'eve.flat.transfer/transfer! [a x]))

(defn pack
  "The packed form of a plain or slab-backed value: one or more 64 KiB
   chunks (FORMAT-V1.md §2–§6), as one byte array. Lane C1."
  [x]
  (delegate 'eve.flat.chunk/pack [x]))

(defn open
  "A flat value over bytes this process made or a store it trusts: the
   header only is checked (FORMAT-V1.md §10). The result satisfies the
   Clojure protocols over the bytes, without materializing. Lane C1."
  [b]
  (delegate 'eve.flat.chunk/open [b]))

(defn open-checked
  "A flat value over bytes from anywhere: one checking pass (FORMAT-V1.md
   §10) before any protocol operation sees them; a refusal is a typed
   ex-info naming the check. Lane C4."
  [b]
  (delegate 'eve.flat.check/open-checked [b]))

(defn bytes
  "The byte array a flat value stands on: its chunk(s), exactly as they
   travel and are stored. Lane C1."
  [s]
  (delegate 'eve.flat.chunk/bytes [s]))
