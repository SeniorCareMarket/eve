#!/usr/bin/env bash
# Builds lg-eve — the let-go eve runs on (hosts.edn :lg; bb eve-swarm seat --host lg) — from the source in this
# directory into $1 (default target/lg-eve beside it), the way impls/glojure/build.sh builds glj-eve:
#   1. copy this directory (go.mod/go.sum pin let-go v1.13.0; evenative, eve's Go package) to $1
#   2. copy that let-go module from the Go module cache to $1/let-go, apply let-go's core completion
#      (eve/adapters/completion/letgo/go/*.patch: what eve needs of let-go's runtime that v1.13.0 does otherwise) and
#      point the build at it (go mod edit -replace)
#   3. go build -o $1/lg-eve
# Cached: a build whose source stamp (this directory, the patches, the target) matches $1/.stamp is kept.
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PATCHES="$(cd "$HERE/../../adapters/completion/letgo/go" && pwd)"
OUT="${1:-$HERE/../../target/lg-eve}"
SUM=sha256sum; command -v sha256sum >/dev/null || SUM="shasum -a 256"   # macOS has shasum
to() { local s=$1; shift; if command -v timeout >/dev/null; then timeout "$s" "$@"; else "$@"; fi; }
STAMP=$( (cd "$HERE" && find . -type f | sort | xargs $SUM; cd "$PATCHES" && find . -type f | sort | xargs $SUM;
          echo "$(go env GOOS)/$(go env GOARCH)") | $SUM | cut -c1-16)
if [ -x "$OUT/lg-eve" ] && [ "$(cat "$OUT/.stamp" 2>/dev/null)" = "$STAMP" ]; then
  echo "lg-eve: cached build $OUT/lg-eve (source stamp $STAMP)"; exit 0
fi
rm -rf "$OUT"; mkdir -p "$OUT"; cp -r "$HERE/." "$OUT/"; cd "$OUT" || exit 1
lmod() { GOFLAGS=-mod=readonly go list -m -f '{{.Dir}}' github.com/nooga/let-go; }
LMOD=$(lmod)
[ -n "$LMOD" ] || { go mod download github.com/nooga/let-go && LMOD=$(lmod); }
[ -n "$LMOD" ] || { echo "lg-eve: let-go is not in the module cache and go mod download did not fetch it"; exit 1; }
echo "\$ cp -r $LMOD let-go; patch -p1 < each of $PATCHES/*.patch"
cp -r "$LMOD" let-go && chmod -R u+w let-go || exit 1
for p in "$PATCHES"/*.patch; do
  patch -s -p1 -d let-go < "$p" || { echo "lg-eve: $p did not apply"; exit 1; }
  echo "  applied $(basename "$p")"
done
go mod edit -replace github.com/nooga/let-go=./let-go || exit 1
echo "\$ go build -o lg-eve ."
GOFLAGS=-mod=mod to 600 go build -o lg-eve . || { echo "lg-eve: build failed"; exit 1; }
echo "  built $OUT/lg-eve ($(wc -c < lg-eve | tr -d ' ') bytes)"
echo "$STAMP" > .stamp
