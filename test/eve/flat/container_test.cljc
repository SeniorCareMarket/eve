;; Lane C3 tests — one per requirement of IMPL-PLAN §3.3 lane C3.
;; The chunk payloads used here are TEST SCAFFOLDING: a toy sorted key->value chunk with a
;; FORMAT-V1 §2 header, standing in for C1's packed chunk until lane C1 lands. The container
;; itself (src/eve/flat/container.cljc) reads only the §2 header fields (magic, version,
;; kind, length) of any chunk and never interprets a payload; the toy reader below plays the
;; part of C1's reader, and its open counter is how C3.1 proves exactly one chunk is opened.
(ns eve.flat.container-test
  (:require [clojure.test :refer [deftest is testing]]
            [eve.flat.container :as cont])
  ; the Arrays import is used below: the bare class name sits in the config's host-class unresolved-symbol exclusion (for the seats that reach it without an import), which blinds kondo to the import's use
  #?(:clj (:import #_{:clj-kondo/ignore [:unused-import]} [java.util Arrays])))

;; ---------------------------------------------------------------------------
;; scaffolding: unsigned byte compare, toy chunks, synthetic image units
;; ---------------------------------------------------------------------------

(defn- ubyte [^bytes b i] (bit-and (long (aget b (int i))) 0xFF))

(defn- kbytes
  "A test key: a 4-byte big-endian unsigned int. The directory compares key bytes unsigned
   lexicographically (Eve's canonical order); the toy uses fixed-width ints so byte order
   and numeric order agree."
  [k]
  (let [b (#_{:clj-kondo/ignore [:unresolved-symbol]} byte-array 4)]
    (aset b 0 (unchecked-byte (bit-shift-right k 24)))
    (aset b 1 (unchecked-byte (bit-shift-right k 16)))
    (aset b 2 (unchecked-byte (bit-shift-right k 8)))
    (aset b 3 (unchecked-byte k))
    b))

(defn- put-u32 [^bytes b off v]
  (dotimes [i 4] (aset b (+ off i) (unchecked-byte (bit-shift-right v (* 8 i))))))

(defn- put-header
  "A FORMAT-V1 §2 chunk header in the first 32 bytes of b."
  [^bytes b {:keys [kind length root nsym symtab shapetab]}]
  (System/arraycopy (.getBytes "EVS1" "US-ASCII") 0 b 0 4)
  (aset b 4 (byte 1))                       ;; version
  (aset b 5 (unchecked-byte kind))
  (put-u32 b 8 length)
  (put-u32 b 12 (or root 0))
  (when nsym (aset b 16 (unchecked-byte (bit-and nsym 0xFF))) (aset b 17 (unchecked-byte (bit-shift-right nsym 8))))
  (put-u32 b 20 (or symtab 0))
  (put-u32 b 24 (or shapetab 0)))

(defn- varint-bytes [v]
  (loop [v v out []]
    (if (< v 128)
      (conj out (unchecked-byte v))
      (recur (bit-shift-right v 7) (conj out (unchecked-byte (bit-or (bit-and v 127) 128)))))))

(defn toy-chunk
  "A chunk (kind 0) whose payload is: [varint n] then n sorted entries
   [varint klen][key bytes][varint vlen][value UTF-8 bytes]. Root = 32 (the payload)."
  [entries]
  (let [payload (byte-array
                 (into []
                       (concat (varint-bytes (count entries))
                               (mapcat (fn [[k v]]
                                         (let [vb (.getBytes ^String v "UTF-8")]
                                           (concat (varint-bytes (alength k)) k
                                                   (varint-bytes (alength vb)) vb)))
                                       entries))))
        total (* 8 (quot (+ 32 (alength payload) 7) 8))
        b (byte-array total)]
    (put-header b {:kind 0 :length total :root 32})
    (System/arraycopy payload 0 b 32 (alength payload))
    b))

(defn- read-varint [^bytes b i]
  (loop [i i sh 0 v 0]
    (let [x (ubyte b i)]
      (if (< x 128) [(bit-or v (bit-shift-left x sh)) (inc i)]
          (recur (inc i) (+ sh 7) (bit-or v (bit-shift-left (bit-and x 127) sh)))))))

(defn toy-open
  "The stand-in for C1's chunk reader. Increments counter, parses the toy payload."
  [counter]
  (fn [^bytes b]
    (swap! counter inc)
    (let [[n p] (read-varint b 32)]
      (loop [i 0 p p entries []]
        (if (== i n)
          {:chunk-bytes b :entries entries}
          (let [[kl p] (read-varint b p)
                k (Arrays/copyOfRange b (int p) (int (+ p kl)))
                [vl p] (read-varint b (+ p kl))
                v (#_{:clj-kondo/ignore [:unresolved-symbol]} String. b (int p) (int vl) "UTF-8")]
            (recur (inc i) (+ p vl) (conj entries [k v]))))))))

(defn toy-lookup
  "Binary search over an opened toy chunk's entries by key bytes."
  [opened key]
  (let [entries (:entries opened)]
    (loop [lo 0 hi (dec (count entries))]
      (when (<= lo hi)
        (let [mid (quot (+ lo hi) 2)
              [k v] (nth entries mid)
              c (cont/compare-key-bytes key k)]
          (cond (zero? c) v
                (neg? c) (recur lo (dec mid))
                :else (recur (inc mid) hi)))))))

(defn- entries-for [lo hi] (mapv (fn [k] [(kbytes k) (str "v-" k)]) (range lo hi)))

(defn- three-chunk-container []
  (cont/make-container
   [{:bytes (toy-chunk (entries-for 10 20)) :first-key (kbytes 10)}
    {:bytes (toy-chunk (entries-for 20 30)) :first-key (kbytes 20)}
    {:bytes (toy-chunk (entries-for 30 40)) :first-key (kbytes 30)}]
   {:chunk 0 :offset 32}))

;; ---------------------------------------------------------------------------
;; C3.1 — directory-opens-one-chunk
;; ---------------------------------------------------------------------------

(deftest directory-opens-one-chunk
  (let [c (three-chunk-container)
        fb (cont/container-bytes c)
        dir (cont/read-directory fb)]
    (testing "binary search over the directory finds the chunk for a key"
      (is (nil? (cont/find-chunk-idx dir (kbytes 5))) "below the first key: no chunk")
      (is (= 0 (cont/find-chunk-idx dir (kbytes 10))))
      (is (= 0 (cont/find-chunk-idx dir (kbytes 19))))
      (is (= 1 (cont/find-chunk-idx dir (kbytes 20))))
      (is (= 1 (cont/find-chunk-idx dir (kbytes 27))))
      (is (= 2 (cont/find-chunk-idx dir (kbytes 39))))
      (is (= 2 (cont/find-chunk-idx dir (kbytes 1000))) "past the end: the last chunk"))
    (testing "a lookup opens exactly one chunk — the right one"
      (doseq [[k want] [[10 "v-10"] [27 "v-27"] [39 "v-39"]]]
        (let [counter (atom 0)
              opened (cont/open-value-chunk fb dir (kbytes k) (toy-open counter))]
          (is (= 1 @counter) (str "key " k " opened " @counter " chunks"))
          (is (= want (toy-lookup opened (kbytes k)))))))
    (testing "the chunk handed to the reader is that chunk's bytes and no other's"
      (let [counter (atom 0)
            opened (cont/open-value-chunk fb dir (kbytes 27) (toy-open counter))
            chunks (:chunks c)]
        (is (Arrays/equals ^bytes (:chunk-bytes opened) ^bytes (nth chunks 1)))
        (is (not (Arrays/equals ^bytes (:chunk-bytes opened) ^bytes (nth chunks 0))))
        (is (not (Arrays/equals ^bytes (:chunk-bytes opened) ^bytes (nth chunks 2))))))))

;; ---------------------------------------------------------------------------
;; C3.2 — container-compression
;; ---------------------------------------------------------------------------

(deftest container-compression
  (let [big (mapv (fn [k] [(kbytes k) (apply str (repeat 40 (str "payload-" (mod k 5) "-")))])
                  (range 100 160))
        chunks (mapv (fn [part] {:bytes (toy-chunk part) :first-key (first (first part))})
                     (partition 20 big))
        c (cont/make-container chunks {:chunk 0 :offset 32})
        fb (cont/container-bytes c)
        z (cont/compress-container c)]
    (testing "the container is the unit of compression: one stream over the whole container"
      (is (Arrays/equals fb (cont/decompress-container-bytes z))
          "decompressing the one stream returns the container's bytes")
      (is (< (alength z) (alength fb)) "and it actually compresses"))
    (testing "sixteen chunks is the cap"
      (is (thrown? clojure.lang.ExceptionInfo
                   (cont/make-container (vec (repeat 17 (first chunks))) {:chunk 0 :offset 32}))))))

;; ---------------------------------------------------------------------------
;; C3.3 — enter-mid-stream
;; ---------------------------------------------------------------------------

(deftest enter-mid-stream
  (let [c (three-chunk-container)
        chunks (:chunks c)
        stream (byte-array (apply concat chunks))] ;; a stream: chunks only, no directory yet
    (testing "a reader enters at any chunk by walking header lengths only"
      (doseq [idx [0 1 2]]
        (let [off (cont/stream-chunk-offset stream idx)
              counter (atom 0)
              opened ((toy-open counter) (cont/chunk-at-offset stream off))]
          (is (= 1 @counter))
          (is (Arrays/equals ^bytes (:chunk-bytes opened) ^bytes (nth chunks idx))))))
    (testing "the chunk entered mid-stream answers for its keys"
      (let [off (cont/stream-chunk-offset stream 2)
            opened ((toy-open (atom 0)) (cont/chunk-at-offset stream off))]
        (is (= "v-35" (toy-lookup opened (kbytes 35))))))
    (testing "an offset that is not a chunk boundary is refused"
      (is (thrown? clojure.lang.ExceptionInfo (cont/chunk-at-offset stream 40))))))

;; ---------------------------------------------------------------------------
;; C3.4 — append-keeps-old-value
;; ---------------------------------------------------------------------------

(deftest append-keeps-old-value
  (let [c1 (cont/make-container
            [{:bytes (toy-chunk (entries-for 10 20)) :first-key (kbytes 10)}
             {:bytes (toy-chunk (entries-for 20 30)) :first-key (kbytes 20)}]
            {:chunk 0 :offset 32})
        fb1 (cont/container-bytes c1)
        dir1 (cont/read-directory fb1)
        c2 (cont/append-chunk c1 {:bytes (toy-chunk (entries-for 30 40)) :first-key (kbytes 30)})
        fb2 (cont/container-bytes c2)
        dir2 (cont/read-directory fb2)]
    (testing "appending makes a new chunk and a new root"
      (is (= 3 (count (:chunks c2))))
      (is (= {:chunk 2 :offset 32} (:root dir2))))
    (testing "the old container is untouched and still reads the old value"
      (is (= 2 (count (:chunks c1))))
      (is (Arrays/equals fb1 (cont/container-bytes c1)))
      (is (= {:chunk 0 :offset 32} (:root dir1)))
      (let [counter (atom 0)
            opened (cont/open-value-chunk fb1 dir1 (kbytes 15) (toy-open counter))]
        (is (= "v-15" (toy-lookup opened (kbytes 15))))))
    (testing "the new root reads the new chunk; old keys still resolve"
      (let [opened (cont/open-value-chunk fb2 dir2 (kbytes 35) (toy-open (atom 0)))]
        (is (= "v-35" (toy-lookup opened (kbytes 35)))))
      (let [opened (cont/open-value-chunk fb2 dir2 (kbytes 15) (toy-open (atom 0)))]
        (is (= "v-15" (toy-lookup opened (kbytes 15))))))))

;; ---------------------------------------------------------------------------
;; C3.5 — container-is-placement-unit (with Z3.7)
;; ---------------------------------------------------------------------------

(defn- build-image-unit [len {:keys [root symtab shapetab blocks block-table reloc-table unit-start]
                              :or {unit-start 0}}]
  (let [b (byte-array len)]
    (put-header b {:kind 1 :length len :root root :nsym 1 :symtab symtab :shapetab shapetab})
    (doseq [[off size] blocks]
      (dotimes [i size] (aset b (+ (- off unit-start) i) (unchecked-byte 0xAB))))
    (doseq [[i [off size]] (map-indexed vector block-table)]
      (put-u32 b (+ symtab (* 8 i)) off)
      (put-u32 b (+ symtab (* 8 i) 4) size))
    (doseq [[i [pos owner target]] (map-indexed vector reloc-table)]
      (put-u32 b (+ shapetab (* 12 i)) pos)
      (put-u32 b (+ shapetab (* 12 i) 4) owner)
      (put-u32 b (+ shapetab (* 12 i) 8) target))
    b))

(deftest container-is-placement-unit
  (let [;; three 256-byte units; blocks image-global. Unit 0 carries both tables (§9:
        ;; the cross-unit plan lives in the first unit). Units 1 and 2 carry no tables.
        block-table [[32 40] [80 56] [288 48] [352 64] [544 40]]
        reloc-table [[40 32 80]      ;; intra-unit 0 -> 0
                     [88 80 288]     ;; cross 0 -> 1
                     [296 288 544]   ;; cross 1 -> 2
                     [552 544 32]    ;; cross 2 -> 0
                     [360 352 288]]  ;; intra-unit 1 -> 1
        u0 (build-image-unit 256 {:root 32 :symtab 144 :shapetab 184
                                  :blocks [[32 40] [80 56]]
                                  :block-table block-table :reloc-table reloc-table})
        ;; build-image-unit defaults :symtab/:shapetab when absent (the (or ...) fallbacks in its body and put-header's); kondo's inferred map type requires them because sibling calls pass them - the two discards below carry that reason
        u1 #_{:clj-kondo/ignore [:type-mismatch]} (build-image-unit 256 {:root 288 :unit-start 256 :blocks [[288 48] [352 64]]})
        u2 #_{:clj-kondo/ignore [:type-mismatch]} (build-image-unit 256 {:root 544 :unit-start 512 :blocks [[544 40]]})
        c (cont/make-container [{:bytes u0} {:bytes u1} {:bytes u2}] {:chunk 0 :offset 32})
        plan (cont/cross-unit-plan c)]
    (testing "the container hands Z3 its units in order"
      (is (= [u0 u1 u2] (cont/image-units c))))
    (testing "the plan is read from the first unit and names every pointer that leaves a unit"
      (is (= #{{:position 88 :owner 80 :target 288 :from-unit 0 :to-unit 1}
               {:position 296 :owner 288 :target 544 :from-unit 1 :to-unit 2}
               {:position 552 :owner 544 :target 32 :from-unit 2 :to-unit 0}}
             (set plan))))
    (testing "no intra-unit pointer is in the plan"
      (is (not-any? (fn [e] (= (:from-unit e) (:to-unit e))) plan)))))
