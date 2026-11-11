(ns eve.portable-surface-test
  (:refer-clojure :exclude [deftype defprotocol])
  (:require #?(:cljs [cljs.test :refer [deftest is testing]]
               :lpy [basilisp.test :refer [deftest is testing]]
               :default [clojure.test :refer [deftest is testing]])
            [eve.deftype :refer [deftype eve-deftype]]
            [eve.deftype.registry :as registry]
            [eve.deftype-proto.serialize :as serialize]
            [eve.deftype-proto.macros :as slab]
            [eve.map-entry :as me]
            [eve.list :as el]
            [eve.protocol :refer [defprotocol]]
            [eve.portable-type-fixture :as fixture]
            [eve.type :as t]
            [eve.compare :as c]
            [eve.math :as math]
            [eve.encoding :as enc]))

(defprotocol PartialProtocol
  :extend-via-metadata true
  (-value [x])
  (-missing [x y]))

(deftype Partial [v]
  PartialProtocol
  (-value [_] v))

(deftype MetaPartial [v m]
  PartialProtocol
  (-value [_] v)
  clojure.lang.IMeta
  (meta [_] m)
  clojure.lang.IObj
  (withMeta [_ new-meta] (MetaPartial. v new-meta)))

(deftest type-identity
  (is (instance? Partial (->Partial 1)))
  (let [v (fixture/->Variable nil)
        k (t/token fixture/Variable)]
    (is (= :eve.portable-type-fixture/Variable k))
    (is (= k (t/type-token v)))
    (is (t/instance? k v))
    (is (t/instance? fixture/Variable v))
    (is (not (t/instance? (t/token fixture/Constant) v)))
    (is (not (t/instance? k nil)))
    (is (= :variable (condp = (t/type-token v) (t/token fixture/Variable) :variable :other)))
    (is (= :variable (case (t/type-token v) :eve.portable-type-fixture/Variable :variable :other)))))

(deftype Variable [v])

(eve-deftype RegisteredSlab [^:int32 n])
(slab/host-deftype RegisteredHost [n])

(deftest existing-registry-is-the-only-identity-state
  (doseq [[v token storage] [[(->Partial 1) :eve.portable-surface-test/Partial :process-local]
                            [(->RegisteredHost 1) :eve.portable-surface-test/RegisteredHost :process-local]
                            [(->RegisteredSlab 1) :eve.portable-surface-test/RegisteredSlab :slab]
                            [(me/map-entry :a 1) :eve.map-entry/MapEntry :slab]
                            [(el/eve-list [1]) :eve.list/EveList :slab]]]
    (let [key (subs (str token) 1)
          entry (registry/lookup-type key)]
      (is (= token (t/type-token v)))
      (is (= key (:type-key entry)))
      (is (= (type (or (serialize/presented-backing v) v)) (:host-type entry)))
      (is (= storage (:storage entry)))
      (is (t/instance-of? token v))))
  (is (not= (t/type-token (->Variable 1))
            (t/type-token (fixture/->Variable 1))))
  (is (nil? (t/type-token nil)))
  (is (nil? (t/type-token {})))
  (is (nil? (t/type-token (first {:a 1}))))
  (testing "updates on an existing entry preserve reader/printer and layout fields"
    (let [key "eve.portable-surface-test/Partial"
          before (registry/lookup-type key)]
      (try
        (registry/register-type! key {:reader ::reader :printer ::printer})
        (registry/register-host-type! key (:host-type before) {:storage :process-local})
        (is (= ::reader (:reader (registry/lookup-type key))))
        (is (= ::printer (:printer (registry/lookup-type key))))
        (is (= :eve.portable-surface-test/Partial (t/type-token (->Partial 2))))
        (finally (swap! registry/registry assoc key before))))))

#?(:cljs nil
   :default (extend (type {}) PartialProtocol
              {:-value (fn [_] 7)
               :-missing (fn [_ _] 8)}))

