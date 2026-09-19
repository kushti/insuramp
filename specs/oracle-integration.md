# Oracle Integration — Payment Proof for the cash→USDT On-Ramp (USDT Leg)

*Implementation spec for the payment-verification oracle used by the cash→USDT
on-ramp leg described in `onramp-insurance.md` §3.1 (read that first). Depends on `onramp-ux.md`
§4 (operator infrastructure status), `onramp-business-model.md` §2–§3 (fee model), and
`specs/vault-contract.md` (canonical timing constants and the vault contract's spending
paths — referenced by name, never redefined). Backend services are Kotlin/Ktor; the vault
contract is ErgoScript tested via ergo-appkit/sigmastate JVM tooling.*

**Two phases, one output format.** Phase 1 (launch): a **trusted centralized oracle**,
authenticated on-chain by an NFT — a single trusted entity, stated plainly and not dressed
up as anything else. Phase 2 (post-launch): a k-of-n guard threshold derived from the
Rosen bridge's GuardSign/Lock contract pattern (§3.2). The **payment-proof digest format
(§2) is identical in both phases** — the upgrade swaps only the authentication check in
the vault contract, not the register layout, deal protocol, wire formats, or apps.

Timing constants used throughout are owned by `specs/vault-contract.md`: `RECLAIM_TIMEOUT`,
`CLAIM_MATURATION`, `BTC_DEADLINE` (extension note only). (The in-contract
protocol fee, `PROTOCOL_FEE_BPS`, was removed entirely on 2026-09-18 — it never
involved the oracle; see §6.)

Deal state machine (canonical names, owned by `specs/deal-protocol.md` §1): QUOTED → FUNDED →
PAYMENT_PENDING → PAYMENT_CONFIRMED → RELEASED; quote expiry (`QuoteExpired`) closes a deal
that never funded, with no on-chain footprint; the timeout branch FUNDED → RECLAIMED (buyer
no-show); the dispute branch PAYMENT_PENDING →
CLAIM_OPENED → CLAIMABLE → CLAIMED (claim opens once cash is collected and the seller has not
paid; claims without cause from PAYMENT_CONFIRMED are possible and are countered with the
oracle digest alone). PAYMENT_CONFIRMED means the oracle confirmed the seller's USDT transfer;
the release follows without any buyer action.
On-chain vault box states: FUNDED box → spent (routine release via path C or reclaim via
path A), or FUNDED box → PAYMENT_PROVEN box → spent (dispute: contest via path C′ or
payout via path D).

## 1. Role of the oracle

The contested event in a cash→USDT on-ramp deal is: **the seller transferred exactly the
agreed amount of USDT to the buyer's receive address on the source chain.** That event is
publicly visible on a transparent chain; no party to the deal should get to assert it
unilaterally — least of all the seller, whose own collateral release depends on it.

The oracle's job is narrow:

