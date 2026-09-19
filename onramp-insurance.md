# Vault-Insured Cash→USDT On-ramp with Ergo Contracts

*Design note — USDT, BTC, and XMR legs. Direction: **cash→USDT on-ramp only** — the buyer hands cash
to the seller at an in-person meeting and receives USDT; the seller collects the cash and
sends the USDT afterwards, so
it acts last and locks the vault collateral. BTC and XMR legs remain extension notes (§3.2/§3.3;
`specs/vault-contract.md` §8.1/§8.2). Synthesizes kushti's forum post "Insuring Bitcoin on-ramp with
Ergo contracts" (ergoforum.org/t/5121, March 2025), the r/ergonauts "DarkPaper Recipe #1" (June
2026), and the Monero verification analysis below. Related to pillar 3 in `pillars.md`.*

## 1. Problem

Cash→crypto OTC deals have a sequencing problem: one side must act first, and the first mover is
exposed. In the on-ramp this product implements, the **cash side acts first**: the buyer hands cash
to the seller (they meet in person) and then waits for the seller's crypto transfer — minutes on
Tron/Ethereum for USDT, but 10–60+ minutes for Bitcoin, where an unconfirmed mempool transaction
can be replaced via RBF, so without insurance the buyer cannot safely leave. (The reverse
direction — crypto side acting first, the off-ramp — is out of scope and not designed here.)

Reputation systems (ratings, vetted channels) are the current mitigation and they cap the market:
they exclude casual sellers, leak identity, and fail for first-time counterparties.

The shared fix: **the side that delivers last locks collateral in an Ergo vault, under an
ErgoScript contract that pays the collateral to whichever side can present cryptographic proof of
the deal's outcome.** On-ramp, the seller delivers last (USDT after cash), so the seller locks
the collateral. No reputation needed; the vault substitutes for it.

## 2. Common vault skeleton

All three asset legs use the same contract shape. A *seller* (anyone, no reputation required) locks
collateral in a vault box governed by a script with multiple spending paths:

| Path | Condition | Effect |
|---|---|---|
| Timeout | `RECLAIM_TIMEOUT` (24h) passes with no claim | Seller reclaims collateral (buyer no-show) |
| Cash-collection proof | The **seller-signed handoff record**: a Schnorr signature from `sellerPubKey` (the same R5 key that reclaims the collateral) over the cash-received message | Buyer can claim the collateral (after `CLAIM_MATURATION`, 12h) |
| Payment proof | Oracle digest of the **seller's** USDT transfer to the buyer (recipient pinned at funding) — the attestation alone | Collateral released to the seller immediately |

The proof roles flip cleanly with the direction. The on-ramp has no payment for an oracle to attest
at claim time (cash has no oracle — never claim a trustless cash proof), so the **claim** is gated
on the seller-signed handoff record collected physically at the meeting, while the **release** is
gated on the oracle digest of the seller's USDT transfer — and on nothing else: the attestation
alone moves the collateral. The seller's own signature never releases the vault (self-attestation
protects nobody), but the phase-1 oracle's attestation does — that trust is stated plainly in §3.1
and §5, not dressed up.

One fixed, non-transaction Schnorr message carries the one off-chain event (Schnorr signatures are
verifiable in ErgoScript — ergoforum.org/t/verifying-schnorr-signatures-in-ergoscript/3407):

