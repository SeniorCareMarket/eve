;; adapters/flat-io/vs/src/eve/flat_io.vs — eve.flat-io, the ValueScript fast root (STUB, Phase 0 of the flat lanes,
;; 2026-11-17): a chunk is a VS string leaf, reads are mmap loads at vs.core/rt-blob-off, the writer is a leaf written in
;; place, tuples are VS rows. The contract: doc/flat/FLAT-IO.md. Lane: U3 (wave 2). Not on any classpath yet.
(ns eve.flat-io)
