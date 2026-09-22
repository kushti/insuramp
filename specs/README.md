# Implementation Specs — Index

*Spec set for building the vault-insured P2P cash→USDT on-ramp designed in the root
docs (`pillars.md` → `onramp-insurance.md` → `onramp-ux.md` → `onramp-business-model.md` —
read those first for the why; these specs are the how). Direction: cash→USDT only — the
buyer hands cash to the seller and receives USDT; the seller acts last
and locks the vault. The reverse direction (USDT→cash) is out of scope and not
specified.*

## Decisions baked into every spec

- **Language:** Kotlin everywhere — native Android buyer app, Ktor operator
  backend, ErgoScript contracts compiled and tested via JVM tooling (ergo-appkit +
  sigmastate) in a Kotlin Gradle module.
- **First leg:** cash→USDT on-ramp — USE collateral, the seller locks the
  vault. The claim is gated on the seller-signed handoff record (the seller's
  R5 key, verified in path B); the phase-1 centralized NFT-authenticated oracle's
  attestation **alone** gates release — the oracle is trusted, period, on the release
  path; phase 2 replaces it with a Rosen-derived guard threshold. BTC (trustless relay)
  and XMR (tx-key oracle) legs are extension notes in `vault-contract.md` §8 until the
  USDT leg ships. The v2 rework of the phase-1 `.es` contracts has landed
  (2026-09-13): vault R7 holds the bare 32-byte `oracleNftId` (the old 65-byte two-key
  packing is gone), path B is
  gated on the single seller-signed handoff record, and paths C/C′ are gated on the
  oracle's attestation (the bare `dealId`) alone — no receipt signature anywhere in the protocol.
- **Repo layout (as implementation phases land):**
  `contracts/` — ErgoScript `.es` sources + Kotlin contract tests (phase 1 + v2
  landed); `apps/core/` — pure-Kotlin shared modules (`dealprotocol`, `ergo` chain
  layer — landed); `apps/app` — native Android buyer app module (starting in M4,
  2026-09-17); `backend/` — operator backend (landed M3-B, 2026-09-17); `e2e/` — end-to-end gate (landed M3-C);
  `specs/` — this directory. Mainnet is the default target everywhere since
  2026-09-17; testnet stays selectable via config/env (`P2P_NETWORK=testnet`,
  `E2E_EXPLORER_URL`/`E2E_FAUCET_URL`).

## Reading order

