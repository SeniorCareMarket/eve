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

(defn open-journal [path] (unsupported))
(defn with-journal-lock [h f] (unsupported))
(defn journal-record [root-r] (unsupported))
(defn journal-record-of [epoch table] (unsupported))
(defn journal-scan [h] (unsupported))
(defn journal-append! [h bytes] (unsupported))
(defn journal-write-at! [h bytes pos] (unsupported))
(defn journal-truncate! [h] (unsupported))
(defn journal-force! [h full?] (unsupported))
(defn journal-close! [h] nil)
