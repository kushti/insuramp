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
(`specs/android-app.md` §4) — `ChainSource` with two implementations (`ExplorerChainSource`,
mainnet-default; `NodeChainSource`, any Ergo node's `/blockchain` extra-indexer API with
multi-URL failover — selected in the backend via `P2P_CHAIN_SOURCE=node` + `P2P_NODE_URL`),
`VaultBoxTracker`
(chain facts → `DealEvent`s), `ClaimTxBuilder` (the two buyer-side txs), `OperatorTxBuilder`
(fund/reclaim/release/contest), `DevOracle`
(attestation-box builder; release paths reference the oracle box as a **data input** carrying
the bare 32-byte `dealId` attestation, so
there is no oracle signature in buyer/seller txs — the `OracleSigner` co-signing seam is
deleted), `ErgoContracts` incl. `compileFast` variants. Every built tx is
prover-verified against the compiled vault scripts. Pure Kotlin/JVM on ergo-appkit 6.0.1;
Android-injectable via interfaces.
`backend/` is live since 2026-09-17 (milestone M3-B, M4 seller-meeting additions): the
runnable Ktor operator backend (`./gradlew :backend:run`, Netty, port 8080) — deal engine
(sole transition authority), chain watcher, vault manager (state-aware reclaim:
FUNDED/PAYMENT_CONFIRMED only; funding is offer-driven: `POST /v1/dashboard/deals/{id}/accept`
is the only fund path, `/decline` closes unanswered offers), quote publisher (multi-quote
feed since 2026-09-19 —
`GET /v1/quotes` returns `{quotes: [...]}`, operator publishes/withdraws via
`/v1/dashboard/quotes`; `P2P_DEMO_QUOTES=true` seeds three located example quotes), buyer/dashboard `/v1` APIs + WebSockets,
dispute inbox, AML `RiskScorer` hook, infra monitor + auto-pause. In-memory `DealStore`
behind a JDBC-shaped interface (PostgreSQL impl is the documented follow-up);
`TxSubmitter` seam for broadcast. (The in-contract protocol fee was removed
2026-09-18: `QuotePublisher` has no `protocolFeeBps` and there is no
`P2P_TREASURY_SECRET` wiring.) The buyer-authed `POST /v1/deals/{id}/handoff` uploads
the seller-signed handoff record; the dashboard-authed
`POST /v1/dashboard/deals/{id}/handoff/sign` Schnorr-signs it server-side with the vault
R5 key and drives `CashCollected` FUNDED → PAYMENT_PENDING, with
`GET /v1/dashboard/deals/{id}/handoff/qr.png` rendering the `p2pgate://handoff?m=...` QR
(ZXing). The buyer deal DTO exposes `sellerPubKey` (the R5 key the app verifies the
signature against). The static seller dashboard (`backend/src/main/resources/dashboard/`)
is served at `/dashboard/` — public shell, token-gated API (`P2P_OPERATOR_KEY`; unset =
demo-open). Release/contest txs take the oracle attestation box as a data input
(`OracleClient.attestationBoxFor`); the oracle serializes attestation postings — one in
flight, a release must confirm on-chain before the next posting.
`e2e/` is live since 2026-09-17 (milestone M3-C): the end-to-end gate
(`./gradlew :e2e:run`, `--dry-run` for a no-broadcast build against live chain) —
funded operator (manual funding by default; testnet faucet via `E2E_FAUCET_URL`),
minted dev-oracle NFT + test collateral, and three real flows
(release via oracle attestation, dispute claim-open → maturation → payout — the
maturation is the hardcoded 360 blocks (~12h) since 2026-10-03, so a live flow B
waits it out; `--dry-run` sim-advances — timeout
reclaim). Mainnet is the default target since 2026-09-17 ("change Ergo testnet to
mainnet everywhere, lets test with mainnet"); testnet stays fully selectable via
`E2E_EXPLORER_URL`/`E2E_FAUCET_URL`.
`tui/` landed 2026-10-02 (`specs/tui-apps.md`): two full-screen terminal consoles, built
with Mosaic 0.18.0 (Compose for the terminal) in their own top-level tree — `apps/` is
the Android app's. `:tui:common` holds the shared `BackendClient` (a Ktor client behind
an interface, so tests run on `MockEngine`) plus the hand-mirrored wire DTOs; `:tui:seller`
is the operator console (a nine-column kanban of live deals, states as columns, with the
handoff QR drawn in the terminal by `TerminalQr` — ZXing plus half-block glyphs); the
seller console **holds no keys**, every seller-signed tx is built by the backend's
`VaultSigner`. `:tui:buyer` is the buyer console: key custody in one passphrase-encrypted
file (`KeyVault` — AES-256-GCM, PBKDF2-HMAC-SHA512 210k, header authenticated as AAD,
`rw-------` set before the first byte is written), needed only for the path-D payout since
path B is `sigmaProp`-only; and it **builds and broadcasts its own claim transactions**
via `:apps:core:ergo`, which is the gap the Android app leaves open. It reads the vault
box from the chain and verifies the seller's signature against the box's **R5**, so it
does not repeat the Android app's "server-supplied `sellerPubKey`" flaw — but it
deliberately does *not* check the fiat amount, which is not on-chain. Two things to know before editing: Mosaic needs a real TTY, so
`./gradlew run` does not work — but a *synthesized* TTY does, so both consoles have
been run against a live demo backend via `script -qec … /dev/null` (see the spec's §8,
which lists the eight bugs that found and the four things it still leaves unproven); and
Mosaic 0.18.0 needs Kotlin ≥ 2.2.10, which is why the
repo was bumped 2.0.21 → 2.2.21. `:tui:seller:check` runs `checkDashboardCoverage`,
which fails if a dashboard API path has no `BackendClient` counterpart — that catches
coverage, not field drift.
The buyer app module (`apps/app`, native Android, `specs/android-app.md`) and the
seller dashboard front-end (`specs/seller-dashboard.md`, a static web app served by the
Ktor backend at `/dashboard/`) landed in M4 (2026-09-17/18); the rest of `apps/` remains
reserved-only.
**The buyer app was unblocked end-to-end on 2026-09-27:** deal creation works against a
real backend (it previously sent a base58 address where hex was expected, and sent the
buyer's single input as *both* legs of the deal). The offer is now "you get N USDT" with
the cash leg derived from the quote's rate — quotes publish a `fiatPerUsdtMicros` rate
and the backend re-derives the cash leg, rejecting a mismatch; the seller's `spreadBps`
is metadata and is applied nowhere (owner decision). `TronAddress` and `FiatAmounts` in
`:core:dealprotocol` are the single implementations of the address codec and the cash
derivation, shared by the app and the backend. The same pass made the meeting flow
reachable at FUNDED (it was gated behind PAYMENT_PENDING, i.e. after the moment it was for),
routed `p2pgate://handoff?m=…` links into the meeting screen, showed the recovery link
(built from the configured operator, not a hardcoded domain), and added the deal list and
delete-all-data flow. The claim path is still unbuilt — the app has no chain layer and
returns instruction strings instead of transactions; see the open backlog in
`specs/README.md`.
There is no `pyproject.toml`,
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
  releases the collateral, the seller co-signing the payout, phase-2 Rosen-derived guard threshold). The reverse direction
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
vault; the claim needs the seller-signed handoff record (the seller's R5 key, the
same key that reclaims the collateral) and the release on the oracle's attestation
**alone** — the phase-1 oracle is trusted,
period, on the release path. The broader vision is Ergo as pillar 3 of
"state-independent 21st century money": trust-minimized onramping.

