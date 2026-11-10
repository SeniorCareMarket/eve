(ns datascript.storage
  "PORT (PORTING-DS.md): vanilla's storage layer serializes DBs through
   persistent-sorted-set 0.3.x's storage-capable sets (a StorageAdapter
   rides inside each index set; the JVM form's classes do the work). On
   Eve that layer has no counterpart and needs none: a DB is an Eve
   value, and storing it IS placing it in an Eve atom — a disk atom for
   durability, a shared disk atom for cross-process/cross-host access.
   This namespace keeps vanilla's protocol and the entry points
   datascript.core touches, with Eve semantics:

   - maybe-adapt-storage: identity when no :storage opt is given (the
     overwhelmingly common path — it sits inside empty-db/init-db); an
     explicit :storage opt raises, with the Eve guidance, rather than
     being silently ignored.
   - storage / storage-adapter: nil (a DB carries no storage adapter;
     its residence is whichever Eve atom holds it).
   - store/restore/addresses/collect-garbage/file-storage: raise with
     the Eve guidance. The lane's drivers demonstrate the Eve form:
     (eve.atom/lookup-or-create-mmap-atom! dir) + swap!/deref.")

#?(:lpy
   ;; basilisk's defprotocol calls nth on the option keyword
   ;; (:extend-via-metadata) — the options map form is not supported
   ;; by its macro; the protocol is never extended via metadata on
   ;; this seat (storage is an Eve atom here), so the option is
   ;; simply absent.
   (defprotocol IStorage
     (-store [_ addr+data-seq])
     (-restore [_ addr])
     (-list-addresses [_])
     (-delete [_ addrs-seq]))
   :default
   (defprotocol IStorage
     :extend-via-metadata true
     (-store [_ addr+data-seq])
     (-restore [_ addr])
     (-list-addresses [_])
     (-delete [_ addrs-seq])))

(defn- eve-storage-guidance []
  (ex-info "DataScript storage on Eve is an Eve atom: place the DB value in a (disk) Eve atom; there is no separate storage adapter."
           {:eve/storage :atom}))

(defn maybe-adapt-storage [opts]
  (if (contains? opts :storage)
    (throw (eve-storage-guidance))
    opts))

(defn storage-adapter [_db] nil)

(defn storage [_db] nil)

(defn store [& _args] (throw (eve-storage-guidance)))

(defn restore [& _args] (throw (eve-storage-guidance)))

(defn addresses [& _dbs] (throw (eve-storage-guidance)))

(defn collect-garbage [& _args] (throw (eve-storage-guidance)))

(defn file-storage [& _args] (throw (eve-storage-guidance)))

;; Remaining vars datascript.conn / datascript.db reference from vanilla's
;; storage implementation (tail-store/restore machinery). Same disposition:
;; they exist so the namespaces compile; calling one raises the guidance.
(defn store-impl! [& _args] (throw (eve-storage-guidance)))

(defn restore-impl [& _args] (throw (eve-storage-guidance)))

(defn store-tail [& _args] (throw (eve-storage-guidance)))

(defn db-with-tail [& _args] (throw (eve-storage-guidance)))
