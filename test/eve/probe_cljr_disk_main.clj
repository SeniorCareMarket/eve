(ns eve.probe-cljr-disk-main
  "Step bisect for the cljr disk-atom segfault: the release suite's
   disk-atom area (eve.release-suite-test) re-run as numbered steps
   with a flushed marker after each, so the output names the step the
   native crash takes down. The area's own sequence, unchanged:
   domain, atom, 50 swaps, close, root file, reopen, deref, close.

     bb probe:cljr-disk"
  (:require [eve.atom :as ea]
            [eve.process :as proc]))

(defn- mark [s] (println s) (flush))

(defn -main [& _]
  (let [base (str "target/release-suite/disk-probe-" (rand-int 1000000000) "/d")]
    (mark (str "STEP 0 base=" base))
    (let [dom (ea/persistent-atom-domain base)]
      (mark "STEP 1 domain open")
      (let [a (ea/persistent-atom {:id :probe/disk :persistent base} {:n 0})]
        (mark "STEP 2 atom created")
        (dotimes [_ 5] (swap! a update :n inc))
        (mark (str "STEP 3 five swaps, n=" (:n @a)))
        (dotimes [_ 45] (swap! a update :n inc))
        (mark (str "STEP 4 fifty swaps, n=" (:n @a)))
        (ea/close-atom-domain! dom)
        (mark "STEP 5 domain closed")
        (mark (str "STEP 6 root file exists=" (proc/file-exists? (str base ".root"))))
        (let [dom2 (ea/persistent-atom-domain base)]
          (mark "STEP 7 domain reopened")
          (let [a2 (ea/persistent-atom {:id :probe/disk :persistent base} {:n -1})]
            (mark (str "STEP 8 reopened atom deref n=" (:n @a2)))
            (ea/close-atom-domain! dom2)
            (mark "STEP 9 reopened domain closed"))))))
  (mark "PROBE-DONE cljr-disk")
  (Environment/Exit 0))
