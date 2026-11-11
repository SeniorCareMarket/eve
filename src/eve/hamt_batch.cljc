(ns eve.hamt-batch
  "Bulk construction over already encoded HAMT entries [portable-hash key-bytes ...].
   No host hashing of values, slab reads, or intermediate path copies."
  (:require [eve.hamt-util :as hu]
            [eve.encoding :as enc]))

(defn unique-entries
  "Byte equality, not host value equality. Retain the last payload for a key."
  [entries]
  (into []
        (mapcat (fn [[_ bucket]]
                  (if (= 1 (count bucket))
                    bucket
                    (reduce (fn [out entry]
                            (if-let [i (first (keep-indexed
                                              (fn [i old]
                                                (when (enc/bytes= (nth old 1) (nth entry 1)) i))
                                              out))]
                              (assoc out i entry)
                              (conj out entry)))
                            [] bucket))))
        (group-by first entries)))

(defn build
  "Emit each final node once. Entries must have distinct encoded keys.
   bitmap! receives [data-bitmap child-bitmap data-entries child-offsets];
   collision! receives entries sharing all 32 hash bits. Numeric slot order
   is explicit, including slot 31 (the sign bit)."
  [entries shift bitmap! collision!]
  (if (> shift 30)
    (collision! entries)
    (let [groups (sort-by first (group-by #(hu/mask-hash (first %) shift) entries))
          [db nb data children]
          (reduce (fn [[db nb data children] [_ bucket]]
                    (let [bit (hu/bitpos (ffirst bucket) shift)]
                      (if (= 1 (count bucket))
                        [(bit-or db bit) nb (conj data (first bucket)) children]
                        [db (bit-or nb bit) data
                         (conj children (build bucket (+ shift 5) bitmap! collision!))])))
                  [0 0 [] []] groups)]
      (bitmap! db nb data children))))
