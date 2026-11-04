#!/usr/bin/env bash
# Builds the glj eve-media's Go member runs on into $1 (default capstone/eve-media/target/glj-eve-media): glj-eve's own
# build (eve/impls/glojure/build.sh — glojure v0.7.22, eve's region/types/host packages, the core completion's patches)
# with one more package in glojure's package map, eveglj/media (glj/media: the media server, the watcher's listing, the
# range check). Skips everything when the output's stamp matches the sources.
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EVE="$(cd "$HERE/../../.." && pwd)"
GLJ="$EVE/impls/glojure"
PATCHES="$EVE/adapters/completion/glojure/go"
OUT="${1:-$HERE/../target/glj-eve-media}"
PKGS=syscall,unsafe,eveglj/region,eveglj/types,eveglj/host,eveglj/media
SUM=sha256sum; command -v sha256sum >/dev/null || SUM="shasum -a 256"   # macOS has shasum
to() { local s=$1; shift; if command -v timeout >/dev/null; then timeout "$s" "$@"; else "$@"; fi; }
TGOOS=$(go env GOOS); TGOARCH=$(go env GOARCH)
STAMP=$( (cd "$GLJ" && find . -type f | sort | xargs $SUM; cd "$PATCHES" && find . -type f | sort | xargs $SUM;
          cd "$HERE/media" && find . -type f | sort | xargs $SUM; echo "$TGOOS/$TGOARCH") | $SUM | cut -c1-16)
if [ -x "$OUT/glj-eve" ] && [ "$(cat "$OUT/.stamp" 2>/dev/null)" = "$STAMP" ]; then
  echo "glj-eve-media: cached build $OUT/glj-eve (source stamp $STAMP)"; exit 0
fi
rm -rf "$OUT"; mkdir -p "$OUT"; cp -r "$GLJ/." "$OUT/"; mkdir -p "$OUT/media"; cp "$HERE/media/"*.go "$OUT/media/"
cd "$OUT" || exit 1
# on a cold module cache `go list -m` answers an empty Dir and exits 0: download it then (as impls/glojure/build.sh)
gmod() { GOFLAGS=-mod=readonly go list -m -f '{{.Dir}}' github.com/glojurelang/glojure; }
GMOD=$(gmod)
[ -n "$GMOD" ] || { go mod download github.com/glojurelang/glojure && GMOD=$(gmod); }
[ -n "$GMOD" ] || { echo "glj-eve-media: glojure is not in the module cache and go mod download did not fetch it"; exit 1; }
cp -r "$GMOD" glojure && chmod -R u+w glojure || exit 1
for p in "$PATCHES"/*.patch; do patch -s -p1 -d glojure < "$p" || { echo "glj-eve-media: $p did not apply"; exit 1; }; done
go mod edit -replace github.com/glojurelang/glojure=./glojure || exit 1
# glojure's generator built for this machine, run for the target (as glojure's scripts/gen-gljimports.sh)
env -u GOOS -u GOARCH GOFLAGS=-mod=mod go build -o gen-import-interop github.com/glojurelang/glojure/cmd/gen-import-interop \
  || { echo "glj-eve-media: building the generator failed"; exit 1; }
GOROOT=$(go env GOROOT) CGO_ENABLED=0 GOOS=$TGOOS GOARCH=$TGOARCH to 120 ./gen-import-interop -packages "$PKGS" \
  > gljimports/gljimports.go || { echo "glj-eve-media: generator failed"; exit 1; }
GOFLAGS=-mod=mod to 300 go build -o glj-eve ./cmd/glj-eve || { echo "glj-eve-media: build failed"; exit 1; }
echo "$STAMP" > .stamp
echo "glj-eve-media: built $OUT/glj-eve ($(wc -c < glj-eve | tr -d ' ') bytes, $TGOOS/$TGOARCH)"
