;; Proof 1 (seat's way): eve.process-slab loads first — the pattern
;; eve.runner.nbb-main and the flagship entry both use — then the vanilla
;; port's two entry namespaces must require cleanly on the fixed Eve.
(require 'eve.process-slab)
(eve.process-slab/init! :force true)
(require 'datascript.db 'datascript.core)
(println :REQUIRE-OK)
