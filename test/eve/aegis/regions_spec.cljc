(ns eve.aegis.regions-spec
  "The read-regions spike's app spec (aegis/docs/read-regions-spike/REPORT.md): a private room whose members map IS
   its readers' roster (:mirror), a lockbox branch only room admins write, and a message branch held to the region's
   epoch. Shared by eve.aegis.regions-claims-test and the broker end-to-end test's babashka client
   (test/aegis/helpers/regions_client.clj), which hands its EDN text to the broker as the atom's app spec.")

(def spec
  {:op :aegis/app
   :policy {:op :or
            :children [{:op :and :branch "add-member"
                        :children [{:op :primitive :name :p/not-quarantined :args {}}
                                   {:op :primitive :name :p/key-assoc :args {:path-vec [:rooms :private :members]}}]}
                       {:op :and :branch "remove-member"
                        :children [{:op :primitive :name :p/not-quarantined :args {}}
                                   {:op :primitive :name :p/key-dissoc :args {:path-vec [:rooms :private :members]}}]}
                       {:op :and :branch "write-private-lockbox"
                        :children [{:op :primitive :name :p/not-quarantined :args {}}
                                   {:op :primitive :name :p/lockbox-write :args {:path-vec [:keys :private] :region "private"}}]}
                       {:op :and :branch "private-message"
                        :children [{:op :primitive :name :p/not-quarantined :args {}}
                                   {:op :primitive :name :p/merkle-tail-append :args {:path-vec [:rooms :private :messages]}}
                                   {:op :primitive :name :p/field-equals
                                    :args {:in [:new :rooms :private :messages :last :author] :eq [:principal :username]}}
                                   {:op :primitive :name :p/region-epoch :args {:region "private"}}]}]}
   :acls {:op :defacls
          :entries [{:path ["rooms" "private" "members"] :config {:kind "group" :groupDomain "chat" :groupName "room-admins"}}
                    {:path ["rooms" "private" "messages"] :config {:kind "group" :groupDomain "chat" :groupName "private-members"}}
                    {:path ["keys" "private"] :config {:kind "group" :groupDomain "chat" :groupName "room-admins"}}]}
   :groups {:op :defgroups
            :entries [{:domain "chat" :name "room-admins"}
                      {:domain "chat" :name "private-members" :mirror ["rooms" "private" "members"]}]}
   :nullifier-scopes {:op :defnullifierscopes :default :per-epoch}
   :records {:op :defrecords
             :entries [{:path ["rooms" "private" "messages"]
                        :required [:id :author :ts :key_epoch :iv :ct] :optional [:blob_cid :dek_iv :dek_ct]
                        :leaf [:id :author :ts :key_epoch :iv :ct :blob_cid :dek_iv :dek_ct]}]}
   :read-regions {:op :defreadregions
                  :entries [{:name "private" :paths [["rooms" "private" "messages"]]
                             :readers {:groupDomain "chat" :groupName "private-members"}
                             :lockbox ["keys" "private"] :history :grant}]}})
