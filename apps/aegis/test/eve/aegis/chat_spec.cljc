(ns eve.aegis.chat-spec
  "The chat app's spec as data (a verbatim copy of src/aegis/chat/spec.vs with its comment lines removed, read as EDN,
   taken 2026-10-23 for the evaluator tests; the signed file stays the source of truth).")

(def spec
{:op :aegis/app
 :name "chat"


 :policy
 {:op :or
  :children
  [;; ── Branch 0: post a public message ───────────────────────────
   {:op :and
    :branch "post-message"
    :children [{:op :primitive :name :p/not-quarantined :args {}}
               {:op :primitive :name :p/merkle-tail-append
                :args {:path-vec [:rooms :public :messages]}}
               {:op :primitive :name :p/field-equals
                :args {:in [:new :rooms :public :messages :last :author]
                       :eq [:principal :username]}}]}

   {:op :and
    :branch "create-poll"
    :children [{:op :primitive :name :p/not-quarantined :args {}}
               {:op :primitive :name :p/merkle-tail-append
                :args {:path-vec [:rooms :public :polls]}}
               {:op :primitive :name :p/field-equals
                :args {:in [:new :rooms :public :polls :last :author]
                       :eq [:principal :username]}}]}

   {:op :and
    :branch "cast-vote"
    :children [{:op :primitive :name :p/merkle-tail-append
                :args {:path-vec [:rooms :public :votes]}}
               {:op :primitive :name :p/semaphore-membership
                :args {:path-vec [:rooms :public :votes]}}]}

   {:op :and
    :branch "add-member"
    :children [{:op :primitive :name :p/not-quarantined :args {}}
               {:op :primitive :name :p/key-assoc
                :args {:path-vec [:rooms :private :members]
                       :key :member-id}}]}

   {:op :and
    :branch "remove-member"
    :children [{:op :primitive :name :p/not-quarantined :args {}}
               {:op :primitive :name :p/key-dissoc
                :args {:path-vec [:rooms :private :members]
                       :key :member-id}}]}

   {:op :and
    :branch "post-announcement"
    :children [{:op :primitive :name :p/not-quarantined :args {}}
               {:op :primitive :name :p/merkle-tail-append
                :args {:path-vec [:rooms :announcements :messages]}}
               {:op :primitive :name :p/field-equals
                :args {:in [:new :rooms :announcements :messages :last :author]
                       :eq [:principal :username]}}
               {:op :primitive :name :p/remote-atom-state
                :args {:broker-symbolic-id "local"
                       :atom-symbolic-id "aegis.chat.credential-registry"
                       :slot 1}}]}

   {:op :and
    :branch "scratch-post"
    :children [{:op :primitive :name :p/merkle-tail-append
                :args {:path-vec [:scratch-spaces :posts]}}]}

   {:op :and
    :branch "private-message"
    :children [{:op :primitive :name :p/not-quarantined :args {}}
               {:op :primitive :name :p/merkle-tail-append
                :args {:path-vec [:rooms :private :messages]}}
               {:op :primitive :name :p/field-equals
                :args {:in [:new :rooms :private :messages :last :author]
                       :eq [:principal :username]}}]}

   {:op :and
    :branch "audit-log"
    :children [{:op :primitive :name :p/not-quarantined :args {}}
               {:op :primitive :name :p/merkle-tail-append
                :args {:path-vec [:audit-log]}}
               {:op :primitive :name :p/field-equals
                :args {:in [:new :audit-log :last :moderator]
                       :eq [:principal :username]}}]}

   {:op :and
    :branch "open-scratch-space"
    :children [{:op :primitive :name :p/not-quarantined :args {}}
               {:op :primitive :name :p/merkle-tail-append
                :args {:path-vec [:scratch-spaces :rooms]}}
               {:op :primitive :name :p/field-equals
                :args {:in [:new :scratch-spaces :rooms :last :owner]
                       :eq [:principal :username]}}]}]}


 :acls {:op :defacls
        :entries [{:path ["users"]
                   :config {:kind "per-key-principal"
                            :commitFn "sha256"}}
                  {:path ["rooms" "public" "messages"]
                   :config {:kind "group"
                            :groupDomain "_broker_defaults"
                            :groupName "trusted-anonymous"}}
                  {:path ["rooms" "public" "polls"]
                   :config {:kind "group"
                            :groupDomain "_broker_defaults"
                            :groupName "trusted-anonymous"}}
                  {:path ["rooms" "public" "votes"]
                   :config {:kind "group"
                            :groupDomain "_broker_defaults"
                            :groupName "trusted-anonymous"}}
                  {:path ["rooms" "public" "settings"]
                   :config {:kind "group"
                            :groupDomain "chat"
                            :groupName "moderators"}}
                  {:path ["rooms" "private" "members"]
                   :config {:kind "group"
                            :groupDomain "chat"
                            :groupName "room-admins"}}
                  {:path ["rooms" "private" "messages"]
                   :config {:kind "group"
                            :groupDomain "chat"
                            :groupName "private-members"}}
                  {:path ["rooms" "announcements" "messages"]
                   :config {:kind "group"
                            :groupDomain "chat"
                            :groupName "credential-holders"}}
                  {:path ["scratch-spaces" "rooms"]
                   :config {:kind "group"
                            :groupDomain "_broker_defaults"
                            :groupName "trusted-anonymous"}}
                  {:path ["audit-log"]
                   :config {:kind "group"
                            :groupDomain "chat"
                            :groupName "moderators"}}
                  {:path ["roles" "admins"]
                   :config {:kind "group"
                            :groupDomain "chat"
                            :groupName "admins"}}]}


 :groups {:op :defgroups
          :entries [{:domain "_broker_defaults" :name "trusted-anonymous"
                     :anonymity :ballot}
                    {:domain "chat" :name "moderators"}
                    {:domain "chat" :name "admins"}
                    {:domain "chat" :name "room-admins"}
                    {:domain "chat" :name "credential-holders"}
                    {:domain "chat" :name "private-members"
                     :mirror ["rooms" "private" "members"]}]}


 :lifecycle {:op :deflifecycle :mode :networked :min-posts-for-promotion 3}


 :nullifier-scopes {:op :defnullifierscopes
                    :default :per-epoch
                    :byPath [{:path ["rooms" "public" "votes"] :scope :per-poll}
                             {:path ["scratch-spaces" "posts"] :scope :per-session}]}


 :sessions {:op :defsessions
            :registry-atom "aegis.session-registry"
            :minting-mode :client-derived
            :max-lifetime-epochs 3600
            :max-active-per-principal 3}


 :rollups [{:op :defrollup
            :name "audit-log"
            :operator ":audit-operator"
            :atoms [":aegis.chat.public"]
            :upgradeable false
            :challenge-window 600}]
 :principals [":audit-operator"]
 :atom-owners {":aegis.chat.public" ":audit-operator"}


 :records {:op :defrecords
           :entries [{:path ["rooms" "public" "messages"]
                      :required [:id :author :ts :content_commitment :ciphertext :ivB64]
                      :optional [:attachment-cid]
                      :leaf [:id :author :ts :content_commitment :ciphertext :ivB64 :attachment-cid]}
                     {:path ["rooms" "public" "polls"]
                      :required [:id :author :ts :question :options] :optional []
                      :leaf [:id :author :ts :question :options]}
                     {:path ["rooms" "public" "votes"]
                      :required [:id :poll :choice :ts] :optional []
                      :leaf [:id :poll :choice :ts]
                      :scope-key :poll}
                     {:path ["rooms" "private" "messages"]
                      :required [:id :author :ts :content_commitment :ciphertext :ivB64]
                      :optional [:attachment-cid]
                      :leaf [:id :author :ts :content_commitment :ciphertext :ivB64 :attachment-cid]}
                     {:path ["rooms" "announcements" "messages"]
                      :required [:id :author :ts :content_commitment :ciphertext :ivB64]
                      :optional [:attachment-cid]
                      :leaf [:id :author :ts :content_commitment :ciphertext :ivB64 :attachment-cid]}
                     {:path ["scratch-spaces" "rooms"]
                      :required [:id :owner :ts :title] :optional []
                      :leaf [:id :owner :ts :title]}
                     {:path ["scratch-spaces" "posts"]
                      :required [:id :space :ts :text] :optional []
                      :leaf [:id :space :ts :text]}
                     {:path ["audit-log"]
                      :required [:id :moderator :action :target :ts] :optional []
                      :leaf [:id :moderator :action :target :ts]}]}})
