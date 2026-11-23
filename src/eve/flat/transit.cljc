;; Transit handlers for flat handles (UPGRADE-PLAN R9).
;;
;; Measured fact (Nov 13 2026): Transit's writer dispatches on
;; the value's CLASS, not on the collection protocols, so a
;; flat handle nested in host data is refused ("Not supported:
;; class eve.flat.chunk.FlatBatch") even though the handle is
;; fully walkable. The handlers here are the bridge, in two
;; flavors:
;;
;;   plain — the handle is written as its logical content
;;   (a JSON array / object / set). The far side receives
;;   plain data. This is "ingest by walking", with the rep
;;   doing the walk.
;;
;;   flat — the handle is written as its chunk bytes under an
;;   eve tag, and the read handler reopens it as a flat
;;   handle: flat ships AS flat through Transit.
;;
;; Map and set handles need no entry in either flavor: on the
;; JVM they are proxies over APersistentMap / APersistentSet
;; (a flat map must BE an APersistentMap for plain-map equiv
;; dispatch to accept it — see defmapproxy in eve.flat.chunk),
;; so there is no stable class to key a handler on, and none
;; is needed: Transit's default map/set handlers walk them
;; like any other map. The flat flavor therefore covers the
;; sequential values (the dataframe case); a map handle
;; travels as its content in both flavors.
;;
;; Usage:
;;   (transit/writer out :json {:handlers (ft/plain-write-handlers)})
;;   (transit/writer out :json {:handlers (ft/flat-write-handlers)})
;;   (transit/reader in :json {:handlers (ft/flat-read-handlers)})
;;
;; cognitect.transit is resolved lazily (requiring-resolve),
;; so loading this namespace never requires Transit on the
;; classpath — only calling the handler constructors does.

(ns eve.flat.transit
  (:require ; the alias is exercised on the other reader pass; the require loads on both 
   #_{:clj-kondo/ignore [:unused-namespace]}   [eve.flat.chunk :as chunk]
            ; the alias is exercised on the other reader pass; the require loads on both 
   #_{:clj-kondo/ignore [:unused-namespace]}   [eve.flat.freeze :as freeze]))

#?(:clj
   (do
     (defn- wh [tag rep]
       ((requiring-resolve 'cognitect.transit/write-handler)
        (if (fn? tag) tag (fn [_] tag)) rep))

     (defn- rh [f]
       ((requiring-resolve 'cognitect.transit/read-handler) f))

     (defn- cls [name] (Class/forName name))

     (def ^:private seq-classes
       ["eve.flat.chunk.FlatBatch" "eve.flat.chunk.FlatVec"
        "eve.flat.chunk.FlatColVec" "eve.flat.chunk.FlatList"
        "eve.flat.freeze.ConcatBatch" "eve.flat.freeze.BasedValue"])

     (defn plain-write-handlers
       "Class -> WriteHandler reps-ing each sequential flat
        handle as its plain logical content (a JSON array).
        Map/set handles travel under Transit's defaults (see
        the namespace header). FrozenValue dispatches on its
        opened form."
       []
       (into {}
             (concat
              (map (fn [c] [(cls c) (wh "array" vec)]) seq-classes)
              [[(cls "eve.flat.freeze.FrozenValue")
                (wh (fn [x] (if (sequential? (freeze/deliver-flat x)) "array" "map"))
                    (fn [x] (let [h (freeze/deliver-flat x)]
                              (if (sequential? h) (vec h) (into {} h)))))]])))

     (defn- seq-bytes
       "The framed stream bytes of a sequential flat value:
        one chunk travels framed too, so the read side has
        exactly one shape to open."
       [x]
       (let [h (cond
                 (instance? eve.flat.freeze.FrozenValue x) (freeze/deliver-flat x)
                 (instance? eve.flat.freeze.BasedValue x) (freeze/deliver-flat (freeze/plain-of x))
                 :else x)
             chunks (if (instance? eve.flat.freeze.ConcatBatch h)
                      (mapv chunk/bytes-of (.-handles h))
                      [(chunk/bytes-of h)])]
         (freeze/frame-stream chunks)))

     (defn flat-write-handlers
       "Class -> WriteHandler shipping sequential flat values
        AS flat: framed chunk streams under \"eve/flatseq\".
        A map-kind FrozenValue falls back to its content.
        Pair with flat-read-handlers."
       []
       (into {}
             (concat
              (map (fn [c] [(cls c) (wh "eve/flatseq" seq-bytes)])
                   seq-classes)
              [[(cls "eve.flat.freeze.FrozenValue")
                (wh (fn [x] (if (sequential? (freeze/deliver-flat x))
                              "eve/flatseq" "map"))
                    (fn [x] (if (sequential? (freeze/deliver-flat x))
                              (seq-bytes x)
                              (into {} (freeze/deliver-flat x)))))]])))

     (defn flat-read-handlers
       "Tag -> ReadHandler reopening the flat forms written
        by flat-write-handlers."
       []
       {"eve/flatseq" (rh (fn [bs] (freeze/open-stream (freeze/unframe-stream bs))))})))
