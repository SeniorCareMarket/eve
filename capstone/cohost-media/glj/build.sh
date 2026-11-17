#!/usr/bin/env bash
# Builds the glj member's binary into $1 (default capstone/cohost-media/target/glj-cohost-media).
# The build is Eve's own (impls/glojure/build.sh — glojure v0.7.22, Eve's region/types/host/media
# packages, the core completion's patches, the stamp cache); the media package moved into Eve's
# glojure tree (eveglj/media), so there is nothing capstone-specific left to add here.
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EVE="$(cd "$HERE/../../.." && pwd)"
exec bash "$EVE/impls/glojure/build.sh" "${1:-$HERE/../target/glj-cohost-media}"
