(ns eve.thread.probe
  "A registered task every runtime can run (eve.thread.server's pool serves it; a page or a client submits it by name):
   it answers its argument, the argument's square, and the runtime and rung that ran it — the receipt a check reads to
   see WHICH server ran a job."
  (:require [eve.thread :as t]
            [eve.thread.domain :as dom]))

(t/deftask who
  "x, its square, and the runtime and rung that ran it"
  [x]
  (let [dh (:dh dom/*running*)] {:x x :y (* x x) :ran (:rid dh) :via (dom/via dh)}))