- The **handoff record**, signed by the seller at the meeting, proves *cash
  collection*. The sequencing matters: the seller signs **only after physically counting the
  cash** — signing before collection would hand the buyer a false artifact (which would
  only ever unlock a payout of the seller's own collateral to the buyer). So the buyer hands
  over the cash, watches the seller count and sign, and **must not leave the meeting until
  their app shows the verified seller-signed record**; the record is the buyer's only
  dispute artifact. It gates the buyer's claim. Stated plainly, because the artifact is
  seller-signed under the same key that reclaims the collateral: (a) the claim pays the
  *buyer*, so a seller faking or coercing a record is self-defeating — no new attack; and
  (b) the real residual is a seller who pockets the cash and refuses to sign, leaving the
  buyer with no on-chain artifact at all — the mitigation is procedural (don't hand over
  cash before the verified record is persisted in the app), the same exposure as any
  face-to-face cash trade, not a cryptographic one.
- The release needs **no buyer message at all**: the oracle's attestation of the seller's USDT
  transfer is solely sufficient on the release paths. There is no receipt signature anywhere in
  the protocol.

Privacy: the seller funds and reclaims vaults through mixer output / stealth addresses, so in
routine deals (where the buyer has no incentive to dispute) the reclaim path leaves no link between
the seller's vaults — the buyer stays pseudonymous, and the seller's vaults sit in a ring of all
sellers' vaults.

**Which asset is collateral depends on the deal leg:**

- Cash→USDT deals → collateral is **USE** (Ergo stablecoin; matches the value being traded, no
  extra price risk). The insured value equals the USDT amount of the deal.
- Cash→BTC deals → collateral is **rsBTC** (Rosen-wrapped BTC; exists today).
- Cash→XMR deals → collateral would be **rsXMR** — **hypothetical: Rosen does not support Monero
  today** (see §3.3).

The contested event differs per leg, and that — not the vault — is where the three designs diverge.

## 3. Per-asset designs

### 3.1 USDT (Tron / Ethereum) — oracle-verified

On-ramp shape (buyer hands cash, seller sends USDT afterwards):

1. Buyer and seller agree terms (quote). No reputation vetting. The buyer shares a USDT receive
   address, pinned in the vault at funding (R9 `recipientAddr` — the seller pays the buyer), and
   the deal terms pin the two deal keys: `buyerPubKey` and `sellerPubKey`
   (`specs/deal-protocol.md` §3.1).
2. Seller does its standard AML check — off-chain, seller-side, regardless of everything else;
   no contract can fix it. *(This step is off-chain reputation by design.)*
3. Seller locks USE equal to the USDT amount in the vault (it acts last, so it locks the
   collateral).
4. At the meeting, the buyer hands over the cash and watches the seller count it; the seller
   signs the handoff record only after the count; the buyer does not leave until their app shows
   the verified seller-signed record, checked against the deal terms.
5. Seller sends the USDT to the buyer's pinned address; the phase-1 oracle observes the transfer
   and **screens it as non-tainted before attesting** (Tether blacklist/freeze exposure on Tron,
   sanctions screening on Ethereum — a heuristic: Tether can freeze after attestation, so
   screening at attestation time is not a guarantee; `specs/oracle-integration.md` §4).
6. Resolution:
   - **Routine:** the oracle attests the transfer and the seller releases the vault with the
     oracle digest alone — immediate payout (path C). No buyer action is required at any point
     after the meeting; the release follows the attestation.
   - **Seller collected the cash but never paid:** the buyer opens the claim with the
     seller-signed handoff record (path B); after `CLAIM_MATURATION` (12h) the buyer claims the
     USE (path D). There is no payment for an oracle to attest at claim time, so the claim is
     gated on the handoff record, not the oracle.
   - **Buyer ghosts after the USDT arrives:** nothing depends on the buyer anymore — the release
     follows the oracle's attestation without any buyer action, so there is nothing to withhold.
     (If the oracle never attests a transfer the seller claims to have sent, that is an
     operational incident — seller fault, dispute evidence — not a protocol path.)

**Trust model:** the payment-proof path trusts the oracle — *completely*. In practice the phase-1
deployment is a single trusted centralized oracle authenticated on-chain by NFT, upgraded
post-launch to a Rosen-derived guard threshold with a byte-identical payment-proof format (two
phases: `specs/oracle-integration.md`). Say "trusted" for phase 1 and mean it: the attestation
alone releases the vault, so a compromised or malicious oracle can attest a payment that never
happened and take the collateral — there is no on-chain defense, and that is accepted at launch.
What remains is operational: oracle operator ≠ marketplace operator, publicly auditable
attestations (a false attestation is ex-post provable against public source-chain data, and each
one is bounded by the per-deal size cap while the oracle is centralized), and the phase-2
threshold upgrade. The claim path, by contrast, involves no oracle at all — and its artifact
is now seller-signed under the same R5 key that reclaims the collateral. Consequences,
stated plainly: a seller opening a claim on its own vault is self-defeating (the claim pays
the *buyer* the seller's own collateral), so no collusion pair is needed for that analysis;
and the real residual is a seller who takes the cash and refuses to sign, which leaves the
buyer with no on-chain dispute artifact — the buyer's protection is procedural (don't hand
over cash without the in-app verified record), the same exposure as any face-to-face cash
trade. Note the oracle only
needs to *confirm an event on a transparent chain*. Also verified against rosen-bridge
code: the live watcher/guard set cannot attest deal-scoped events, and Rosen has no Tron support —
§3.2 of that spec.

**Implementation status (2026-09-17):** the `.es` contracts in `contracts/` implement the
v2 design (2026-09-13): R7 holds the bare 32-byte
`oracleNftId` (the old two-key packing is gone), claim path B is gated on the
seller-signed handoff record with no oracle input, and the release paths C/C′ take the
oracle's attestation box as a **data input** — the attestation alone, no oracle signature
in the release tx, no receipt signature anywhere. The tested suite is the on-ramp
matrix in `specs/vault-contract.md` §7 (44 tests + 8 oracle-box tests, green — the
2026-09-18 fee removal deleted the old fee tests, 35a–35e);
`specs/deal-protocol.md` is canonical for roles and wire formats (its state machine and wire
formats are implemented and tested in `apps/core/dealprotocol/`). On top of that: the chain layer
landed 2026-09-16 (M2, extended M3-A) in `apps/core/ergo/` — explorer-backed `ChainSource`,
`VaultBoxTracker`, the claim txs (`ClaimTxBuilder`), the operator-side txs
(`OperatorTxBuilder`: fund/reclaim/release/contest), the 112-byte `PaymentAttestation`, and the
`DevOracle` attestation-box seam; the Ktor operator backend landed 2026-09-17 (M3-B, `backend/`);
the 2026-09-17 data-input rework removed the `OracleSigner` co-signing seam — release txs
now reference the oracle box as a data input, with the attestation serialized one in
flight (a release must confirm before the next posting). An end-to-end gate (`e2e/`, M3-C)
drives release-via-attestation, dispute, and timeout-reclaim
against the live chain (mainnet by default since 2026-09-17, `--dry-run` no-broadcast mode).
The deployed phase-1 oracle *service* (Tron/Ethereum observers + HTTP attestation API) is still
future work — until it exists, "oracle confirmed" in the components is an assertion fed to the dev
oracle, not an observation of a source chain (`specs/oracle-integration.md` §4).

### 3.2 BTC (rsBTC) — trustless via Bitcoin relay

Extension note (the shipped phase-1 scope is the USDT leg only; `specs/vault-contract.md` §8.1).
On-ramp shape (cash first) from kushti's forum post:

1. BTC seller locks rsBTC in the vault *before* the meeting.
2. At the deal, the seller signs a message containing the BTC amount, the Bitcoin transaction id,
   and a timestamp.
3. Submitting that message (verified Schnorr signature, fresh — e.g. ≤4h old) creates the insurance
   box with two spending paths:
   - **after a deadline (`BTC_DEADLINE`, ~6h)** the buyer (who handed over cash) can withdraw the
     rsBTC;
   - **the seller can cancel** the withdrawal by presenting a *proof of inclusion* in the Bitcoin
     blockchain of a transaction with the id from the signed message.

The inclusion proof comes from a **trustless Bitcoin relay on Ergo** (research implementation:
github.com/ross-weir/ergohack-sidechain, with `BtcTxCheck.es` as the tx-check contract; relay
design discussed at ergoforum.org/t/trustless-bitcoin-relay-on-ergo/4798). Amounts on Bitcoin are
public, so the relay proof establishes *that the promised tx paid the promised amount* with no
trusted party.

**Dispute UX:** the buyer can start a dispute immediately (1 Ergo transaction), or — if the vault
is large and there's some operational trust — wait it out with zero on-chain transactions. No
trusted party is involved in dispute resolution.

The ~6h deadline is sized to Bitcoin's tail latency: 10-minute average blocks, 1–2 hour waits,
worse under congestion, plus RBF exposure while unconfirmed.

**Trust model:** trustless under the relay's security assumptions (relay soundness + enough
confirmation depth). This is the strongest of the three designs — no oracle in the loop.

