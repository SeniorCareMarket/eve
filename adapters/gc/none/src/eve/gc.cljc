(ns eve.gc
  "eve's GC hook (feature gc, adapters/README.md) on a host that has none: eve.view's reads are read-end C there (no
   pin of their own; each access pins briefly, and the read ends at the domain's next commit).")

(defn available? [] false)
(defn sweep! [] nil)
(defn token [_release] nil)
