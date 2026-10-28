#!/usr/bin/env bash
# Builds glj-eve — the glj eve runs on (hosts.edn :glj; bb test:glj) — from the
# source in this directory into $1 (default ${TMPDIR:-/tmp}/glj-eve), the way
# glojure itself builds a glj with extra Go packages (its gljdeps.edn route,
# internal/deps: generate a package map with genpkg, then a main that blank-
# imports it). Steps:
#   1. copy this directory (go.mod/go.sum pin glojure v0.7.22) to $1
#   2. copy that glojure module from the Go module cache to $1/glojure, apply
#      glojure's core completion (eve/adapters/completion/glojure/go/*.patch:
#      what eve needs of glojure's interpreter that v0.7.22 lacks) and point
#      the build at it (go mod edit -replace)
#   3. glojure's own generator writes the package map for syscall, unsafe and
#      the three eve packages, as glojure's scripts/gen-gljimports.sh does: the
#      generator built for this machine, run for the target (GOOS / GOARCH as
#      `go env` reports them, so a GOOS=… GOARCH=… in the environment
#      cross-builds)
#   4. go build -o glj-eve ./cmd/glj-eve
# Skips everything when $1/glj-eve exists and $1/.stamp matches a hash of the
# sources here, the patches and the target. Needs only the Go module cache (no
# network once it is warm). Runs on Linux and macOS (no GNU-only tool).
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PATCHES="$(cd "$HERE/../../adapters/completion/glojure/go" && pwd)"
OUT="${1:-${TMPDIR:-/tmp}/glj-eve}"
PKGS=syscall,unsafe,eveglj/region,eveglj/types,eveglj/host
SUM=sha256sum; command -v sha256sum >/dev/null || SUM="shasum -a 256"   # macOS has shasum
to() { local s=$1; shift; if command -v timeout >/dev/null; then timeout "$s" "$@"; else "$@"; fi; }
now() { perl -MTime::HiRes=time -e 'printf("%d\n", time() * 1000)'; }   # (date +%N is GNU's)
TGOOS=$(go env GOOS); TGOARCH=$(go env GOARCH)
STAMP=$( (cd "$HERE" && find . -type f | sort | xargs $SUM; cd "$PATCHES" && find . -type f | sort | xargs $SUM;
          echo "$TGOOS/$TGOARCH") | $SUM | cut -c1-16)
if [ -x "$OUT/glj-eve" ] && [ "$(cat "$OUT/.stamp" 2>/dev/null)" = "$STAMP" ]; then
  echo "glj-eve: cached build $OUT/glj-eve (source stamp $STAMP)"; exit 0
fi
rm -rf "$OUT"; mkdir -p "$OUT"; cp -r "$HERE/." "$OUT/"; cd "$OUT" || exit 1
ms() { echo $(( $(now) - $1 )); }
t0=$(now)
# on a cold module cache `go list -m` answers an empty Dir and exits 0 (measured 2026-10-21): download it then
gmod() { GOFLAGS=-mod=readonly go list -m -f '{{.Dir}}' github.com/glojurelang/glojure; }
GMOD=$(gmod)
[ -n "$GMOD" ] || { go mod download github.com/glojurelang/glojure && GMOD=$(gmod); }
[ -n "$GMOD" ] || { echo "glj-eve: glojure is not in the module cache and go mod download did not fetch it"; exit 1; }
echo "\$ cp -r $GMOD glojure; patch -p1 < each of $PATCHES/*.patch"
cp -r "$GMOD" glojure && chmod -R u+w glojure || exit 1
for p in "$PATCHES"/*.patch; do
  patch -s -p1 -d glojure < "$p" || { echo "glj-eve: $p did not apply"; exit 1; }
  echo "  applied $(basename "$p")"
done
go mod edit -replace github.com/glojurelang/glojure=./glojure || exit 1
echo "\$ gen-import-interop -packages $PKGS > gljimports/gljimports.go   (for $TGOOS/$TGOARCH)"
env -u GOOS -u GOARCH GOFLAGS=-mod=mod go build -o gen-import-interop github.com/glojurelang/glojure/cmd/gen-import-interop \
  || { echo "glj-eve: building the generator failed"; exit 1; }
GOROOT=$(go env GOROOT) CGO_ENABLED=0 GOOS=$TGOOS GOARCH=$TGOARCH to 100 ./gen-import-interop -packages "$PKGS" \
  > gljimports/gljimports.go || { echo "glj-eve: generator failed"; exit 1; }
echo "  generated $(wc -l < gljimports/gljimports.go) lines, $(grep -c '_register(' gljimports/gljimports.go) registrations in $(ms $t0) ms"
t1=$(now)
echo "\$ go build -o glj-eve ./cmd/glj-eve"
GOFLAGS=-mod=mod to 300 go build -o glj-eve ./cmd/glj-eve || { echo "glj-eve: build failed"; exit 1; }
echo "  built $OUT/glj-eve ($(wc -c < glj-eve | tr -d ' ') bytes) in $(ms $t1) ms"
echo "$STAMP" > .stamp