### 3.3 XMR (rsXMR) — oracle-verified via tx-key reveal *(proposed)*

Extension note / design placeholder (`specs/vault-contract.md` §8.2), not a roadmap item. On-ramp
roles: after collecting the cash, the **seller** is the XMR sender.

Monero's privacy breaks the BTC approach in a specific way: everything needed for verification —
amounts, destinations — is encrypted, and Monero's math (Keccak hash-to-scalar, MLSAG ring
signatures) cannot be checked in ErgoScript, which has only `blake2b256` and `sha256` opcodes (no
Keccak/SHA-3). So a BTC-style trustless relay proof is **not available today**, and rsXMR itself
does not exist yet (Rosen supports Ergo, Cardano, Bitcoin incl. Runes, Ethereum, BSC, Doge, Firo,
Handshake, Base as of 2026-09 per rosen-bridge code — notably not Tron, Nervos, or Monero).

What Monero *does* provide is a clean selective-disclosure primitive
(getmonero.org/resources/user-guides/prove-payment.html):

- Revealing the **per-transaction secret key** (`get_tx_key`) lets anyone verify that a specific
  on-chain tx paid a specific address a specific amount.
- `get_tx_proof` / `check_tx_proof` wraps this as a portable, signed artifact — publishable as data
  on Ergo.
