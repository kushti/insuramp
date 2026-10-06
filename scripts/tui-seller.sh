#!/usr/bin/env bash
# tui-seller.sh — the operator console (nine-column kanban) against a backend.
#
# Builds the installDist launcher if missing, runs it with a JDK 17 JAVA_HOME
# (the launcher resolves java at RUN time — a system Java 8 dies with
# UnsupportedClassVersionError), and wraps the run in a synthesized PTY when
# stdin is not a terminal (Mosaic needs a TTY; ./gradlew :tui:seller:run does
# not work). The backend must be up — see scripts/run-seller.sh.
#
# Env: P2P_OPERATOR_KEY (default: op-demo, matching run-seller.sh's demo),
#      P2P_BASE_URL (default: http://127.0.0.1:8080), plus the TuiConfig set
#      (P2P_NETWORK, P2P_EXPLORER_URL, P2P_NODE_URL). See specs/tui-demo-run.md.
set -euo pipefail
cd "$(dirname "$0")/.."

export JAVA_HOME="${JAVA_HOME:-$HOME/.local/opt/jdk-17.0.20.1+1}"
export P2P_OPERATOR_KEY="${P2P_OPERATOR_KEY:-op-demo}"
export P2P_BASE_URL="${P2P_BASE_URL:-http://127.0.0.1:8080}"

BIN=tui/seller/build/install/seller/bin/seller
if [ ! -x "$BIN" ]; then
    echo "Building the seller console launcher..."
    ./gradlew :tui:seller:installDist --console=plain -q
fi

if ! curl -s -m 2 -o /dev/null "$P2P_BASE_URL/v1/quotes"; then
    echo "WARNING: no backend answers on $P2P_BASE_URL — start it with scripts/run-seller.sh" >&2
fi

if [ -t 0 ] && [ -t 1 ]; then
    exec "$BIN" "$@"
else
    # Not a terminal (pipe, CI): synthesize one. Extra CLI args are appended.
    exec script -qec "$BIN $*" /dev/null
fi
