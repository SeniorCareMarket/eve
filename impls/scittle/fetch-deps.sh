#!/usr/bin/env bash
# Fetch the seat build's pinned upstream sources into vendor/ (idempotent).
# Pins: scittle tag v0.8.33; sci @ 6790624776b09297a22100438f05b6555a4ff56d
# (the exact sha scittle v0.8.33's deps.edn pins). Sources are used
# unmodified — Eve's seat changes no upstream file; it compiles the stock
# library as the base module of its own application build and adds one
# Eve-owned plugin module beside it.
set -eu
cd "$(dirname "$0")"
mkdir -p vendor
if [ ! -d vendor/scittle-src/src ]; then
  curl -fsSL "https://codeload.github.com/babashka/scittle/tar.gz/refs/tags/v0.8.33" -o /tmp/scittle-seat-scittle.tgz
  tar -xzf /tmp/scittle-seat-scittle.tgz -C vendor
  mv vendor/scittle-0.8.33 vendor/scittle-src
fi
if [ ! -d vendor/sci-src/src ]; then
  curl -fsSL "https://codeload.github.com/babashka/sci/tar.gz/6790624776b09297a22100438f05b6555a4ff56d" -o /tmp/scittle-seat-sci.tgz
  tar -xzf /tmp/scittle-seat-sci.tgz -C vendor
  mv vendor/sci-6790624776b09297a22100438f05b6555a4ff56d vendor/sci-src
fi
echo "vendor pins in place: scittle v0.8.33, sci 6790624776b09297a22100438f05b6555a4ff56d"
