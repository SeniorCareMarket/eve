(ns eve.journal-file
  "The commit journal's file handle (eve.atom's durability mode) on a
   host with no journal-file implementation: opening a journal domain
   here fails honestly. (Before the eve.journal-file seam, a :journal?
   open on such a host silently ran WITHOUT the journal — a durability
   promise the seat could not keep.) Hosts with an implementation live
   in adapters/journal-file/{java,node,clr,python}.")

(defn- unsupported []
  (throw (ex-info "eve.journal-file: journal domains are not supported on this host"
                  {})))

(defn open-journal [_path] (unsupported))
(defn with-journal-lock [_h _f] (unsupported))
(defn journal-record [_root-r] (unsupported))
(defn journal-record-of [_epoch _table] (unsupported))
(defn journal-scan [_h] (unsupported))
(defn journal-append! [_h _bytes] (unsupported))
(defn journal-write-at! [_h _bytes _pos] (unsupported))
(defn journal-truncate! [_h] (unsupported))
(defn journal-force! [_h _full?] (unsupported))
(defn journal-close! [_h] nil)
