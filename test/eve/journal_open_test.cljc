(ns eve.journal-open-test
  "A journaled persistent domain must open, write, close and re-open on
   every seat: the journal is the disk tier's default shape (the named
   opt-out is :journal? false), so an open that throws is a release
   defect on that seat. Regression for the bb seat (2026-11-08): under
   babashka, methods of the returned FileLock (sun.nio.ch.FileLockImpl)
   are unreachable — SCI falls back to a field lookup and
   (.release fl) throws NoSuchFieldException: release — and domain open
   itself runs recovery under with-journal-lock, so every journaled
   open died in the lock's release. The java journal adapter now
   releases the way the jvm-nio-lock region adapter already did:
   (.close fl), with a channel-of-its-own route where even close is
   refused."
  (:refer-clojure :exclude [swap!])
  (:require [clojure.test :refer [deftest is]]
            [eve.host :refer [swap!]]
            [eve.process :as proc]
            [eve.atom :as a]
            [eve.journal-file :as jf]
            #?(:cljs ["os" :as os])))

(defn- fresh-base []
  (str #?(:cljs (.tmpdir os) :lpy "/tmp" :clje "/tmp" :jank "/tmp" :cljr (System.IO.Path/GetTempPath)
          :default (System/getProperty "java.io.tmpdir"))
       "/eve-journal-open-" (rand-int 1000000000) "/d"))

(deftest journaled-domain-opens-writes-and-reopens
  (let [base (fresh-base)
        dom (a/persistent-atom-domain base)
        x (a/lookup-or-create-mmap-atom! dom "t/x" 0)]
    (is (proc/file-exists? (str base ".journal"))
        "the default disk domain is journaled — this test exercises the journal path")
    (swap! x + 41)
    (swap! x inc)
    (a/close-atom-domain! dom)
    (let [dom2 (a/persistent-atom-domain base)
          x2 (a/lookup-or-create-mmap-atom! dom2 "t/x" 0)]
      (is (= 42 @x2) "the re-open recovers what the journal committed")
      (swap! x2 inc)
      (is (= 43 @x2))
      (a/close-atom-domain! dom2))))

(deftest journal-off-domain-still-opens
  (let [base (fresh-base)
        dom (a/persistent-atom-domain base :journal? false)
        x (a/lookup-or-create-mmap-atom! dom "t/x" 0)]
    (is (not (proc/file-exists? (str base ".journal")))
        "the named opt-out writes no journal file")
    (swap! x + 7)
    (a/close-atom-domain! dom)
    (let [dom2 (a/persistent-atom-domain base :journal? false)
          x2 (a/lookup-or-create-mmap-atom! dom2 "t/x" 0)]
      (is (= 7 @x2))
      (a/close-atom-domain! dom2))))

(deftest journal-lock-releases
  ;; the adapter fn itself: two sequential holds on the same channel —
  ;; the second hold can only take the file lock if the first released
  ;; it (a same-process second lock would throw
  ;; OverlappingFileLockException).
  (let [base (fresh-base)
        _ (proc/ensure-parent-dir! (str base ".journal"))
        fc (jf/open-journal (str base ".journal"))]
    (is (= :one (jf/with-journal-lock fc (fn [] :one))))
    (is (= :two (jf/with-journal-lock fc (fn [] :two))))
    (jf/journal-close! fc)))
