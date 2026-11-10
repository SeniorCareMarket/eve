(ns eve.host-completion
  "What eve.host loads first to make the host's core Clojure's (the core
   completion feature, adapters/completion/). A core that is Clojure's needs
   nothing: this namespace.

   The arithmetic contract every host's core answers to (doc/
   arithmetic-overflow.md; the operator's decision, 2026-11-07): checked
   + - * throw ArithmeticException on i64 overflow, +' -' *' promote,
   unchecked-* wrap — the JVM's clojure.core semantics, on every host.
   A core that is Clojure's already implements it (the JVM's, babashka's,
   ClojureCLR's and jolt's + is clojure.lang.Numbers', which throws), so
   here there is nothing to bind. A host whose core answers otherwise
   carries the binding in its own completion (jank's promoting core in
   adapters/completion/jank, basilisp's in adapters/completion/basilisp,
   glojure's in adapters/completion/glojure), and a host with no lever
   is a named finding in that doc, never a quiet divergence.")
