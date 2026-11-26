(ns eve.dom.page
  "eve.dom's executor for a page whose guest is not an eve thread (eve.thread-host's workers): a ValueScript program in
   its browser host's Web Worker, whose frames reach the page as the vs.host bridge's :evedom ops. The page's host
   script calls these two exports; everything else is eve.dom.screen, the one executor every guest uses.

     eveDom.execFrame(bytes, guest) -> the reply frame's bytes (a Uint8Array of a request frame; guest names whose
                                       callbacks the frame's stubs post to)
     eveDom.setSender(guest, f)      -> f(msg) posts a stub's call (msg.op \"cb\": msg.cb, msg.args an ArrayBuffer of its
                                       argument frame) or release (\"rel\": msg.cb, msg.gen) to that guest"
  (:require [eve.dom.screen :as screen]))

(defn ^:export execFrame [bytes guest] (screen/exec-frame bytes guest))

(defn ^:export setSender [guest f] (screen/set-sender! guest (fn [msg _transfer] (f msg))))
