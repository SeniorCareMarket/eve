(ns eve.process-slab
  "The process slab where a host's domains are eve.deftype-proto.alloc's
   JvmSlabCtx (every host but ClojureScript and nbb, whose module slab is
   adapters/slab/module): nothing to register — alloc's own heap domain is
   this process's, its openers open JvmSlabCtx domains.")

(defn init!
  "Ready this process's slab: alloc's heap domain needs nothing (the module
   slab's init! answers a promise of its WASM upgrades). Answers nil."
  [& _opts]
  nil)