1. Observe the source chain for a USDT `Transfer` matching (deal's `recipientAddr` in R9 —
   the buyer's USDT address, exact amount).
2. Wait for the source-chain confirmation rule (§4.2) to be satisfied.
3. **Screen the transfer as non-tainted (§4.3)** — Tether blacklist/freeze exposure on
   Tron, sanctions screening on Ethereum. This is an attestation precondition.
4. Attest the event by **publishing the attestation on-chain**: the oracle spends its
   singleton box and re-creates it with the 112-byte payment-proof payload in R4 (the
   classic oracle-pool datapoint pattern). Vault release paths (C/C′) then reference
   that box as a **data input** — on-ramp, **only the release** — the vault contract
   authenticates the oracle by its NFT (§3.1). The oracle never signs buyer/seller
   transactions.

The oracle never holds Ergo-side collateral funds, never sees fiat or cash, and never
decides a dispute outcome — it only attests that a source-chain event happened. The
contract does the payout math. In phase 1 the trust concentration is total and explicit:
the oracle is a single trusted entity for the payment leg, and its attestation is **solely
sufficient** on the release paths (§5.3 says this without euphemism).

The clean flip versus the off-ramp reading: the off-ramp gated the *claim* on the oracle
(proof the buyer paid); the on-ramp gates the *claim* on the **seller-signed handoff record**
(proof cash was collected — there is no payment for an oracle to attest at claim time) and
gates *release* on the oracle digest alone (proof the seller's USDT arrived,
`specs/deal-protocol.md` §1). Path B therefore involves the oracle **not at all**.

The attestation is consumed two ways:

1. **Off-chain:** the oracle's confirmation signal advances the deal from
   PAYMENT_PENDING to PAYMENT_CONFIRMED — the buyer's "USDT confirmed" indicator. The
   FUNDED box is untouched.
2. **On-chain:** the attestation box is a **data input** to the release (path C: oracle
   digest alone, routine close; path C′: the same digest from the PAYMENT_PROVEN box,
   contesting a claim). The digest rides in the data input's R4 — the PAYMENT_PROVEN
   box carries the handoff record, not the digest. Data-input scripts never execute, so
   **the release transaction contains no oracle signature**; NFT custody is the
   authenticity anchor (§3.1, §5.3). **The attestation alone is sufficient on both
   paths: there is no receipt signature anywhere in the protocol.** The release follows
   the attestation without any buyer action — which is exactly why the phase-1 oracle is
   trusted, period (§5.3).

## 2. Payment-proof digest format (permanent — both phases)

### 2.1 ErgoTree constraint

ErgoScript hash opcodes are `blake2b256` and `sha256` only — no Keccak-256. Consequences:

- The contract **cannot** re-derive an Ethereum/Tron transaction hash or verify a
  Merkle-Patricia proof of the `Transfer` log in-script. Source-chain data arrives as
  opaque bytes attested by the oracle.
- What the contract **can** do: check attestation fields against box registers, hash with
  `blake2b256`, and verify Schnorr signatures on secp256k1 (used for the handoff record
  and the guard threshold in phase 2).

So the digest is designed so that every field is either (a) fixed at vault funding time
and stored in the FUNDED box registers (R4 `dealId`; R9 `srcChainId`/`tokenId`/
`recipientAddr`/`expectedAmount`), or (b) supplied by the attestation and checked against
those registers in the release spend.

### 2.2 Byte layout

All integers big-endian. `digest = blake2b256(payload)` where `payload` is the exact
concatenation:

| Offset | Field | Size | Set at | Notes |
|---|---|---|---|---|
| 0 | `version` | 1 B | funding | `0x01`; bump on format change |
| 1 | `dealId` | 32 B | funding | `blake2b256(deal terms)` per `specs/deal-protocol.md` §3.1; binds the proof to one deal |
| 33 | `srcChainId` | 1 B | funding | registry shared with the deal terms: `0x01` = Tron, `0x02` = Ethereum |
| 34 | `tokenId` | 1 B | funding | `0x01` = USDT on the given chain (mirrors the deal-terms `asset` byte); guards against a same-address different-token attestation |
| 35 | `recipient` | 21 B | funding | source-chain address payload, left-padded with zeros: 21 B Tron (base58check payload), 20 B Ethereum (right-aligned). **On-ramp: the buyer's USDT receive address** (R9 `recipientAddr` — the seller pays the buyer) |
| 56 | `amount` | 8 B | funding | uint64 in USDT base units (6 decimals on both chains); exact-amount deals only — no partial-payment semantics |
| 64 | `srcTxId` | 32 B | attestation | source-chain tx hash as opaque bytes (Tron/ETH hashes are both 32 B; no hashing of it required in-script) |
| 96 | `srcBlockHeight` | 8 B | attestation | uint64 |
| 104 | `srcBlockTime` | 8 B | attestation | uint64, unix seconds |

Total payload: 112 bytes; digest: 32 bytes. [solid] for sizes; the chain/token registries
are shared with the deal-terms format in `specs/deal-protocol.md` §3.1.

**This layout is the permanent oracle output format.** The phase-2 threshold upgrade
(§3.2) must keep it byte-identical: guards sign this same digest, and the vault's field
checks are untouched.

The funding-set fields are pinned in the vault box registers when the FUNDED box is
created. The contract slices the attestation-supplied payload and checks
`dealId`/`srcChainId`/`tokenId`/`recipient`/`amount` against its registers — the oracle
cannot attest a different deal, address, or amount for *this* vault. Replay safety:
`dealId` is unique per deal; cross-chain replay is excluded by `srcChainId`, cross-token
by `tokenId`.

## 3. Authenticating the oracle

### 3.1 Phase 1 — centralized oracle, NFT-authenticated

- A single **oracle box** on Ergo carries `ORACLE_NFT` (a compile-time constant of the
  vault contract, also pinned in each vault's R7 so vaults survive oracle-box moves) and
  is governed by `proveDlog(oracleKey)`. Its R4 holds the current 112-byte attestation
  payload (§2.2).
- Any vault spend that needs the payment proof — on-ramp, the release paths C and C′
  only — **includes the oracle box as a data input** (`CONTEXT.dataInputs(0)`). The
  vault contract checks the data input's token id against R7 (or the compile-time pin)
  and the payload's `dealId`/`srcChainId`/`tokenId`/`recipient`/`amount` against R4/R9.
  Because data-input scripts never execute, **no oracle signature exists in the release
  transaction** — authenticity reduces to NFT custody, and any box carrying the oracle
  NFT with a matching R4 payload passes, foreign-script boxes included (`specs/vault-contract.md`
  §7 test 20; §5.3 states this honestly). The claim path (B) takes no oracle input at
  all: it is gated on the seller-signed handoff record.
- **One attestation in flight (hard serialization constraint).** The attestation box is
  a singleton: posting the attestation for deal Y **spends** the box holding deal X's
  attestation, which invalidates any still-mempool release tx that referenced it as a
  data input. Therefore a deal's release must **confirm on-chain** before the oracle
  posts the next attestation, and the oracle service must **serialize attestation
  postings per pending release** — if a release tx is still in mempool, the oracle waits
  and re-checks (by deal id / box id) before posting. This bounds phase-1 oracle
  throughput to one pending release at a time and makes prompt operator-side release
  submission part of the oracle's liveness.
- The oracle publishes an attestation **only after** its daemon has confirmed the
  seller's USDT transfer to the R9 recipient per §4.2 **and** the transfer has passed
  the §4.3 taint screen. Refusing to attest is the oracle's only *honest* power; it
  cannot redirect funds (the vault's own paths fix every output) — but note its
  attestation alone releases the vault, so a *dishonest* posting moves collateral with
  no on-chain check (§5.3).
- Flow: the operator backend builds the release transaction itself (operator wallet
  only — no oracle co-signature, no oracle-key fee inputs) and attaches the oracle's
  current attestation box as the data input, fetched via the oracle's attestation API
  (§4.1). In code this is a seam, not yet a deployed service (M3, 2026-09-17): the
  backend resolves the attestation box through `OracleClient.attestationBoxFor(dealId):
  ChainBox?`, with the in-process `DevOracle` wired in tests/dev (and in the e2e gate,
  which mints a dev-oracle NFT and drives the full release/contest flows); production
  swaps in a remote attestation-box provider behind the same seam. `DevOracle.attestationBox(attestation)`
  builds the box; the old `OracleSigner` / `DevOracle.signer()` / `releaseInputBox()`
  co-signing seam is deleted — the backend's dev whole-tx co-signing deviation is gone
  with it.
- Key management: `oracleKey` in an HSM or encrypted keystore; the oracle box is
  self-recreated as `OUTPUTS(0)` of each attestation posting (rotation spend; NFT
  preserved by its script). The script's rotation rules (NFT id + amount and `value`
  preserved into `OUTPUTS(0)` — the reproduction position is pinned on the oracle's own
  spends; fixed 2026-09-17, see `specs/vault-contract.md` §8.4) are tested in
  `contracts/src/test/kotlin/p2pgate/contracts/OracleContractSpec.kt`.

### 3.2 Phase 2 — k-of-n guard threshold (Rosen-derived, post-launch)

The upgrade replaces exactly one check in the vault contract: "the data input carries
the oracle NFT and its R4 payload matches R4/R9" becomes "the data input is the
guard-set box (NFT) providing `Coll[Coll[Byte]]` guard keys + threshold, and
`atLeast(k, guardPks)` sign the digest (per the Schnorr verification in
`specs/vault-contract.md` §5)" — the guard signatures ride in the release tx as context
vars, restoring a cryptographic gate where phase 1 has NFT custody only. Everything
else — digest format, register layout, wire formats, apps — is untouched. The claim
path stays oracle-free in both phases.

This pattern is not invented; it is lifted from the Rosen bridge's production contracts
(github.com/rosen-bridge/contract, MIT):

- **`GuardSign.es`** — a guard-set box holding a GuardNFT, `R4 = Coll[Coll[Byte]]` guard
  public keys, `R5 = [paymentThreshold, updateThreshold]`. Rotation = spending the box
  with the update threshold and writing new R4/R5; the guard address never changes.
- **`Lock.es`** — Rosen's bank contract; reads the guard box as a **data input** and
  requires `atLeast(paymentThreshold, pks.map(proveDlog ∘ decodePoint))`. This is exactly
  the k-of-n secp256k1 threshold-in-ErgoScript our phase 2 needs, production-hardened on
  Rosen mainnet.

**What the Rosen research ruled out** (verified against rosen-bridge source, see Sources):

- **The live Rosen federation cannot attest deal events.** Watchers only observe
  transfers to Rosen's own lock address carrying Rosen bridge metadata
  (`rosen-extractor`, rcs-003); there is no dynamic watch-address hook. Guards sign only
  Rosen-constructed payout transactions (`guard-service`'s `txAgreement` accepts only
  Rosen tx types). Reusing the *live set* would require Rosen team coordination, not
  configuration.
- **Rosen watchers do not sign event digests.** They post commit-reveal boxes on Ergo
  (`Commitment.es`: `R6 = blake2b256(eventData ++ WID)`), backed by a whole RSN→X-RWT
  permit economy with slashing via a trusted cleanup service (`Fraud.es`). Our phase-2
  design deliberately deviates: guards sign digests off-chain so routine deals keep a
  minimal on-chain footprint — we trade away Rosen's on-chain auditability and
  commit-reveal anti-copycat properties, and get Sybil resistance from guard collateral
  instead of the permit economy.
- **Rosen has no Tron support.** Verified chain support in code: Ergo, Cardano, Bitcoin
  (+Runes), Ethereum, BSC, Doge, Firo, Handshake, Base. The Tron observer is self-built
  from phase 1 on (§4.1); if Rosen adds Tron later, the phase-2 fork can adopt its
  machinery.
- **Forkable software:** `rosen-bridge/scanner` + the EVM observation extractor (MIT) are
  the starting point for the phase-2 Ethereum observer; `guard-service`'s aggregation and
  `rosenet` p2p layer are reference implementations for guard coordination.

Indicative phase-2 parameters: k ≈ 60–70% of n per the Rosen watcher-threshold posture
[spec]; watcher/guard double layering (guards independently re-verify each event against
the source chain before signing — Rosen's core assumption, worth keeping verbatim).

### 3.3 Key ceremony and rotation

- Phase 1: `oracleKey` generated at protocol deployment; rotation = deploy a new oracle
  box with a new NFT and publish new vault contract constants — in-flight vaults keep
  their pinned `oracleNftId` (R7), so rotation never strands a live deal.
- Phase 2: guard-set rotation follows `GuardSign.es` exactly (update-threshold spend
  writes the new key set); vaults reference the guard box by NFT and always see the
  current set. A guard key compromise triggers emergency rotation plus a quote-publishing
  pause (§5.2) until the new set is live.

## 4. Oracle architecture (Kotlin)

Headless daemon, systemd unit, config file, structured logs, Prometheus metrics. No UI;
operators see status through the dashboard's infrastructure panel (`onramp-ux.md` §4).
Phase 1 has one signer; the architecture already separates observation from signing so
phase 2 multiplies signers without reshaping the oracle.

**Implementation status (M3, 2026-09-17):** this daemon is not yet deployed, and no
source-chain observer exists yet — stated plainly. What has landed is the *seam* and
the attestation machinery: the 112-byte `PaymentAttestation` payload (`:apps:core:ergo`),
the `DevOracle` (NFT-bearing oracle box; `DevOracle.attestationBox(attestation)` builds
the release's data input — the old whole-tx co-signing `OracleSigner` seam is deleted),
the backend's `OracleClient`/`DevOracleClient` (attestation registry + liveness probe +
`attestationBoxFor` + the serialization rule of §3.1), and the e2e gate driving release
and contest end-to-end with a minted dev-oracle NFT. The `POST /v1/attestations` /
`GET /v1/health` surface below is the specified shape of the production service; until
it exists, "the oracle confirmed" in every component is an assertion fed to `DevOracle`,
not an observation of Tron/Ethereum.

```
 Ethereum node ──┐                         ┌── signing oracle ───┐
                 ├──▶ observer ──▶ event DB ┤   (oracleKey, HSM)  ├──▶ attestation API (Ktor)
 Tron node ──────┘   (per chain,            │ posts attestations  │         ▲
 (both self-built —                         │ only for confirmed, │         │ attestation box
  no Rosen Tron                             │   screened events   │   fetched by the operator
  support)                                  └─────────────────────┘   backend (release's data input)
```

### 4.1 Components

- **Source-chain observer (one per chain).** Kotlin daemon against a local node —
  Ethereum via JSON-RPC and Tron via its HTTP/gRPC API, both in phase 1. Both are
  self-built: Rosen has no Tron support, and its EVM scanner only understands Rosen lock
  events (the phase-2 fork may swap in `rosen-bridge/scanner`'s EVM machinery; the Tron
  observer remains ours until — and unless — Rosen catches up). The watch set is fed at
  deal funding: when a deal enters FUNDED, the operator backend registers `(dealId,
  srcChainId, recipient = the buyer's USDT address, amount, expiresAt = fundedAt +
  RECLAIM_TIMEOUT)` over an internal RPC (mTLS, never exposed to deal parties). The
  observer matches on exact `(recipient, amount)` in a single transaction — never
  partial or multi-tx sums.
- **Event DB.** Durable store (PostgreSQL) of candidate events with confirmation
  progress; idempotent re-scans on restart; reorg handling by rolling back events whose
  block left the canonical chain.
- **Confirmation tracker.** Promotes a candidate event to *signable* only when §4.2's
  rule holds.
- **Taint screen (attestation precondition, §4.3).** Screens the observed transfer
  before it can become signable; a transfer that fails screening is never attested.
- **Signing oracle.** Holds `oracleKey` (HSM or encrypted keystore). Its one on-chain
  write is the **attestation posting**: spend the singleton and re-create it at
  `OUTPUTS(0)` with the 112-byte payload in R4 — but only after verifying the deal event
  is signable: confirmed per §4.2, screened per §4.3, and the payload fields match the
  confirmed event. Refuses: unsigned/unconfirmed events, field mismatches, events that
  failed the taint screen, and a second `srcTxId` for the same `dealId` (one payment per
  deal — the vault accepts exactly one). It never sees or signs buyer/seller
  transactions. **Serialization:** one attestation in flight — it never posts deal Y's
  attestation while the release using deal X's attestation is unconfirmed; a
  mempool-pending release blocks the next posting, re-checked by deal id / box id (§3.1).
- **Attestation API (Ktor).** External surface:
  - `POST /v1/attestations` with `{ dealId }` → `{ boxId, payload }` — the current
    attestation box, ready to be attached as the release's data input, once the event is
    confirmed and screened; or `202` with confirmation progress (`{ srcTxId, height,
    confirmations, required }`) while pending (or `409` while a previous attestation is
    still in flight per the serialization rule). The release transaction itself is built
    and signed by the operator backend alone — the oracle never sees it. The operator
    backend polls this to advance the deal to PAYMENT_CONFIRMED and to fetch the
    attestation box for the release; the buyer app reads confirmation status through the
    backend's attestation proxy (`specs/operator-backend.md` §2), never from the oracle
    directly.
  - `GET /v1/health` → per-chain observer lag (blocks behind tip), last signed
    attestation age. Feeds the dashboard infrastructure status and the auto-pause rule
    (§5.2).

### 4.2 Confirmation rules before signing

Sized so a source-chain reorg cannot flip an attested event; parameter-governed.
Starting points informed by Rosen's own per-chain observation depths
(guard-service defaults: Ethereum 50, BSC 900, Cardano 40, Ergo 14) [approx]:

- **Ethereum:** event in a finalized block, or ≥ 64 slot-confirmations as a pre-finality
  fallback. Finalized checkpoints are the clean rule; the fallback exists for finality
  stalls.
- **Tron:** event in a block confirmed by ≥ 19 subsequent blocks (solidity
  threshold ~1 minute [approx]).

Attestation never happens below the rule. A reorg that removes an attested event after
the release transaction has confirmed is the false-confirmation tail risk owned by the
(phase-1, trusted) oracle — one more reason the phase-2 threshold matters.

### 4.3 Taint screening before attestation

The oracle attests only that the seller's USDT transfer happened — but USDT carries
issuer risk the buyer inherits at receipt, so screening is an **attestation
precondition**: no screen, no signature.

- **Tron:** check the sending side of the transfer against Tether's blacklist/freeze
  state (frozen/blacklisted addresses are visible in the USDT contract's storage; the
  observer tracks `isBlackListed` status for the counterparties of the watched
  transfer).
- **Ethereum:** sanctions screening of the transfer's counterparties (e.g. the OFAC
  SDN-address lists tracked by the usual chain-analytics providers); a transfer touching
  a listed address is not attested.

**The heuristic limit, stated plainly:** screening is a point-in-time check at
attestation. Tether can freeze an address *after* the attestation lands — a transfer
that screened clean can still leave the buyer holding frozen USDT later, and nothing in
the protocol prevents or compensates that. Screening at attestation time reduces
exposure; it is **not a guarantee** of the received funds' future usability. In phase 2
each guard re-runs the same screens independently before co-signing (the watcher/guard
double-layer rule of §3.2 applies to screening too).

## 5. Liveness & safety

### 5.1 Lag monitoring

Each observer exports blocks-behind-tip and last-event-scan age. Alerting thresholds are
operator-set; the daemon exposes only metrics. A stalled observer, a desynced source
node, or a DB write failure all surface as one health signal.

### 5.2 Auto-pause of quote publishing

Per `onramp-ux.md` §4: if the oracle is lagging, quote publishing pauses automatically —
never sell insurance you can't currently verify. Mechanically: the operator backend polls
`GET /v1/health` and refuses to publish quotes (and hides the buyer-side collateral line)
whenever any
active source chain's lag exceeds its threshold or the signing oracle is unreachable.
Deals already FUNDED are unaffected — their USDT confirmations and release attestations
land when the oracle recovers (subject to the one-attestation-in-flight serialization,
§3.1), and the buyer's claim path never depends on oracle liveness (it is gated on the
seller-signed handoff record, not the oracle). Only *new* insurance sales stop.

### 5.3 Trust model — stated honestly

**Phase 1 makes the oracle a single trusted third party for the payment leg — and on the
release path it is trusted *completely*.** On-ramp, the oracle gates *release*, and its
attestation is **solely sufficient**: a malicious or compromised oracle can attest a
payment that never happened — a seller-run oracle could publish a fake attestation of
its own "payment", and since the release path's authenticity anchor is NFT custody alone
(data-input scripts never execute), any box carrying the NFT with a matching payload
moves the collateral — with **no on-chain defense of any kind**. There is no buyer
signature on the release paths to withhold, and none to save a cheated buyer.
This is a deliberate launch trade-off, not a discovery, and it is accepted for phase 1.
It is exactly why:

- **oracle operator ≠ marketplace operator** — with the same entity running both, a fake
  digest would be self-certifying; separation means stealing requires capturing a second
  organization, not one insider; and
- **phase 2 is not optional decoration** — the k-of-n guard threshold (§3.2) is what
  eventually removes this single point of total trust.

Mitigations beyond those two structural points — all operational, none cryptographic:

- **Separation:** the oracle key is not the seller's vault key. Collusion then requires
  two entities, not one insider.
- **Transparency:** every attestation is on-chain (the payload sits in the singleton
  box's R4, readable by anyone) and auditable against public source-chain data — anyone
  can check that an attested `srcTxId` really paid `recipient`/`amount`. False
  attestations are publicly detectable after the fact, so oracle fraud is **ex-post
  provable** and the oracle's reputation (and phase-2 prospects) is continuously on the
  line. That is all that may be promised — detection and recourse after the fact, not
  prevention.
- **Blast radius cap:** max deal size = vault capacity is already enforced
  operator-side; protocol-level deal-size caps while the oracle is centralized keep a
  single false attestation small.
- **Liveness, not safety, for honest buyers:** oracle downtime delays release
  confirmation, but cannot steal collateral by silence and cannot block the
  buyer's claim — the claim path only starts once the seller-signed handoff record
  exists on-chain, and it takes no oracle input. `RECLAIM_TIMEOUT` interacting with a
  paid-but-unconfirmed transfer is an operational incident (seller fault, dispute
  evidence), not a protocol failure mode. (Dishonesty is the safety case above; silence
  is only a delay.)

Phase 2 restores the design's intended posture: not trustless, but cost-to-attack
(slashable guard collateral + future fee income) exceeding extractable value per deal
(`onramp-business-model.md` §3).

## 6. Compensation

- **Phase 1:** the oracle is run by the protocol side as fixed infrastructure cost — with the
  in-contract protocol fee removed 2026-09-18 there is no per-deal revenue to fund it.
  The cost-floor analysis of `onramp-business-model.md` §2 applies with the fee set to
  zero (below a volume threshold, verification costs exceed revenue → bootstrap subsidy).
- **Phase 2 [spec, unresolved]:** guard compensation is undefined while there is no
  protocol fee. *Pre-removal design, kept for the record:* watchers/guards were to earn
  **40–60% of the protocol fee** per `onramp-business-model.md` §3 (governance-adjustable),
  the vault's fee output flowing to an oracle-pool address controlled by the guard set,
  distributed pro-rata to signing watchers with a fixed guard-layer slice — compensation
  following signed work, not set membership (the Sybil-resistant property). Any future
  revenue model reopens this section; it does not inherit the old split.

## 7. Extension notes

- **XMR leg (rsXMR, proposed).** Identical machinery, different observer: the oracle
  (phase 2: each watcher) re-runs `check_tx_key` verification against its own Monero
  daemon (all daemon RPC, automatable — `onramp-insurance.md` §3.3) and attests the same
  digest format with `srcChainId` extended to Monero (`srcTxId` = Monero tx hash, opaque
  32 B; `recipient` repurposed to the deal's one-time XMR address hash). For XMR the
  oracle is **mandatory, not a fallback** — no trustless in-script path exists (no
  Keccak-256 in ErgoTree). Rosen's own Monero R&D (`docs/r-and-d/bringing-monero.md`)
  converges on the same `check_tx_proof`-published-on-Ergo design.
- **BTC leg (rsBTC).** Needs **no oracle**: payment proof is a trustless Bitcoin-relay
  inclusion proof verified in-script (`onramp-insurance.md` §3.2). Nothing in this spec
  applies to the BTC leg except the shared attestation-status API shape.
  `BTC_DEADLINE` (~6h, see `specs/vault-contract.md`) governs that leg only.

## 8. Open questions

1. ~~Reuse Rosen's live federation vs. a deal-scoped watcher set~~ **(resolved).**
   Neither for phase 1 (centralized oracle) nor literally for phase 2: the live set
   cannot serve deal events (§3.2). Phase 2 = fork of Rosen's contracts + scanner
   pattern with our own guard set.
2. **Who operates the phase-1 oracle.** Protocol team, a trusted community entity, or a
   small multisig-of-known-parties even before the full guard machinery? The NFT design
   permits upgrading the oracle box's script to a small `atLeast(2-of-3)` before the
   full phase-2 rollout — a cheap intermediate step worth considering. (Whoever it is,
   it must not be the marketplace operator — §5.3.)
3. ~~Tron observer timing~~ **(resolved).** The phase-1 centralized oracle observes both
   Ethereum and Tron from launch — the observers are self-built either way (no Rosen
   dependency), and Tron is USDT's biggest rail. When Rosen eventually adds Tron, the
   phase-2 fork can adopt its machinery; until then the Tron observer remains ours.
4. **Phase-2 governance of k/n and rotation.** A timelocked governance box is the
   Ergo-native answer; the governance token/multisig design is out of scope here.
5. **Guard collateral mechanics for phase 2.** Rosen's RSN/RWT permit economy is proven
   but heavy (and its slashing uses a trusted cleanup service); a leaner guard-bond box
   is likely sufficient at our deal sizes. Unresolved.

## 9. Sources

Rosen bridge (all github.com/rosen-bridge, MIT-licensed):

- `contract` repo — `GuardSign.es`, `Lock.es` (guard threshold pattern), `Commitment.es`,
  `EventTrigger.es`, `Fraud.es` (watcher commit-reveal and slashing)
- `guard-service` — `services/guard-service/src/agreement/txAgreement.ts` (guard tx
  agreement), `config/default.yaml` (per-chain confirmation depths)
- `watcher` — `src/ergo/boxes.ts` (event trigger serialization)
- `rcs` — rcs-003, Bridge Expansion Kit (chain-support requirements)
- `docs` — `concept-and-assumptions.md`, `rsn-token.md`, `r-and-d/bringing-monero.md`

## 10. Cross-references

- Vault contract, timing constants, register map: `specs/vault-contract.md`
- Deal protocol (state machine, handoff record, wire formats): `specs/deal-protocol.md`
- USDT leg design & trust model: `onramp-insurance.md` §3.1, §4–§5
- XMR/BTC leg context for §7: `onramp-insurance.md` §3.2–§3.3
- Operator infrastructure status & auto-pause UX: `onramp-ux.md` §4
- Fee model, watcher compensation, collusion economics: `onramp-business-model.md` §2–§3
- Vision context: `pillars.md` pillar 3