(deftest partial-protocol-and-metadata
  (is (thrown? #?(:cljs js/Error :lpy python/Exception :default Throwable)
               (-missing (->Partial 0) 1)))
  #?(:cljs nil :default (is (= 7 (-value {}))))
  (is (= 42 (-value (->Partial 42))))
  (is (= 42 (-value (->MetaPartial 42 {'eve.portable-surface-test/-value (fn [_] 99)}))))
  (is (= 43 (-value (with-meta {} {'eve.portable-surface-test/-value (fn [_] 43)}))))
  (is (= 44 (-missing (with-meta {} {'eve.portable-surface-test/-missing (fn [_ x] x)}) 44))))

(deftype Ordered [k]
  c/IOrderKey
  (-order-key [_] k))

(deftest mixed-data-order
  (let [values [nil false true -1 0 1 1.5 ##Inf ##NaN "a" :a :z/a 'a 'z/a
                #uuid "80000000-0000-0000-0000-000000000000"
                #uuid "00000000-0000-0000-0000-000000000000"
                #inst "2020-01-01" [] [1] [1 2] [2 0] '(1 2) {} {:a 1} #{} #{1}]]
    (doseq [a values b values]
      (is (= (- (c/compare a b)) (c/compare b a))))
    (let [ordered (vec (sort c/compare values))]
      (doseq [i (range (count ordered)) j (range i (count ordered))]
        (is (<= (c/compare (nth ordered i) (nth ordered j)) 0)))))
  (is (neg? (c/compare (->Ordered [1]) (->Ordered [2]))))
  (is (neg? (c/compare [9] [1 2])))
  (is (neg? (c/compare :a :z/a)))
  (is (zero? (c/compare {:a 1 :b 2} {:b 2 :a 1})))
  (is (zero? (c/compare #{1 "a"} #{"a" 1})))
  (is (neg? (c/compare #uuid "80000000-0000-0000-0000-000000000000"
                       #uuid "00000000-0000-0000-0000-000000000000"))))

(defn- bits [x]
  (mapv #(bit-and 255 %) (seq (enc/f64-le-bytes x))))

(def sqrt-bit-fixtures
  ;; IEEE bit fixtures, rather than decimal source literals: both SCI and
  ;; Basilisp readers normalize/round some literal edge cases before sqrt.
  ;; The JVM and bb additionally check every expected fixture against Math.
  [
    [[0 0 0 0 0 0 0 0] [0 0 0 0 0 0 0 0]]
    [[0 0 0 0 0 0 0 128] [0 0 0 0 0 0 0 128]]
    [[1 0 0 0 0 0 0 0] [0 0 0 0 0 0 96 30]]
    [[255 255 255 255 255 255 15 0] [255 255 255 255 255 255 255 31]]
    [[0 0 0 0 0 0 16 0] [0 0 0 0 0 0 0 32]]
    [[255 255 255 255 255 255 239 127] [255 255 255 255 255 255 239 95]]
    [[0 0 0 0 0 0 240 63] [0 0 0 0 0 0 240 63]]
    [[0 0 0 0 0 0 0 64] [205 59 127 102 158 160 246 63]]
    [[0 0 0 0 0 0 8 64] [170 76 88 232 122 182 251 63]]
    [[0 0 0 0 0 0 16 64] [0 0 0 0 0 0 0 64]]
    [[0 0 0 0 0 192 28 64] [216 92 139 155 150 114 5 64]]
    [[17 60 161 133 203 149 32 94] [221 75 81 182 150 9 7 79]]
    [[223 187 149 222 79 244 95 103] [163 254 22 37 124 156 166 83]]
    [[194 67 168 77 210 161 241 82] [38 208 25 181 214 203 112 73]]
    [[74 213 85 1 172 88 42 22] [132 19 246 77 49 9 13 43]]
    [[40 116 15 242 37 184 178 104] [56 66 107 8 108 78 81 84]]
    [[174 171 31 240 159 51 197 8] [103 195 188 218 16 12 90 36]]
    [[87 240 105 235 115 125 172 47] [178 220 146 73 174 49 206 55]]
    [[216 150 167 93 150 225 169 30] [54 13 56 161 71 199 76 47]]
    [[166 182 85 116 8 0 138 17] [134 140 239 244 47 216 188 40]]
    [[104 212 185 154 122 111 188 106] [174 171 58 160 120 84 85 85]]
    [[178 131 9 153 255 183 69 89] [20 62 216 170 227 92 154 76]]
    [[196 165 171 255 100 2 46 79] [193 67 24 56 27 253 142 71]]
    [[26 237 53 244 188 140 32 113] [15 142 217 142 75 3 135 88]]
    [[141 210 72 226 219 242 57 117] [197 184 93 52 61 96 148 90]]
    [[17 199 205 83 220 134 180 35] [252 212 201 0 99 31 210 49]]
    [[111 187 136 1 43 132 248 110] [7 176 12 170 57 206 115 87]]
    [[253 37 122 48 227 223 90 10] [177 201 53 78 124 188 36 37]]
    [[168 10 107 245 205 142 32 73] [161 177 132 79 187 4 135 68]]
    [[251 144 61 169 62 72 106 56] [160 83 66 146 34 0 45 60]]
    [[236 153 163 121 52 96 41 58] [130 143 217 0 254 126 12 61]]
    [[46 10 24 245 118 67 223 70] [17 129 139 205 146 93 102 67]]
    [[213 153 191 125 246 73 214 83] [206 56 102 181 107 226 226 73]]
    [[155 227 50 117 158 129 75 28] [75 212 27 119 15 171 29 46]]
    [[85 163 108 209 103 148 246 104] [206 93 190 199 218 1 115 84]]
    [[247 254 85 6 0 235 223 1] [213 201 135 124 48 153 230 32]]
    [[2 145 76 233 44 249 204 68] [54 173 237 101 247 114 94 66]]
    [[224 32 12 145 80 162 168 33] [34 45 185 231 148 19 204 48]]
    [[118 49 3 182 254 186 64 45] [194 172 8 88 94 35 151 54]]
    [[111 239 123 242 98 118 48 0] [240 21 108 136 197 58 16 32]]
    [[217 74 55 250 49 112 84 116] [229 128 1 220 94 21 34 90]]
    [[75 86 139 175 183 100 190 18] [11 159 134 6 86 13 86 41]]
    [[173 228 168 204 21 194 152 117] [224 220 232 130 44 231 195 90]]])

(deftest exact-sqrt
  (doseq [[input expected] sqrt-bit-fixtures]
    (let [x (enc/read-f64-le (enc/bytes-of input) 0)]
      (is (= expected (bits (math/sqrt x))))
      #?(:clj (is (= expected (bits (Math/sqrt x)))))))
  (is (not (= (math/sqrt -1.0) (math/sqrt -1.0))))
  (is (not (= (math/sqrt ##NaN) (math/sqrt ##NaN)))))
