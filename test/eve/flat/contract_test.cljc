(ns eve.flat.contract-test
  "W0.3 (contract-roundtrip), W0.4 (alloc-run-default), W0.5 (scoreboard-smoke):
   the frozen byte contract of FORMAT-V1.md §2 and §9, the slab I/O's run
   entry with its per-block default, and the scoreboard on a tiny corpus."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.flat.contract :as contract]
            ; the alias is exercised on the other reader pass; the require loads on both 
            #_{:clj-kondo/ignore [:unused-namespace]}   [eve.deftype-proto.alloc :as alloc]
            #?(:clj [eve.atom :as ea])))

;; ---------------------------------------------------------------------------
;; W0.3 — contract-roundtrip
;; ---------------------------------------------------------------------------

(deftest contract-roundtrip
  (testing "the chunk header round-trips, field for field"
    (doseq [fields [{:kind :packed :version 1 :flags 0 :length 8192 :root 64
                     :nsym 12 :nshape 3 :symtab 128 :shapetab 256 :typetab 0}
                    {:kind :image :version 1 :flags 5 :length 65536 :root 4096
                     :nsym 1 :nshape 0 :symtab 8192 :shapetab 12288 :typetab 16384}
                    {:kind :directory :version 1 :flags 32768 :length 1048576 :root 0
                     :nsym 0 :nshape 0 :symtab 0 :shapetab 0 :typetab 0}]]
      (let [ba (#_{:clj-kondo/ignore [:unresolved-symbol]} byte-array contract/header-size)]
        (contract/write-header! ba 0 fields)
        (is (= fields (contract/read-header ba 0))))))
  (testing "the magic is EVS1 and a wrong magic is refused by name"
    (let [ba (byte-array contract/header-size)]
      (contract/write-header! ba 0 {:kind :packed :length 32 :root 32})
      (is (= [0x45 0x56 0x53 0x31] (mapv #(bit-and (aget ba %) 0xFF) (range 4))))
      (aset ba 0 (byte 0))
      (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
                   (contract/read-header ba 0)))))
  (testing "the flag bits and the required-features byte round-trip"
    (let [ba (byte-array contract/header-size)
          flags (bit-or contract/flag-wide contract/flag-digest
                        (contract/required-features 0xA5))]
      (contract/write-header! ba 0 {:kind :packed :flags flags :length 32 :root 32})
      (let [h (contract/read-header ba 0)]
        (is (= flags (:flags h)))
        (is (contract/wide? h))
        (is (contract/digest? h))
        (is (not (contract/types? h)))
        (is (= 0xA5 (contract/required-features-of h))))))
  (testing "an image header's nsym field is its layout id (§9)"
    (let [ba (byte-array contract/header-size)]
      (contract/write-header! ba 0 {:kind :image :layout-id contract/layout-canonical
                                    :length 4096 :root 64 :symtab 1024 :shapetab 2048})
      (let [h (contract/read-header ba 0)]
        (is (= contract/layout-canonical (contract/layout-id h)))
        (is (= 1024 (:symtab h)) "in an image, symtab is the block table's offset")
        (is (= 2048 (:shapetab h)) "in an image, shapetab is the relocation table's offset"))))
  (testing "the image block table and relocation table round-trip (§9)"
    (let [blocks [{:offset 32 :size 128} {:offset 160 :size 64} {:offset 224 :size 1024}]
          relocs [{:position 40 :owner 32 :target 160} {:position 168 :owner 160 :target 224}]
          ba (byte-array 4096)]
      (contract/write-block-table! ba 2048 blocks)
      (contract/write-relocation-table! ba 3072 relocs)
      (is (= blocks (contract/read-block-table ba 2048 (count blocks))))
      (is (= relocs (contract/read-relocation-table ba 3072 (count relocs)))))))

;; ---------------------------------------------------------------------------
;; W0.4 — alloc-run-default
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest alloc-run-default
     (let [dom (ea/atom-domain nil :capacities {0 (* 4 1024 1024) 1 (* 4 1024 1024)})
           a (ea/lookup-or-create-mmap-atom! dom "w0/alloc-run" nil)
           sio (ea/atom-sio a)]
       (testing "the default answers n allocated blocks of the class, by looping alloc!"
         (let [offs (alloc/alloc-run! sio 0 4)]
           (is (= 4 (count offs)))
           (is (= 4 (count (distinct offs))))
           (is (every? #(= 0 (alloc/decode-class-idx %)) offs))
           (doseq [o offs] (is (true? (alloc/-sio-free! sio o)) "each block was allocated"))
           (doseq [o offs] (is (false? (alloc/-sio-free! sio o)) "and is free again"))))
       (testing "a run of one is a block; a run of none is empty"
         (let [offs (alloc/alloc-run! sio 1 1)]
           (is (= 1 (count offs)))
           (is (= 1 (alloc/decode-class-idx (first offs))))
           (alloc/-sio-free! sio (first offs)))
         (is (= [] (alloc/alloc-run! sio 0 0))))
       (testing "the overflow class has no fixed block size to claim a run of"
         (is (thrown? clojure.lang.ExceptionInfo (alloc/alloc-run! sio 6 2)))))))

;; ---------------------------------------------------------------------------
;; W0.5 — scoreboard-smoke
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest scoreboard-smoke
     (require 'eve.flat.scoreboard)
     (let [run! (ns-resolve 'eve.flat.scoreboard 'run!)
           tmp (java.io.File/createTempFile "spike-log" ".md")
           results (run! {:corpora :tiny :runs 1 :warmup 0 :log (.getPath tmp)})
           text (slurp tmp)]
       (is (= #{:B1 :B2 :B3 :B4 :B5 :B6 :B7 :B8}
              (set (mapcat (fn [r] (map :row (:rows r))) results)))
           "every scoreboard row ran")
       (is (re-find #"B4" text) "the run was appended to the log")
       (is (re-find #"atom->atom" text) "including atom -> atom (B4), measured as one run"))))
