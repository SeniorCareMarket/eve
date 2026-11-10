;; render_check.clj — regression check for ants/render.
;;
;; WHY THIS EXISTS: the pre-fix render built each row string and
;; discarded it, returning "\n" per row, so the board printed as
;; dim blank lines on EVERY platform while audits (a separate
;; path) stayed green — the defect survived M2 and a full packaged
;; proof day because nothing asserted on the board itself. This
;; check asserts on the board. A render that prints blanks fails
;; here, not on a user's evening.
;;
;; It loads ONLY the render defns out of docs/stm-ants/ants.cljc
;; (no Eve needed) and feeds synthetic cells. Run from the
;; checkout root:
;;   bb docs/stm-ants/demo/render_check.clj
;; Exit 0 iff every assertion holds.

(require '[clojure.string :as str])

(let [path (or (first *command-line-args*) "docs/stm-ants/ants.cljc")
      src (slurp path)
      want '#{xy->idx ant-glyphs ant-glyphs-carrying cell-glyph render}
      rdr (java.io.PushbackReader. (java.io.StringReader. src))]
  (loop []
    (let [form (read {:eof ::eof} rdr)]
      (when (not= form ::eof)
        (when (and (seq? form) (contains? want (second form)))
          (eval form))
        (recur)))))

(let [failures (atom [])
      check (fn [label ok?] (when-not ok? (swap! failures conj label)))
      ;; 8x8 synthetic board, idx = y*8+x
      cells (vec (for [i (range 64)]
                   (cond (= i 0) {:ants {[:t 0] {:dir 2 :food true}} :food 0 :pher 0.0 :home true}
                         (= i 1) {:ants {[:t 1] {:dir 0}} :food 0 :pher 0.0}
                         (= i 2) {:ants {} :food 1 :pher 0.0}
                         (= i 3) {:ants {} :food 2 :pher 0.0}
                         (= i 4) {:ants {} :food 3 :pher 0.0}
                         (= i 5) {:ants {} :food 6 :pher 0.0}
                         (= i 6) {:ants {} :food 0 :pher 0.1}
                         (= i 7) {:ants {} :food 0 :pher 0.5}
                         (= i 8) {:ants {} :food 0 :pher 2.0}
                         (= i 9) {:ants {} :food 0 :pher 3.5}
                         (= i 10) {:ants {} :food 0 :pher 0.0 :home true}
                         :else {:ants {} :food 0 :pher 0.0})))
      out (render 8 cells)
      lines (str/split-lines out)]
  (check "render returns exactly dim lines" (= 8 (count lines)))
  (check "every line is dim chars wide" (every? (fn [l] (= 8 (count l))) lines))
  (check "board is not blank (the original defect)"
         (boolean (some (fn [l] (not (str/blank? l))) lines)))
  (check "carrying ant dir 2 renders ⇒" (str/includes? out "⇒"))
  (check "searching ant dir 0 renders ↑" (str/includes? out "↑"))
  (check "home cell renders ⌂" (str/includes? out "⌂"))
  (check "food bands render · : + #"
         (and (str/includes? out "·") (str/includes? out ":")
              (str/includes? out "+") (str/includes? out "#")))
  (check "pheromone gradient renders ░ ▒ ▓ █"
         (and (str/includes? out "░") (str/includes? out "▒")
              (str/includes? out "▓") (str/includes? out "█")))
  (if (seq @failures)
    (do (println "RENDER-CHECK FAIL:" @failures) (System/exit 1))
    (do (println "RENDER-CHECK PASS — board:")
        (println out)
        (System/exit 0))))