## Document map

| File | Role |
|---|---|
| `pillars.md` | Top-level vision: four pillars of state-independent money (Ergo ledger/reserve → USE stablecoins → Rosen cross-chaining/onramp → Basis p2p cash issuance). Context for everything else. |
| `onramp-insurance.md` | **Core design doc — read this first.** Vault contract skeleton, per-asset designs (USDT oracle-verified, BTC trustless via Bitcoin relay, XMR oracle-verified via tx-key reveal), trust-model comparison, limitations, sources. |
| `onramp-ux.md` | Product design for the two sides of the marketplace — buyer app (native Android app) and seller meeting flow — plus the operator dashboard. Flow timings must stay consistent with the contract design (24h timeout, 12h claim maturation, ~6h BTC deadline). |
| `onramp-business-model.md` | Protocol-layer economics: fee model (the in-contract protocol fee was removed 2026-09-18; protocol revenue is undefined/deferred [spec]), capital dynamics (protocol TVL ≈ outstanding deal volume; collateral availability is the binding constraint), volume scenarios, risks, bootstrapping sequence. |
| `specs/tui-apps.md` | The two terminal consoles (`tui/`): the operator kanban console and the buyer console (both landed), key custody, and what a headless test cannot verify. |
| `specs/` | Implementation specs (index: `specs/README.md`): vault contract, deal protocol, oracle integration, seller dashboard, buyer app, operator backend. Canonical constants live in `specs/vault-contract.md` §2; canonical state names in `specs/deal-protocol.md` §1. `specs/vault-contract-review.md` is a 2026-09-26 adversarial review of the shipped vault contracts — **findings open, not normative**; its §8 lists the spec edits it asks for (not applied). |

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
  attestation is the only gate on the payout; the seller's co-signature is its own
  automation, not an independent check). The docs' own rule: "not trustless, but the
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
65-byte two-key packing is gone; Ergo boxes have R4–R9 only, no R10), and since 2026-10-03 the
PAYMENT_PROVEN box pins it per-box in **R9**, copied from the FUNDED box's R7 at claim-open
(the compile-time `%%ORACLE_NFT_ID%%` pin is gone — neither vault tree embeds the NFT, so
`ErgoContracts.compile`'s `oracleNftId` is now only a deployment descriptor for funding and
tracker classification), path B needs
the seller-signed handoff record (a single Schnorr half under the R5 seller key, no
oracle on the claim path), and paths C/C′ take the oracle box as a **data input** — the
oracle's attestation alone releases, co-signed by the seller (NFT custody is the authenticity anchor;
no oracle signature exists in release txs). Since 2026-09-24 every payout path (A, C, C′, D)
leaves the payee free — the signing side's `proveDlog` authorizes the spend and only the full
collateral is conserved (key rotation); there is no receipt signature anywhere in
the protocol. A 2026-09-26 follow-up fixed the branch discriminators the payee pin
had been doing double duty as: path B is discriminated first (context-var-0 presence)
so post-timeout claim-opens stay possible, and path D requires no data inputs so
post-maturation contests (C′) stay reachable. A 2026-10-04 pass replaced the FUNDED box's
*inferred* discriminator with an explicit action byte (context var 0, a `Byte` naming
CLAIM / RECLAIM / RELEASE, `ContractParams.ACTION_*` — the Basis reserve contract's shape):
every path is now positively identified, the branch-ordering hazard is structurally gone,
and an unknown code is rejected instead of falling through to release. The codes are
  **hardcoded literals** in the `.es` (structural, not deployment parameters — nothing
  varies them between a mainnet and a fast e2e compile; `CLAIM_MATURATION_BLOCKS`
  went the same way on 2026-10-03, hardcoded as `360L` in `vault_payment_proven.es`
  with `ContractParams.CLAIM_MATURATION_BLOCKS` mirroring it for the off-chain code);
  `ContractParams.ACTION_*` mirrors them for the builders and `FundedActionSpec` asserts the
  pair never drifts, since a drifted code fails quietly as an unspendable vault rather than
  loudly. It is a **breaking change to the FUNDED spend interface** (the var is mandatory;
  path B's material moved from vars 0–3 to 1–4) — safe only because no FUNDED box has ever
  been funded on mainnet. One semantic change came with it: a post-timeout release tx is now
  checked against the attestation rather than being spent as an attestation-free reclaim (the
  seller's recourse is unchanged — he builds a RECLAIM). On 2026-10-03 (owner decision)
`vault_payment_proven.es` followed the same pattern: it reads the action byte from context
var 0 (`ContractParams.ACTION_CLAIM_PAYOUT` 0 / `ACTION_CONTEST` 1) instead of inferring
D vs C′ from `dataInputs.size` — which path D keeps as a hygiene check — and the
`%%CLAIM_MATURATION_BLOCKS%%` substitution became the hardcoded literal `360L`, mirrored by
`ContractParams.CLAIM_MATURATION_BLOCKS` for the off-chain code (`FundedActionSpec` guards
both pairs against drift; consequence: a live e2e flow B waits the real ~12h of maturation).
The two-box split remains load-bearing (a script cannot delete one of its own spending
paths — `specs/vault-contract-review.md` §5.2). On 2026-09-21 (pre-launch, owner decision) the attestation payload was
simplified to the bare 32-byte `dealId`: the oracle box's R4 carries only the dealId
(the 112-byte `PaymentAttestation` field codec is deleted), the vault's R9 31-byte
funding binding is deleted from both boxes, and release paths check only NFT custody +
`dataInput.R4 == vault.R4 (dealId)` plus the seller's payout co-signature. The buyer's receive address is no longer pinned
on-chain; "the USDT went to the right address" is wholly the oracle's off-chain
assertion (`specs/oracle-integration.md` §2.2). The in-contract protocol fee was removed on 2026-09-18 (owner
decision): `ContractParams.PROTOCOL_FEE_BPS` (25 bps, in-contract since
2026-09-17) is deleted, no treasury fee output exists, and every
collateral-moving path pays the recipient in full — the miner fee is unchanged.
FUNDED R8 is the plain `Long` `timeoutHeight`, PAYMENT_PROVEN R7 the plain
`Long` `proofHeight` (that register shape landed 2026-09-17 and stayed). The
suite is the on-ramp matrix in `specs/vault-contract.md` §7.

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
  `VaultContractSpec` 56 (1 `@Disabled`: phase-2 GuardSign readiness, test 45;
  the 2026-09-18 fee removal deleted the old 35a–35e fee tests; the 2026-09-21
  dealId-only payload removed tests 22, 23, 25, 29; the 2026-09-24 payout-freedom
  change added tests 46–48 and inverted test 21; the 2026-09-26
  branch-discriminator fix added tests 49–52; the 2026-10-04 action-var refactor
  added tests 53–56 and split test 51 into with/without-attestation; the
  2026-10-03 PAYMENT_PROVEN action-byte change added tests 57–59, and the
  same day's per-box NFT pin (R9) added tests 60–61) plus
  `OracleContractSpec` 8 + `FundedActionSpec` 6 (the drift guard for the hardcoded
  action literals vs `ContractParams.ACTION_*` in both vault scripts, plus the
  hardcoded `360L` maturation vs `ContractParams.CLAIM_MATURATION_BLOCKS`) →
  contracts 70;
  dealprotocol module: DealStateMachine 44,
  Messages 10, QrPayload 11, DealTerms 22, Blake2b256 7, plus (2026-09-27)
  TronAddress 8 + FiatAmounts 10 → 113; ergo module:
  ClaimTxBuilder 14, HandoffRecordVerifier 9, ExplorerChainSource 12, SchnorrVerifier 9,
  VaultBoxTracker 24; plus (M3-A/C): OperatorTxBuilder 15,
  DevOracle 6, FastContracts 4; plus (2026-09-17): NodeChainSource 13 → ergo 106
  (the 2026-09-21 payload simplification deleted `PaymentAttestation` and its
  9-test suite, and collapsed the five per-field release-tamper tests into one;
  the 2026-10-01 tracker fix re-derived payout classification from collateral
  conservation + spend shape instead of the payout tree, so it also added the
  key-rotated-payee and conservation cases — see `specs/android-app.md` §4.2);
  backend 121 (11 suites; the 2026-10-03 live-console run fixed the demo-quote
  seeding bug — `P2P_DEMO_QUOTES=true` seeded nothing because `maxAmount` was
  derived from an empty pool, and the existing test asserted the broken
  behaviour); e2e 10 (E2eFlow 5,
  E2eConfig 2, SchnorrPort 3) → JVM modules 420; plus the Android buyer app
  (`apps/app`, M4; + map view, localization hi/sw/ar/ru, in-app locale switcher,
  multi-quote currency-filtered list, offer-cash flow 2026-09-20, and the
  2026-09-27 pass: USDT-leg offer math with a rate-derived cash leg, TRON
  address validation, meeting reachable at FUNDED, handoff deep links, deal list
  + delete-all, recovery link, reconnecting sockets): 95 JVM
  unit tests (`:app:testDebugUnitTest`); plus the terminal consoles
  (`tui/`, `specs/tui-apps.md`): `:tui:common` 30 (KtorBackendClient 16, Format,
  TuiConfig) + `:tui:seller` 24 (lanes, screen snapshot, terminal QR, status line) +
  `:tui:buyer` 41 (KeyVault 17, BuyerFlow 15, screen snapshot) → JVM modules 515;
  plus the Android app's 95 → **610 total**.
  Full gate:
  `./gradlew :contracts:test :apps:core:dealprotocol:test :apps:core:ergo:test :backend:test :e2e:test :tui:common:test :tui:seller:test :tui:buyer:test :app:testDebugUnitTest :app:assembleDebug`
  (headless SDK at `~/.local/opt/android-sdk`; root `local.properties` sets sdk.dir).
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
  call it trusted — and on the release path *solely* trusted (its attestation is the only
  gate on the payout; the seller co-signs automatically, which is not an independent
  check) — with no "cost-to-attack" framing until the phase-2 threshold ships.
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
