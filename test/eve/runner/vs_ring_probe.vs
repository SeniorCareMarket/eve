(ns eve.runner.vs-ring-probe
  "A compiled VS program measuring aegis's ring path (Option 2) on ValueScript one operation per run, so a run's peak
   memory is that operation's (a compiled program frees only what an atom's write replaces): the file probe.txt in the
   preopened directory names it (no newline) —
     none      the namespaces loaded, nothing run (the floor)
     h2c       one H_p(scope) (eve.aegis.h2c via eve.aegis.clsag/scope-point)
     muladd    one eve.crypto/ed25519-mul-add (the crypto adapter's edwards25519 row)
     scmul     one eve.aegis.clsag/sc-mul
     sign2     one CLSAG signature over a ring of 2
     verify2   one CLSAG signature over a ring of 2 and its verification
   Each prints its wall time. Every step runs inside -main (VS evaluates a top-level def's init as it compiles). Run:
     vs compile eve/test/eve/runner/vs_ring_probe.vs -o <out>.wasm   (VS_CLJ_ROOTS: crypto/vs-kernel's root first)
     printf h2c > <dir>/probe.txt; wasmtime run --dir <dir>::/ -W threads=y -W shared-memory=y -W exceptions=y -W max-wasm-stack=67108864 <out>.wasm"
  (:require [eve.aegis.clsag :as clsag]
            [eve.crypto :as crypto]
            [eve.net.bytes :as b]
            [eve.process :as process]))

(defn- t [label f]
  (let [t0 (process/now-ms) r (f) t1 (process/now-ms)]
    (println label :ms (- t1 t0))
    r))

(defn- counter-random [label]
  (let [c (atom 0)]
    (fn [n] (loop [out []] (if (>= (count out) n) (subvec out 0 n) (recur (into out (crypto/sha256 (b/utf8 (str label "-" (swap! c inc)))))))))))

(defn -main []
  (let [probe (slurp "probe.txt")
        seed-a (crypto/sha256 (b/utf8 "seed:a"))
        seed-b (crypto/sha256 (b/utf8 "seed:b"))
        scope (b/utf8 "per-poll|aegis.chat.ring|p-1")
        msg (crypto/sha256 (b/utf8 "the claim digest"))]
    (println :probe probe)
    (cond
      (= probe "h2c") (t :scope-point (fn [] (clsag/scope-point scope)))
      (= probe "muladd") (t :mul-add (fn [] (crypto/ed25519-mul-add (vec (range 32)) clsag/BASE (vec (range 32 64)) clsag/BASE)))
      (= probe "scmul") (t :sc-mul (fn [] (clsag/sc-mul (vec (range 32)) (vec (range 32 64)))))
      (or (= probe "sign2") (= probe "verify2"))
      (let [ring [(t :pk-a (fn [] (crypto/ed25519-public-key seed-a))) (crypto/ed25519-public-key seed-b)]
            sig (t :sign2 (fn [] (clsag/sign {:msg msg :ring ring :seed seed-a :index 0 :scope scope :random (counter-random "r")})))]
        (when (= probe "verify2")
          (println :verified (t :verify2 (fn [] (clsag/verify {:msg msg :ring ring :scope scope :sig sig}))))))
      :else nil)
    (println :done probe)))

(-main)
