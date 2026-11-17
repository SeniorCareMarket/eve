(ns eve.scittle-seat
  "Eve's scittle seat plugin — the seat's SCI-context configuration.

   Stock scittle's SCI context omits the cljs.core/MapEntry class:
   its built-in cljs.core namespace map carries no entry SCI's
   analyzer accepts for the (MapEntry. k v) constructor form, and
   the built-in namespace's interns cannot be merged into after the
   fact. Eve's adapters/encoding/js instantiates MapEntry in its
   map-entry fn, so eve.encoding — and with it eve.net.bytes,
   eve.net.json, eve.net.portable-crypto and eve.crypto — cannot
   load on this seat without the binding.

   SCI resolves an unqualified (Type. ...) form through the
   context's :classes map. nbb's context carries these class
   entries from its own build; this plugin is the same layer for
   Eve's scittle seat: a module of the seat's application build
   (stock scittle, unmodified, + this namespace), registering the
   host's own cljs.core/MapEntry class — the identical class object
   scittle's internal cljs.core constructs and predicates on, so
   map-entry?, key/val and equality behave exactly as in the
   compiled build.

   Stock scittle likewise registers no cljs.reader namespace for
   programs: (require 'cljs.reader) throws \"Could not find
   namespace: cljs.reader\", though the compiled cljs.reader is in
   the bundle (SCI's own cljs.core/read-string entry is a handle
   onto it — measured: identical results and error strings, and a
   tag parser registered through the compiled register-tag-parser!
   is visible to cljs.core/read-string, i.e. one shared tag table).
   Only the namespace registration is missing. A plugin's
   :namespaces merge lands for a NEW namespace (merging into the
   built-in cljs.core map does not — the MapEntry probes), so this
   plugin registers cljs.reader's public vars, compiled, under
   their own namespace: read-string is then the compiled build's
   reader itself, over the host's data types, with its tag table
   shared with cljs.core/read-string exactly as in the compiled
   build. The two dynamic vars are registered by value — the atoms
   they hold — so a program's cljs.reader/*tag-table* in value
   position is the atom itself, exactly as in the compiled build,
   and the compiled register-tag-parser! mutates that same atom.
   (Registering the Var objects instead was measured wrong in this
   lane's first build: SCI's deref of the registered Var did not
   reach the atom, and a program's @cljs.reader/*tag-table* did not
   see registered tags.)

   Load order: this module's script loads AFTER scittle.js and
   BEFORE any program (application/x-scittle) tags are evaluated;
   registration merges into the live context via
   scittle.core/register-plugin!."
  (:require [cljs.reader :as reader]
            [scittle.core :as scittle]))

(scittle/register-plugin!
 ::seat
 {:classes {'MapEntry cljs.core/MapEntry}
  :namespaces {'cljs.reader {'read-string reader/read-string
                              'read reader/read
                              'register-tag-parser! reader/register-tag-parser!
                              'deregister-tag-parser! reader/deregister-tag-parser!
                              'register-default-tag-parser! reader/register-default-tag-parser!
                              'deregister-default-tag-parser! reader/deregister-default-tag-parser!
                              'parse-timestamp reader/parse-timestamp
                              'parse-and-validate-timestamp reader/parse-and-validate-timestamp
                              '*tag-table* reader/*tag-table*
                              '*default-data-reader-fn* reader/*default-data-reader-fn*}}})
