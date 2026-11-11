(ns eve.bb-logical-protocol-test
  "SCI map-mode dispatch, deliberately isolated from e3's auto-registration tests."
  (:require [clojure.test :refer [deftest is]]
            [eve.deftype :refer [deftype]]
            [eve.deftype.registry :as registry]
            [eve.core :as e]))

(declare ->MapLikeOne ->MapLikeTwo)

(deftype MapLikeOne [v m]
  clojure.lang.ILookup
  (valAt [_ k] (when (= k :v) v))
  (valAt [_ k nf] (if (= k :v) v nf))
  clojure.lang.Associative
  (containsKey [_ k] (= k :v))
  (assoc [_ k value] (if (= k :v) (->MapLikeOne value m) (throw (ex-info "fixture key" {}))))
  clojure.lang.Counted
  (count [_] 1)
  clojure.lang.IMeta
  (meta [_] m)
  clojure.lang.IObj
  (withMeta [_ metadata] (->MapLikeOne v metadata)))

(deftype MapLikeTwo [v m]
  clojure.lang.ILookup
  (valAt [_ k] (when (= k :v) v))
  (valAt [_ k nf] (if (= k :v) v nf))
  clojure.lang.Associative
  (containsKey [_ k] (= k :v))
  (assoc [_ k value] (if (= k :v) (->MapLikeTwo value m) (throw (ex-info "fixture key" {}))))
  clojure.lang.Counted
  (count [_] 1)
  clojure.lang.IMeta
  (meta [_] m)
  clojure.lang.IObj
  (withMeta [_ metadata] (->MapLikeTwo v metadata)))

;; The extension layer consumes the canonical existing entry. e3 separately
;; tests automatic producer registration; these fixtures exercise that API,
;; not a replacement registry or a production manual registration workaround.
(registry/register-type! "eve.bb-logical-protocol-test/MapLikeOne"
  {:type-key "eve.bb-logical-protocol-test/MapLikeOne" :host-type MapLikeOne})
(registry/register-type! "eve.bb-logical-protocol-test/MapLikeTwo"
  {:type-key "eve.bb-logical-protocol-test/MapLikeTwo" :host-type MapLikeTwo})

(defprotocol Flavor (flavor [this]))
(extend-type MapLikeOne Flavor (flavor [this] [:one (e/get this :v)]))
(extend-type MapLikeTwo Flavor (flavor [this] [:two (e/get this :v)]))
(extend-type String Flavor (flavor [this] [:native this]))

(deftest registered-map-identities-do-not-collapse-to-scimap
  (let [one (->MapLikeOne 1 nil) two (->MapLikeTwo 2 nil)]
    (is (= "babashka.impl.SciMap" (.getName (class one))))
    (is (= (class one) (class two)))
    (is (= [:one 1] (flavor one)))
    (is (= [:two 2] (flavor two)))
    (is (= [:one 3] (flavor (e/assoc one :v 3))))
    (is (= [:two 2] (flavor (e/with-meta two {:m true}))))
    (is (= [:native "host"] (flavor "host")))))
