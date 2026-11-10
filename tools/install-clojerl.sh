#!/usr/bin/env bash
# Reproducible clojerl toolchain for Eve's :clje host. Usage: tools/install-clojerl.sh [DEST]   (default ./clje-tools)
# Pins: Erlang/OTP 27.3.4.9 (hex.pm bob prebuilt, ubuntu-24.04), rebar3 3.24.0, clojerl 4ad14e5 (0.9.0-2194).
# Then: DEST/env.sh sets PATH; DEST/clojerl/bin/clojerl is the binary.
set -euo pipefail
DEST=$(mkdir -p "${1:-clje-tools}" && cd "${1:-clje-tools}" && pwd)
OTP=27.3.4.9; OS=${OTP_OS:-ubuntu-24.04}
cd "$DEST"
if [ ! -x otp/bin/erl ]; then
  mkdir -p otp
  curl -fsSL "https://builds.hex.pm/builds/otp/$OS/OTP-$OTP.tar.gz" | tar -xz -C otp --strip-components=1
  (cd otp && ./Install -minimal "$PWD")
fi
mkdir -p bin
[ -x bin/rebar3 ] || { curl -fsSL -o bin/rebar3 https://github.com/erlang/rebar3/releases/download/3.24.0/rebar3; chmod +x bin/rebar3; }
cat > env.sh <<EOF
export PATH="$DEST/otp/bin:$DEST/bin:\$PATH"
EOF
. ./env.sh
[ -d clojerl/.git ] || git clone -q https://github.com/clojerl/clojerl.git clojerl
(cd clojerl && git checkout -q 4ad14e5 && make)
echo "OTP=$(erl -noshell -eval 'io:format("~s",[erlang:system_info(otp_release)]),halt().') rebar3=$(rebar3 version|head -1) clojerl=$DEST/clojerl/bin/clojerl @ $(git -C clojerl rev-parse --short HEAD)"
