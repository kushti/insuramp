#!/usr/bin/env bash
# tui-buyer.sh — the buyer console: quotes, the deal, the meeting gate, claims.
#
# Builds the installDist launcher if missing, runs it with a JDK 17 JAVA_HOME
# (the launcher resolves java at RUN time), and wraps the run in a synthesized
# PTY when stdin is not a terminal (Mosaic needs a TTY). The backend must be
# up — see scripts/run-seller.sh.
#
# Env: P2P_BASE_URL (default: http://127.0.0.1:8080), P2P_KEY_FILE,
#      P2P_HANDOFF_FILE, P2P_KEY_PASSPHRASE (set = no interactive prompt),
#      P2P_ORACLE_NFT_ID (optional; unset only degrades spend classification),
#      and P2P_PAYOUT_ADDRESS — the buyer's TRON USDT address (T…, base58check;
#      NOT the Ergo key). See specs/tui-demo-run.md.
set -euo pipefail
cd "$(dirname "$0")/.."

export JAVA_HOME="${JAVA_HOME:-$HOME/.local/opt/jdk-17.0.20.1+1}"
export P2P_BASE_URL="${P2P_BASE_URL:-http://127.0.0.1:8080}"

BIN=tui/buyer/build/install/buyer/bin/buyer
if [ ! -x "$BIN" ]; then
    echo "Building the buyer console launcher..."
    ./gradlew :tui:buyer:installDist --console=plain -q
fi

if ! curl -s -m 2 -o /dev/null "$P2P_BASE_URL/v1/quotes"; then
    echo "WARNING: no backend answers on $P2P_BASE_URL — start it with scripts/run-seller.sh" >&2
fi
if [ -z "${P2P_PAYOUT_ADDRESS:-}" ]; then
    echo "NOTE: P2P_PAYOUT_ADDRESS is not set — taking a quote needs a TRON (T…) address." >&2
fi

if [ -t 0 ] && [ -t 1 ]; then
    exec "$BIN" "$@"
else
    exec script -qec "$BIN $*" /dev/null
fi
