# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.0] - 2026-03-10

### Added
- Shared-memory persistent data structures for ClojureScript and Clojure
  - `atom`, `hash-map`, `hash-set`, `vector`, `list` backed by SharedArrayBuffer
  - Cross-process mmap-backed persistent atoms (JVM + Node.js)
  - Native C++ addon (`mmap_cas.cc`) for memory-mapped CAS operations
  - Epoch-based garbage collection for safe cross-worker/process memory reclamation
  - WASM-accelerated bitmap allocator with SIMD optimizations
  - Slab allocator with six size classes (32B–1024B) plus coalescing overflow
  - Lazy slab growth — files start small and grow on demand
- Specialized collections
  - Integer map (PATRICIA trie) with merge-with and range queries
  - Red-black tree sorted set
- `eve/obj` typed shared objects (AoS and SoA layouts)
- `eve/deftype` for custom SAB-backed types with atomic field operations
- Typed array support with full Atomics API
- JVM persistent atoms via Panama FFM (Java 21+)
- X-RAY memory diagnostics for slab allocator debugging
- Comprehensive documentation and benchmark results

[Unreleased]: https://github.com/SeniorCareMarket/eve/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/SeniorCareMarket/eve/releases/tag/v0.1.0
