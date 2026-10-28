(ns eve.platform
  "What eve's shared code needs that is the same on every host:
   - throw!: throw an error with a message
   - hash-ordered: hash an ordered collection (vec, list)
   - hash-unordered: hash an unordered collection (map, set)
   both through eve.core, the vocabulary every EVE value answers: a host whose
   core cannot walk an eve type (babashka, ValueScript) walks it through
   eve.core's protocols, every other host takes its core's own

   The host's own primitives — bytes, UTF-8, IEEE-754 bits, its numbers,
   instants, uuids, exceptions, map entries — are eve.encoding's, an adapter
   (eve/adapters/encoding/, adapters/README.md)."
  (:require [eve.host]
            [eve.core]))

(defn throw!
  "Throw an error with the given message."
  [msg]
  (throw (ex-info msg {})))

(defn int32
  "x's low 32 bits as a signed integer (two's complement), on every host
   (JavaScript's bit-and already answers int32; the others' answers the
   unsigned word)."
  [x]
  (let [u (bit-and x 0xFFFFFFFF)]
    (if (> u 2147483647) (- u 4294967296) u)))

(defn hash-ordered
  "Hash an ordered collection (vec, list, seq): Murmur3's ordered hash, as
   eve.core's hash-ordered-coll answers it (clojure.core's needs an Iterable,
   which an eve type on babashka or ValueScript is not: it threw on bb and
   hashed VS's own representation of the type on VS — measured 2026-10-19)."
  [coll]
  (eve.core/hash-ordered-coll coll))

(defn hash-unordered
  "Hash an unordered collection (map, set): Murmur3's unordered hash, as
   eve.core's hash-unordered-coll answers it."
  [coll]
  (eve.core/hash-unordered-coll coll))
