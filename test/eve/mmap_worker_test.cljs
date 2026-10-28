(ns eve.mmap-worker-test
  "Async wait/notify test for NodeMmapRegion using an inline worker_thread.
   Requires native addon at build/Release/mmap_cas.node (npm run build:addon).
   Split from mmap_test to keep file sizes below 150 lines.
   NO namespace-level side effects — addon loading done in run-mmap! (runner)."
  (:require [cljs.test :refer [deftest is async]]
            [eve.region :as region]
            [eve.mem :as mem]))

(def ^:private fs (js/require "fs"))

(deftest test-wait-notify-worker
  (async done
    (let [path   (str "/tmp/eve-m2-wn-" (js/Date.now) ".mem")
          region (region/open-mmap-region path 4096)
          _      (mem/-store-i32! region 0 42)
          ;; the worker says it is about to wait, then waits: the store and
          ;; notify come after that (500 ms after starting the worker raced
          ;; its boot on a loaded box: it read 999 before waiting, "not-equal")
          wcode  (str "const {workerData,parentPort}=require('worker_threads');"
                      "const a=require(workerData.p);"
                      "const b=a.open(workerData.f,4096);"
                      "parentPort.postMessage({ready:true});"
                      "const r=a.wait32(b,0,42,5000);"
                      "parentPort.postMessage({r,v:a.load32(b,0)});")
          addon-path (.resolve (js/require "path") "build/Release/mmap_cas.node")
          Worker (.-Worker (js/require "worker_threads"))
          w      (Worker. wcode #js{:eval true
                                    :workerData #js{:p addon-path
                                                    :f path}})]
      (.on w "message"
           (fn [^js msg]
             (if (.-ready msg)
               (js/setTimeout
                (fn []
                  (mem/-store-i32! region 0 999)
                  (mem/-notify-i32! region 0 1))
                100)
               (do
                 (is (= "ok" (.-r msg)) "wait32 returned ok")
                 (is (= 999  (.-v msg)) "new value 999 visible in worker")
                 (.unlinkSync fs path)
                 (done))))))))
