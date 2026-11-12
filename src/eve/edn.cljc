(ns eve.edn
  "Eve's EDN reader: one entry, every host.

   read-string parses EDN with Clojure's semantics and honors Eve's
   registered data readers — the tags eve.deftype / eve.record
   declarations and eve.numeric install in eve.deftype.registry —
   merged (eve.type-readers/reader-options) under the ambient
   *data-readers* and the caller's :readers. Bare Clojure number tokens
   (123N, 1/3, 1.2300M) read as Eve's exact numbers on every host,
   including hosts whose own reader would round them to a double: the
   read goes through eve.numeric/read-edn's token rewrite, so a form
   like {:x 1/3} means the same thing everywhere.

   This is the reader data code names (eve.db.ds's DB-from-EDN contract,
   its rules-from-string, its serialize thaw): an Eve surface, never a
   host reader chosen per platform."
  (:require ;; bb/SCI: the host-type presentation must be installed before
            ;; anything else in this graph loads — a consumer whose first
            ;; Eve require is eve.edn (eve.db.ds.core) otherwise reaches
            ;; its own host deftypes with the presentation missing and
            ;; their factories die (\"No value supplied for key:
            ;; clojure.lang.Atom\" in ds's Conn). First require, :bb only.
            #?(:bb [eve.host-completion])
            [eve.numeric :as numeric]
            [eve.type-readers :as type-readers]
            #?(:cljs [cljs.reader :as cljs-reader]
               :lpy [clojure.edn :as host-edn]
               :default [clojure.edn :as host-edn])))

(defn read-string
  "Read one EDN object from the string s. Options are clojure.edn's
   (:readers, :default, :eof), with Eve's registered readers merged in
   (a per-call reader always wins)."
  ([s] (read-string {} s))
  ([opts s]
   (numeric/read-edn s (type-readers/reader-options opts))))

(defn read
  "Read one EDN object from a reader (clojure.edn/read's contract: opts,
   then the reader). Registered readers are honored exactly as in
   read-string. A stream cannot be token-rewritten, so bare exact-number
   tokens in a stream are the host reader's own business — JVM and bb
   read them natively; where a token must survive a host that rounds,
   read the text with read-string instead."
  ([reader] (read {} reader))
  ([opts reader]
   #?(:cljs (throw (ex-info "eve.edn/read needs a string on this host; use eve.edn/read-string"
                            {:eve.edn/error :no-stream-reader}))
      :lpy (host-edn/read reader (type-readers/reader-options opts))
      :default (host-edn/read (type-readers/reader-options opts) reader))))

#?(:cljs
   (defn register-tag-parser!
     "Register tag's reader with the host reader — ClojureScript is the
      one host whose reader keeps a table of its own, so a consumer with
      its own tags (eve.db.ds's data-readers) registers them here. On
      every other host this is unnecessary: read-string consults
      eve.deftype.registry and *data-readers* on each call."
     [tag f]
     (cljs-reader/register-tag-parser! tag f)))
