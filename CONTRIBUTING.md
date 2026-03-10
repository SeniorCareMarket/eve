# Contributing to Eve

Thank you for your interest in contributing to Eve!

## Development Setup

### Prerequisites

- Clojure CLI (1.11+)
- Java 21+ (for JVM persistent atoms via Panama FFM)
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
#   batch2, batch3, batch4, typed-array, mem, mmap,
#   mmap-slab, mmap-atom, mmap-atom-e2e
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
├── obj.cljs             # Typed shared objects
├── deftype_proto/
│   ├── alloc.cljc       # Slab allocator
│   ├── data.cljc        # Slab constants
│   ├── serialize.cljc   # Serializer
│   └── ...
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
4. Ensure all tests pass (see CLAUDE.md for the full green baseline)
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
