(ns eve.record-context
  "Read policy shared by record construction and adapters. No registry or cache.
   A durable record loads slab views, never a host collection materialization.")

(def ^:dynamic *slab-values* false)
