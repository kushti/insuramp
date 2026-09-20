#!/usr/bin/env bash
# run-seller.sh — the seller/operator side: the operator backend + dashboard.
#
# Demo-mode defaults: in-memory store, NoOp tx submitter, dev oracle,
# seeded example quotes (one per app currency, located), mainnet chain-source
# defaults. Point at a real deployment with the P2P_* env vars — see
# specs/operator-backend.md ("Demo run recipe").
set -euo pipefail
cd "$(dirname "$0")/.."

export JAVA_HOME="${JAVA_HOME:-$HOME/.local/opt/jdk-17.0.20.1+1}"
export P2P_MIX_READY="${P2P_MIX_READY:-100000000000}"
export P2P_DEMO_QUOTES="${P2P_DEMO_QUOTES:-true}"
export P2P_OPERATOR_KEY="${P2P_OPERATOR_KEY:-op-demo}"

if curl -s -m 2 -o /dev/null http://localhost:8080/dashboard/; then
    echo "A backend already serves :8080 — dashboard at http://localhost:8080/dashboard (operator key: $P2P_OPERATOR_KEY)" >&2
    exit 0
fi

echo "Starting the operator backend (demo mode)."
echo "Dashboard: http://localhost:8080/dashboard   (operator bearer key: $P2P_OPERATOR_KEY)"
echo "Buyer API: http://localhost:8080/v1/...       Ctrl-C to stop."
echo "Stop later with: pkill -f p2pgate.backend.api.ServerKt"
exec ./gradlew :backend:run --console=plain
