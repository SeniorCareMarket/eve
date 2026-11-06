(ns eve.domain-open-test
  "An mmap domain's creator is decided by one atomic create of its .root file (eve.process/create-exclusive!), never
   by an existence check then a create: two processes opening a fresh domain together both passed the check and each
   initialised the domain over the other's writes — measured  on 2026-10-19 with three processes
   opening at once: 4 of 5 trials lost counts or a process on babashka, 3 of 5 on nbb, 5 of 5 on the JVM (the second
   creator zeroed ROOT_INFLIGHT under the first). The race needs processes (spike/eve-thread/run/joinrace.sh); this
   pins the primitive and a join through the root's magic, which the creator now writes last."
  (:refer-clojure :exclude [swap!])
  (:require [clojure.test :refer [deftest is testing]]
            [eve.host :refer [swap!]]
            [eve.process :as proc]
            [eve.atom :as a]
            #?(:cljs ["os" :as os])))

(defn- fresh-base []
  (str #?(:cljs (.tmpdir os) :lpy "/tmp" :clje "/tmp" :jank "/tmp" :cljr (System.IO.Path/GetTempPath)
          :default (System/getProperty "java.io.tmpdir"))
       "/eve-domain-open-" (rand-int 1000000000) "/d"))

(deftest one-process-creates-the-file
  (let [f (str (fresh-base) ".x")]
    (proc/ensure-parent-dir! f)
    (is (true? (proc/create-exclusive! f)) "the first create makes it")
    (is (false? (proc/create-exclusive! f)) "a second finds it there")
    (is (proc/file-exists? f))))

(deftest a-reopened-domain-joins-what-it-wrote
  (let [base (fresh-base)
        dom (a/persistent-atom-domain base)
        x (a/lookup-or-create-mmap-atom! dom "t/x" 0)]
    (swap! x + 41)
    (a/close-atom-domain! dom)
    (testing "the .root is there: the second open joins, through the root's magic"
      (let [dom2 (a/persistent-atom-domain base)
            x2 (a/lookup-or-create-mmap-atom! dom2 "t/x" 0)]
        (is (= 41 @x2))
        (swap! x2 inc)
        (is (= 42 @x2))
        (a/close-atom-domain! dom2)))))
