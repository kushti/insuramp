# AGENTS.md

Guidance for AI coding agents working in this repository.

## Project overview

This repository holds the **design notes, implementation specs, and (as of phase 1) the
ErgoScript vault contracts with their test suite** for the project.
Design docs live at the root (four Markdown files); implementation specs live in `specs/`.
The `contracts/` Gradle module is live: ErgoScript `.es` sources under
`contracts/src/main/ergoscript/` plus a Kotlin test suite (sigma-state 6.0.6) under
`contracts/src/test/`. `apps/core/dealprotocol/` is live too: the pure-Kotlin deal
state machine (`specs/deal-protocol.md` §1) with its transition-matrix test suite.
`apps/core/ergo/` is live since 2026-09-16 (milestone M2, extended M3-A): the chain-interaction layer
(`specs/android-app.md` §4) — `ChainSource` + explorer client, `VaultBoxTracker`
(chain facts → `DealEvent`s), `ClaimTxBuilder` (the two buyer-side txs), `OperatorTxBuilder`
(fund/reclaim/release/contest), `PaymentAttestation` (112-byte oracle payload), `DevOracle`
+ `OracleSigner` seam, `ErgoContracts` incl. `compileFast` variants. Every built tx is
prover-verified against the compiled vault scripts. Pure Kotlin/JVM on ergo-appkit 6.0.1;
Android-injectable via interfaces.
`backend/` is live since 2026-09-17 (milestone M3-B): the Ktor operator backend
(`specs/operator-backend.md`) — deal engine (sole transition authority), chain watcher,
vault manager (state-aware reclaim: FUNDED/PAYMENT_CONFIRMED only), quote publisher,
buyer/dashboard `/v1` APIs + WebSockets, dispute inbox, AML `RiskScorer` hook,
infra monitor + auto-pause. In-memory `DealStore` behind a JDBC-shaped interface
(PostgreSQL impl is the documented follow-up); `TxSubmitter` seam for broadcast.
The buyer-authed `POST /v1/deals/{id}/handoff` uploads the seller-signed handoff record.
`e2e/` is live since 2026-09-17 (milestone M3-C): the end-to-end gate
(`./gradlew :e2e:run`, `--dry-run` for a no-broadcast build against live chain) —
funded operator (manual funding by default; testnet faucet via `E2E_FAUCET_URL`),
minted dev-oracle NFT + test collateral, and three real flows
(release via oracle attestation, dispute claim-open → fast maturation → payout, timeout
reclaim). Mainnet is the default target since 2026-09-17 ("change Ergo testnet to
mainnet everywhere, lets test with mainnet"); testnet stays fully selectable via
`E2E_EXPLORER_URL`/`E2E_FAUCET_URL`.
The rest of `apps/` (Android shells) remains reserved-only. There is no `pyproject.toml`,
`package.json`, or `Cargo.toml`. Likewise,
there is no `rosen/` directory: the docs cross-reference `rosen/deck.html` (a Rosen bridge
pitch deck), but it lives outside this repo.

**Implementation decisions (confirmed by the project owner, 2026-09):**
- **Kotlin everywhere** — native Android buyer app, Ktor operator backend,
  ErgoScript contracts compiled/tested via JVM tooling (ergo-appkit + sigmastate).
- **Mainnet-first network defaults** — since 2026-09-17 every runnable module targets
  Ergo mainnet by default (`ExplorerChainSource` default base URL, `ErgoContracts.compile`/
  `compileFast` default prefix, backend `network = "mainnet"`, e2e explorer/faucet
  defaults); testnet remains fully selectable via config/env (`P2P_NETWORK=testnet`,
  `E2E_EXPLORER_URL`, `E2E_FAUCET_URL`). No testnet capability was removed.
- **First asset leg:** cash→USDT on-ramp (USE collateral; the seller acts
  last and locks the vault; phase-1 centralized NFT oracle whose attestation alone
  gates release, phase-2 Rosen-derived guard threshold). The reverse direction
  (USDT→cash off-ramp) is out of scope.
  BTC and XMR legs remain extension notes in `specs/vault-contract.md` §8.

The subject matter: designing a **vault-insured P2P cash→USDT on-ramp** built on the
Ergo blockchain. The core idea: the side of a crypto↔cash deal that acts last locks
collateral (USE stablecoin or Rosen-wrapped BTC) in an ErgoScript contract vault; the
contract pays the collateral to whichever side presents cryptographic proof of the deal's
outcome (Rosen oracle confirmation, trustless Bitcoin relay inclusion proof, Monero
tx-key reveal, or the seller-signed handoff record proving cash collection). This
substitutes for counterparty reputation. On-ramp: the buyer hands cash to the seller at
an in-person meeting first and the seller sends USDT afterwards, so the seller locks the
vault; the claim is gated on the seller-signed handoff record (the seller's R5 key, the
same key that reclaims the collateral) and the release on the oracle's attestation
**alone** — the phase-1 oracle is trusted,
period, on the release path. The broader vision is Ergo as pillar 3 of
"state-independent 21st century money": trust-minimized onramping.

## Document map

| File | Role |
|---|---|
| `pillars.md` | Top-level vision: four pillars of state-independent money (Ergo ledger/reserve → USE stablecoins → Rosen cross-chaining/onramp → Basis p2p cash issuance). Context for everything else. |
| `onramp-insurance.md` | **Core design doc — read this first.** Vault contract skeleton, per-asset designs (USDT oracle-verified, BTC trustless via Bitcoin relay, XMR oracle-verified via tx-key reveal), trust-model comparison, limitations, sources. |
| `onramp-ux.md` | Product design for the two sides of the marketplace — buyer app (mobile PWA) and seller meeting flow — plus the operator dashboard. Flow timings must stay consistent with the contract design (24h timeout, 12h claim maturation, ~6h BTC deadline). |
| `onramp-business-model.md` | Protocol-layer economics: fee model (25 bps, hardcoded in-contract), fee distribution, capital dynamics (protocol TVL ≈ outstanding deal volume; collateral availability is the binding constraint), volume scenarios, risks, bootstrapping sequence. |
| `specs/` | Implementation specs (index: `specs/README.md`): vault contract, deal protocol, oracle integration, buyer app, operator backend. Canonical constants live in `specs/vault-contract.md` §2; canonical state names in `specs/deal-protocol.md` §1. |

The docs form a strict reading order: `pillars.md` (why) → `onramp-insurance.md` (contracts
and trust) → `onramp-ux.md` (product) → `onramp-business-model.md` (economics). Each file
has a "Cross-references" section linking the others.

## Writing conventions in the docs

- **Language:** English throughout.
- **Epistemic discipline is mandatory.** Figures are marked `[approx]` (approximate),
  `[spec]` (speculative), or `[solid]` (measured). Claims that fail arithmetic or sourcing
  (e.g., the Reddit "DarkPaper Recipe #1" market-size claims) are explicitly excluded and
  the exclusion is stated. Follow `onramp-business-model.md`'s note: unverified figures
  must be labeled; do not silently convert `[spec]` numbers into facts.
- **Honest trust models.** Never claim "trustless" where trust is concentrated (e.g., the
  USDT/XMR legs trust the phase-1 centralized oracle — on the release path *solely*: its
  attestation alone moves collateral). The docs' own rule: "not trustless, but the
  cost-to-attack should exceed the extractable value per deal" — that framing applies
  only once the phase-2 guard threshold ships; for phase 1, say "trusted" and mean it.
- **Diagrams** are plain fenced code blocks (ASCII/monospace), e.g., the value-chain flow
  in `onramp-business-model.md` §1 and phone-screen mockups in `onramp-ux.md` §2.
- **Sources** are cited inline by URL and listed in a "Sources" section at the end of
  `onramp-insurance.md`. Primary sources include ergoforum.org threads, the
  r/ergonauts post, and the `ross-weir/ergohack-sidechain` GitHub repo (Bitcoin relay
  contracts such as `BtcTxCheck.es` — external, not vendored here).
- Each design doc opens with an italic preamble stating what it covers and which other
  files it depends on. Match that pattern when adding documents.

## The contracts module (`contracts/`)

Phase-1 vault contracts are implemented and tested here. **Scope note:** the `.es` sources
implement the on-ramp reading of `specs/vault-contract.md` — the **v2 rework has landed**
(2026-09-13, and the two-role refactor followed on 2026-09-17: the old third-party cash-side role is
gone, so the handoff record is seller-signed): vault R7 holds the bare 32-byte `oracleNftId` (the old
65-byte two-key packing is gone; Ergo boxes have R4–R9 only, no R10), path B is gated on
the seller-signed handoff record (a single Schnorr half under the R5 seller key, no
oracle on the claim path), and paths C/C′ take the oracle box as a full input — the
oracle's attestation alone releases the vault; there is no receipt signature anywhere in
the protocol. The fee is a compile-time constant too (`ContractParams.PROTOCOL_FEE_BPS` = 25,
substituted as `%%FEE_BPS%%`; 2026-09-17): FUNDED R8 is the plain `Long` `timeoutHeight`,
PAYMENT_PROVEN R7 the plain `Long` `proofHeight`, and the treasury fee output is required on
every collateral-moving path. The suite is the on-ramp matrix in `specs/vault-contract.md` §7.

- **Layout:** ErgoScript sources in `contracts/src/main/ergoscript/` (`vault_funded.es`,
  `vault_payment_proven.es`, `oracle.es`) — shipped on the classpath as resources.
  Compiler entry points: `ContractCompiler` / `ContractParams` (main Kotlin sourceset,
  plus `CompilerBridge.java` for Scala `$`-class interop). Tests:
  `contracts/src/test/kotlin/p2pgate/contracts/` (`VaultContractSpec` = the §7 matrix,
  `VaultFixture` = box/tx builder + prove/verify driver, `Schnorr.kt` = the t/3407 signer,
  `SigmaBridge.java` = sigma-state interop).
- **Build & test:** JDK 17 + Gradle wrapper. This machine's JDK lives at
  `~/.local/opt/jdk-17.0.20.1+1`, so run:
  `export JAVA_HOME=$HOME/.local/opt/jdk-17.0.20.1+1 && ./gradlew :contracts:test`
  (optionally `--tests 'p2pgate.contracts.VaultContractSpec'`). Expected test counts:
  `VaultContractSpec` 49 (1 `@Disabled`: phase-2 GuardSign readiness, test 45) plus
  `OracleContractSpec` 8 → contracts 57; dealprotocol module: DealStateMachine 44,
  Messages 10, QrPayload 11, DealTerms 22, Blake2b256 7 → 94; ergo module:
  ClaimTxBuilder 16, HandoffRecordVerifier 9, ExplorerChainSource 10, SchnorrVerifier 9,
  VaultBoxTracker 17 → 61; plus (M3-A/C): OperatorTxBuilder 20, PaymentAttestation 9,
  DevOracle 6, FastContracts 4 → ergo 100; backend 87 (10 suites); e2e 10 (E2eFlow 5,
  E2eConfig 2, SchnorrPort 3) → 348 total. Full gate:
  `./gradlew :contracts:test :apps:core:dealprotocol:test :apps:core:ergo:test :backend:test :e2e:test`.
- **Before editing any `.es` file, read the "sigma-state 6 typing constraints" list in
  `specs/vault-contract.md` §5** — several natural ErgoScript constructs (tuple registers,
  `byteArrayToBigInt` in arithmetic, `SigmaProp ||` path selection) fail against sigma 6
  and the contracts are shaped around those constraints.

## External dependencies (referenced, not present)

- **Rosen bridge** — provides rsBTC wrapping (BTC leg). Its GuardSign/Lock contract
  pattern (github.com/rosen-bridge/contract) is the phase-2 oracle upgrade; the live
  watcher/guard federation cannot attest deal-scoped payment events, so the oracle forks
  the stack rather than reusing it. Chain support per current code: Ergo, Cardano,
  Bitcoin (+Runes), Ethereum, BSC, Doge, Firo, Handshake, Base — not Tron, Nervos, or
  Monero. The phase-1 centralized oracle observes Tron and Ethereum with self-built
  observers (no Rosen dependency); Rosen catches up at phase 2.
- **Phase-1 oracle** — a trusted centralized entity, authenticated on-chain by NFT
  (owner decision, 2026-09); see `specs/oracle-integration.md`. Trust-model language must
  call it trusted — and on the release path *solely* trusted (its attestation alone
  moves collateral) — with no "cost-to-attack" framing until the phase-2 threshold ships.
- **USE stablecoin** — over-collateralized by locked ERG; the vault collateral asset for USDT deals.
- **Bitcoin relay on Ergo** — research implementation at `github.com/ross-weir/ergohack-sidechain`.
- **Basis** — p2p cash framework (pillar 4), described only in `pillars.md`.
- **ErgoTree limitation:** hash opcodes are `blake2b256` and `sha256` only — no Keccak-256,
  which is why trustless in-script XMR/Ethereum-log verification is impossible today.
  This constraint shapes the XMR leg design.

## Editing guidelines

- This repo's deliverable is the documents themselves. When editing, keep the three onramp
  docs mutually consistent — the UX flows, contract parameters (timeouts, deadlines,
  maturation delays), and business-model numbers are cross-referenced and must match.
- The same rule extends to `specs/`: timing/fee constants are defined once in
  `specs/vault-contract.md` §2 and deal state names once in `specs/deal-protocol.md` §1;
  every other file references them by name. A parameter change means editing the owning
  table and sweeping the other files in the same change.
- When changing contract parameters, check the consistency note in `onramp-ux.md`'s
  preamble and update all affected docs in the same change.
- Preserve the `[approx]`/`[spec]`/`[solid]` markers and source citations; add new ones
  rather than dropping the labeling when introducing figures.
- Doc changes have no automated check beyond the contracts suite above: "verifying a
  change" means re-reading the edited document and checking cross-references between the
  four root docs and the specs.

## Security/regulatory considerations documented in the repo

Agents should not weaken these positions when editing, as they are deliberate design choices:

- The protocol never touches fiat and never custodies buyer funds; money-transmitter
  obligations attach to operators' in-person cash-collection leg only (see
  `onramp-business-model.md` §7).
- Vault collateral must be sellable in a dispute — DEX liquidity of USE/rsBTC caps deal
  size, not contract correctness.
- Privacy design: mixer/stealth-address funding of vaults, deal-scoped signing keys,
  no accounts/KYC in the app (AML check is seller-side and off-chain).