- This is strictly better than revealing a **private view key**, which exposes the address's
  *entire* receive history. Per-deal, use the tx key.

Proposed flow:

1. The seller (XMR sender) creates a **dedicated one-time deal address** — do not co-mingle; the
   tx-key reveal exposes the full structure of that one tx.
2. After the handoff, the seller sends the XMR; both sides wait for a few confirmations (2-minute
   blocks, no RBF — Monero is friendlier than BTC on both counts; double-spend attempts die at the
   key-image check).
3. The sender publishes the `get_tx_proof` artifact + deal message (amount, timestamp, deal id)
   into the vault box.
4. **Oracle watchers re-run `check_tx_key` verification locally** against their own Monero daemon
   (all daemon RPC, automatable) and co-sign the payment-proof path. The ErgoScript contract itself
   does not verify the Monero math — it verifies the oracle's threshold signature, exactly as in
   the USDT leg.

Sender-side operational requirement: wallets must run with `store-tx-info 1`, or tx keys are lost
and payment becomes unprovable. Proof generation must be built into the deal software, not a manual
CLI step.

**Trust model:** oracle multisig trust, same as USDT. The oracle is mandatory here, not a fallback.
If ErgoTree ever gains Keccak-256 — or someone produces a ZK circuit for the CryptoNote
verification — the XMR leg could be upgraded to the trustless model; that is a research project,
not a roadmap item.

## 4. Trust model comparison

| | USDT leg | BTC leg | XMR leg |
|---|---|---|---|
| Payment verification (gates release) | Oracle attestation — **solely sufficient** (phase 1: centralized NFT oracle, trusted; phase 2: Rosen-derived guard set) — confirms the seller's USDT transfer | Trustless Bitcoin relay (inclusion proof) | Monero tx-key reveal, verified by oracle |
| Trusted parties for crypto leg | Phase-1 centralized oracle (solely trusted on release) → Rosen-style guard multisig | None (relay assumptions only) | Oracle multisig, same as USDT |
| Cash collection proof (gates claim) | Seller-signed handoff record (the R5 seller key — the same key that reclaims the collateral) | Buyer's Schnorr signature at handoff (the seller's signed message carries the promised txid) | Seller-signed handoff record (same skeleton as USDT) |
| Collateral asset | USE | rsBTC (exists) | rsXMR (does not exist yet) |
| Chain latency risk | Low (Tron/Ethereum confirmations) | High (RBF, 10-min blocks) → `BTC_DEADLINE` ~6h | Low (2-min blocks, no RBF) |
| Privacy of crypto leg | None (public chain) | Pseudonymous (transparent amounts) | Strong (revealed per-tx only) |

