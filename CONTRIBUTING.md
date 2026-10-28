# Contributing to Eve

Thank you for your interest in contributing to Eve!

## The changelog

Every user-visible change — a public namespace's behavior, a host added or changed, a format or wire change, a fixed
defect an adopter could have hit — adds a line under `## [Unreleased]` in `CHANGELOG.md` in the same commit
(*Added*, *Changed*, *Removed*, *Fixed*). A release moves those lines under its version. (0.1.0 to 0.9 went seven
months without an entry;.)

## Development Setup

### Prerequisites

- Clojure CLI (1.11+)
- Java 21+ (the JVM's regions: `MappedByteBuffer` with `VarHandle` atomics)
- Node.js (18+)
- npm
- A C++ compiler (for the native addon)

### Getting Started

```bash
# Clone the repository
git clone https://github.com/SeniorCareMarket/eve.git
cd eve

# Install dependencies (also builds native addon via postinstall)
npm install

# Compile tests
npx shadow-cljs compile eve-test

# Run all tests
node target/eve-test/all.js all
```

### Running Tests

```bash
# Run specific test suites
node target/eve-test/all.js slab
node target/eve-test/all.js obj
node target/eve-test/all.js mmap-atom
node target/eve-test/all.js epoch-gc

# Run JVM tests
clojure -M:jvm-test

# Available suites:
#   all, core, slab, epoch-gc, obj, int-map, rb-tree,
#   batch2, batch3, batch4, typed-array, array, mem, mmap,
#   mmap-slab, mmap-atom, mmap-atom-e2e, mmap-domain,
#   large-scale, deftype, validation
```

### Building the Native Addon

Only needed if you modify `mmap_cas.cc`:

```bash
npm run build:addon
```

## Project Structure

```
src/eve/
├── alpha.cljs / .clj    # Public API entry point
├── atom.cljc            # Cross-process mmap atom
├── mem.cljc             # IMemRegion protocol
├── map.cljc             # Eve HAMT map
├── vec.cljc             # Eve persistent vector
├── set.cljc             # Eve persistent set
├── list.cljc            # Eve persistent list
├── shared_atom.cljs     # SAB-backed atom
├── obj.cljc             # Typed shared objects
├── deftype_proto/
│   ├── alloc.cljc       # Slab allocator
│   ├── data.cljc        # Slab constants
│   ├── serialize.cljc   # Serializer
│   ├── coalesc.cljc     # Coalescing overflow allocator
│   └── ...
├── array.cljc           # Typed arrays
├── hamt_util.cljc       # Shared HAMT bitwise helpers
├── perf.clj             # JVM profiling tools
└── deftype/
    ├── int_map.cljs     # Integer map (PATRICIA trie)
    └── rb_tree.cljs     # Red-black tree
```

## Code Style

- Follow existing patterns in the codebase
- Add docstrings to public functions
- Keep macros minimal; prefer runtime functions when possible

## Pull Request Process

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/my-feature`)
3. Write tests for new functionality
4. Ensure all tests pass (see doc/testing.md for the full green baseline)
5. Update documentation if needed
6. Submit a pull request

## Reporting Issues

Please include:
- ClojureScript/Clojure versions
- Node.js version
- Platform (Linux, macOS)
- Minimal reproduction case
- Error messages and stack traces

## License

By contributing, you agree that your contributions will be licensed under the MIT License.