| # | File | Covers | Status |
|---|---|---|---|
| 1 | `specs/vault-contract.md` | ErgoScript vault: box layout, registers, spending paths, test matrix | **phase 1 implemented + tested** (`contracts/`); v2 landed (2026-09-13: single-signed claim, oracle-only release); in-contract protocol fee removed 2026-09-18 (every path pays in full) |
| 2 | `specs/deal-protocol.md` | Canonical deal state machine, wire formats (deal terms, handoff record, QR), key management, privacy requirements | **landed** (`apps/core/dealprotocol/`, 94 tests green) |
| 3 | `specs/oracle-integration.md` | Payment-proof oracle: permanent 32-byte `dealId` payload (simplified from the 112-byte field layout pre-launch, 2026-09-21), phase-1 centralized NFT-authenticated oracle, taint screening, phase-2 Rosen-derived guard threshold, liveness/safety | draft; dealId payload + dev oracle landed in code (`DevOracle.attest(terms) = terms.dealId` — the `PaymentAttestation` codec is deleted — plus the `DevOracle` attestation-box builder, backend `OracleClient`/`DevOracleClient` with `attestationBoxFor`) — the deployed HTTP attestation service is still future work |
| 4 | `specs/seller-dashboard.md` | seller/operator dashboard front-end: login, vault lane kanban, the meeting screen (counted-cash gate, handoff QR), pool view, dispute inbox, infra status; handoff sign/QR endpoints | **landed** (2026-09-17/18, M4; static vanilla JS/CSS app at `/dashboard/`, handoff sign + QR endpoints live, backend runnable via `./gradlew :backend:run`) |
| 5 | `specs/android-app.md` | buyer-facing native Android app: modules, screens, chain interaction, handoff-record verification | **landed** (2026-09-18, M4): `apps/app` native Android buyer app (Compose, ZXing, WorkManager polling) — quote discovery, deal timeline, the meeting screen, claim path, recovery; 37 JVM unit tests + `assembleDebug` green. Native Android is the owner decision; the earlier PWA wording is superseded |
| 6 | `specs/operator-backend.md` | Ktor operator backend: vault lane, collateral management, quotes, AML hook, dispute inbox, APIs | **landed M3-B** (2026-09-17, `backend/`, 93 tests green; M4 seller-meeting endpoints + static dashboard serving added; documented deviations in the spec's status note) |

## Invariants that keep the specs (and future code) consistent

1. **Constants live in one place.** `RECLAIM_TIMEOUT` (24h), `CLAIM_MATURATION` (12h),
   `HANDOFF_RECORD_MAX_AGE`, `HANDOFF_CLOCK_SKEW`, and `BTC_DEADLINE` (~6h) are
   defined only in
   `vault-contract.md` §2. Everything else references the names. Changing a constant means
   editing that table and checking every spec — same rule as the design docs'
   cross-referenced parameters. (The in-contract protocol fee, `PROTOCOL_FEE_BPS`,
   lived there until it was removed on 2026-09-18 — no fee constants remain; every
   collateral-moving path pays in full, miner fee unchanged.)
2. **State names are canonical** in `deal-protocol.md` §1: `QUOTED → FUNDED →
   PAYMENT_PENDING → PAYMENT_CONFIRMED → RELEASED`; quote expiry (`QuoteExpired`)
   closes a deal that never funded; the timeout branch `FUNDED → RECLAIMED` (buyer
   no-show); the dispute branch `PAYMENT_PENDING → CLAIM_OPENED → CLAIMABLE →
   CLAIMED`. No component may invent synonyms.
3. **Trust-model honesty.** The USDT/XMR legs trust the phase-1 centralized oracle —
   and on the release path its attestation is **solely sufficient** (a compromised
   oracle can steal collateral; accepted at launch, mitigated operationally — say so
   wherever the trust model is discussed). Phase 2's guard threshold is where "not
   trustless, but cost-to-attack" applies; the claim path is gated on the
   seller-signed handoff record, not the oracle; only the BTC leg is trustless. Cash
   has no oracle — never claim a trustless cash proof. Specs and code comments must say
   so (house rule from `AGENTS.md`).
4. **Epistemic discipline.** Figures carry `[approx]`/`[spec]`/`[solid]` markers, as in the
   design docs.

## Suggested implementation phases

1. **Contracts** — `contracts/` module, vault `.es` + the test matrix in
   `vault-contract.md` §7, against a mock oracle key set. Phase 1 implemented and
   tested; the **v2 rework has landed** (2026-09-13): R7 holds the bare 32-byte
   `oracleNftId` (the old two-key packing is gone); path B is gated
   on the single seller-signed handoff record; paths C/C′ gate release on the oracle's
   attestation alone; `CLAIM_MATURATION` 12h.
2. **Deal protocol library** — pure-Kotlin `:core:dealprotocol` implementing
   `deal-protocol.md` wire formats + state machine, shared by all apps and the backend.
   **Landed** (2026-09).
3. **Chain layer** — pure-Kotlin `:core:ergo` (`android-app.md` §4): `ChainSource` +
   explorer client, `VaultBoxTracker`, `ClaimTxBuilder`, `OperatorTxBuilder`,
   `DevOracle` (attestation-box builder over the bare `dealId`; the `OracleSigner`
   co-signing seam was removed in the 2026-09-17 data-input rework, and the 112-byte
   `PaymentAttestation` codec was deleted with the 2026-09-21 dealId-only payload), prover-verified txs.
   **Landed** (2026-09-16, M2; operator-side txs extended M3-A).
4. **Operator backend** — vault funding/reclaim + quote feed; enables manual end-to-end
   deals (mainnet by default since 2026-09-17; testnet via config — `P2P_NETWORK=testnet`).
   **Landed** (2026-09-17, M3-B; documented deviations in `operator-backend.md`'s status note).
5. **End-to-end gate** — `e2e/` module: `./gradlew :e2e:run` (`--dry-run` for a
   no-broadcast build against live chain). **Landed** (2026-09-17, M3-C).
6. **UIs (M4)** — the two user interfaces, both built on the demo backend.
   **Landed** (2026-09-18) under two owner decisions: (a) the buyer app is a
   **native Android** app (`apps/app`) — the earlier "mobile-first PWA" wording in
   `onramp-ux.md` is superseded; (b) the seller UI is the operator dashboard
   front-end, specced in `specs/seller-dashboard.md`, served at `/dashboard/`. The
   handoff sign + QR endpoints it depends on are live (sign from `FUNDED` drives
   `CashCollected` → `PAYMENT_PENDING`).
7. **Oracle phase 2** — replace the centralized NFT-authenticated oracle with a GuardSign-style k-of-n guard set (Rosen contract fork, `specs/oracle-integration.md` §3.2). The payload is unchanged — the guard set threshold-signs the same 32-byte `dealId`; only the vault's authentication check and the signer set change. (The deployed phase-1 HTTP attestation service is also still future work — the seam exists in code.)

(Phases 6–7 can overlap; the oracle service and phase-2 guard set are independent workstreams.)

## Cross-references

- Design docs: `onramp-insurance.md`, `onramp-ux.md`, `onramp-business-model.md`, `pillars.md`