Residual trust in **all** legs: the AML/vetting step is off-chain; the cash leg is a
face-to-face handover whose dispute artifact is the seller's own signature on the handoff
record (the same key that reclaims the collateral — a cheating seller gains nothing from a
fake record, since the claim it unlocks pays the buyer, and a seller who refuses to sign
after taking cash leaves the buyer with the same exposure as any face-to-face cash trade);
and
vault collateral is only as good as the collateral asset's liquidity (rsBTC/rsXMR/USE depth on
Ergo DEXs). For the oracle-verified legs add the phase-1 oracle itself, trusted outright on the
release path (§3.1).

## 5. Limitations and open problems

- **Oracle trust for USDT and XMR — total on the release path.** The darkpaper recipe's
  "eliminating trust" is really "concentrating trust into the oracle, which you already trust to
  bridge." Defensible, but say it that way — and for phase 1 say "trusted": the centralized
  oracle's attestation alone releases the vault, so a compromised oracle can attest a fake
  payment and steal the collateral, and nothing on-chain prevents it. That is accepted at
  launch. The mitigations are operational: oracle operator ≠ marketplace operator, publicly
  auditable attestations (oracle fraud is ex-post provable against source-chain data and bounded
  per deal), and deal-size caps while the oracle is centralized. Do not use "cost-to-attack"
  language for phase 1 — there is no threshold to attack; that framing returns only with the
  phase-2 guard set.
- **No Keccak-256 in ErgoTree** — blocks trustless XMR verification and any Ethereum-log
  verification that needs Keccak in-script.
- **rsXMR does not exist.** Wrapping XMR has its own problem: Rosen watchers cannot verify XMR
  events trustlessly, so a wrapped XMR would be born with the oracle trust assumption baked in.
- **Collateral liquidity.** The vault is only insurance if the collateral can actually be sold in a
  dispute. rsBTC/USE depth on Spectrum is thin today; the scheme's ceiling is set by DEX liquidity,
  not by contract correctness.
- **Scales with deal size only via over-collateralization.** A seller must lock 1 USE per 1 USDT
  at risk (100% ratio), so capital efficiency is poor by design — that's the price of "no
  reputation."
- **Regulatory exposure.** The in-person cash-collection leg is the legally sensitive part in most
  jurisdictions; the contracts make the crypto side trust-minimized, they do not launder the fiat
  leg.

## 6. Sources

- kushti, "Insuring Bitcoin on-ramp with Ergo contracts", ergoforum.org/t/5121 (2025-03-30)
- u/ErgoRich, "Ergo DarkPaper Recipe #1: Eliminating Trust in the Biggest Crypto Market (Crypto-2-Cash)", r/ergonauts (2026-06-29) — design origin of the USDT leg; its market-size claims were *not* carried over here (they fail arithmetic against ERG's actual market cap)
- ross-weir, ergohack-sidechain (Bitcoin relay contracts incl. `BtcTxCheck.es`), github.com/ross-weir/ergohack-sidechain
- "Verifying Schnorr signatures in ErgoScript", ergoforum.org/t/3407
- "Trustless Bitcoin relay on Ergo", ergoforum.org/t/4798
- getmonero.org, "How to prove a payment was made" (`get_tx_key` / `check_tx_key` / `get_tx_proof`)
- ErgoTree opcode spec (sigmastate-interpreter): hash opcodes are `blake2b256` and `sha256` only
- rosen-bridge GitHub org — `contract` repo (`GuardSign.es`, `Lock.es`, `Commitment.es`, `Fraud.es`), `guard-service`, `watcher`, `rcs-003` (Bridge Expansion Kit): github.com/rosen-bridge

## 7. Cross-references

- Implementation specs: `specs/README.md` (vault contract, deal protocol, oracle, apps, backend)
