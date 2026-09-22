# Deal Protocol Specification — Off-Chain State Machine & Wire Formats

*Protocol spec for the vault-insured cash→USDT on-ramp in `onramp-insurance.md`. This file is the
**single source of truth** for the deal lifecycle and message formats that every component
implements: the vault contract (`specs/vault-contract.md`), the oracle
(`specs/oracle-integration.md`), the buyer app (`specs/android-app.md`), and the operator
backend (`specs/operator-backend.md`). Timing and
fee constants are owned by `specs/vault-contract.md` §2 and referenced by name here.
Direction: **cash→USDT on-ramp only** — the buyer hands cash to the seller and receives
USDT; the seller holds the USDT, physically collects the cash, and locks the vault
collateral (it acts last). The reverse direction is out of scope and not specified.*

*v2 (owner decision, 2026-09-13): the buyer's receipt signature is gone from the protocol
entirely — release paths C/C′ are gated on the oracle attestation **alone** (the phase-1
oracle is trusted, period), and the handoff record is single-signed by the seller under
the deal's seller key (verified in path B against the vault's R5). The two-role design
stands: the seller meets the buyer, collects the cash, and signs.*

## 1. Deal state machine

Canonical states. Every component uses these names verbatim.

```
                ┌──────── buyer ghosts / seller declines / quote expires ───────┐
                ▼                                            │
 QUOTED ──▶ FUNDED ──▶ PAYMENT_PENDING ──▶ PAYMENT_CONFIRMED ──▶ RELEASED
              │                │                             ▲
              │                │ (buyer opens claim: cash     │ path C′ — the seller
              │                │  collected, seller never    │ counters with the oracle's
              │                │  paid)                      │ attestation alone; an oracle
              │                ▼                             │ signal landing during
              │ (RECLAIM_   CLAIM_OPENED ──▶ CLAIMABLE ──▶ CLAIMED
              │  TIMEOUT)        └──▶ RELEASED (contested) ──┘  kills the claim
              ▼
 (no-show) RECLAIMED
              ▲
              │ (RECLAIM_TIMEOUT, seller already paid, buyer ghosted)
              └────────────────────── PAYMENT_CONFIRMED
```

| State | Meaning | Who moves it | On-chain footprint |
|---|---|---|---|
| QUOTED | **offer sent** — terms proposed, no vault yet; awaiting seller accept | Buyer offers; seller accepts by funding | none |
| FUNDED | vault box created, collateral locked, waiting for the meeting | operator backend | FUNDED box |
| PAYMENT_PENDING | cash collected (seller-signed handoff record exists); seller is obligated to send USDT | seller signs the handoff record at the meeting | FUNDED box |
| PAYMENT_CONFIRMED | oracle observed the seller's USDT transfer to the buyer | oracle attestation (off-chain signal) | FUNDED box (still) |
| RELEASED | payment-proof path spent; collateral to seller in full | seller submits tx (path C/C′, oracle's attestation alone) | box spent (path C/C′) |
| RECLAIMED | timeout path spent; collateral back to seller in full | backend reclaim job | box spent (path A) |
| CLAIM_OPENED | seller-signed handoff record on-chain; box is PAYMENT_PROVEN | Buyer app (path B tx) | PAYMENT_PROVEN box |
| CLAIMABLE | `CLAIM_MATURATION` elapsed since `proofHeight` | automatic (height) | PAYMENT_PROVEN box |
| CLAIMED | Buyer took collateral in full | Buyer app (path D tx) | box spent (path D) |

Rules:

- **Order is fixed by who acts first.** The buyer hands cash before the crypto leg:
  FUNDED → PAYMENT_PENDING requires the seller-signed handoff record; the seller's USDT
  transfer and the oracle observation come after.
- **PAYMENT_CONFIRMED does not touch the chain** (routine deals leave one funding tx and
  one release/reclaim tx). The oracle's attestation (the bare `dealId`, §3.4) only goes
  on-chain when the seller releases
  (path C/C′) — it is the seller's *proof of performance*.
- **Claim gating flipped vs. a naive mirror:** a claim asserts "I handed over cash and the
  seller never paid". There is no payment for an oracle to attest at that point, so the
  claim is gated on the **seller-signed handoff record** (one Schnorr under the deal's
  seller key over the cash-received message), verified in path B against the vault's R5.
  The oracle instead gates *release*: path C/C′ present the oracle's attestation — the
  bare `dealId` signal that the seller's USDT transfer to the buyer is done and screened
  (the buyer's receive address is registered off-chain at funding, §3.3/§3.4 — it is no
  longer pinned on-chain). `PAYMENT_CONFIRMED`
  still allows a claim (the "claims without cause" case — the seller counters during
  maturation by presenting the attestation, which proves performance).
- **RELEASED** is reachable from PAYMENT_CONFIRMED (routine: path C, the attestation
  alone)
  and from CLAIM_OPENED/CLAIMABLE (path C′ — the seller counters a claim by presenting
  the same attestation). The seller's contest is **oracle-signal-only**: if the oracle signal
  lands while a claim is open, the claim is without cause and dead — the state machine
  records it as contested and awaits the seller's C′ counter-spend rather than leaving
  a zombie claim. (The contract itself never sees the off-chain signal, so a matured
  path D spend remains possible on-chain; the seller is expected to win the race with C′.)
- **RECLAIMED is reachable only from FUNDED and PAYMENT_CONFIRMED**, always after
  `RECLAIM_TIMEOUT`. From FUNDED = buyer no-show, nothing happened. From PAYMENT_CONFIRMED =
  the seller already paid (the attestation exists) and the buyer ghosted — the buyer holds the USDT,
  so the seller reclaiming its own collateral harms no one. Reclaim is rejected from
  PAYMENT_PENDING: cash has changed hands and no payment proof exists, so reclaim there
  is a theft path — the buyer's answer is the claim.
- Terminal states: RELEASED, RECLAIMED, CLAIMED.
- The buyer app's vertical timeline (`onramp-ux.md` §2.2) renders FUNDED → PAYMENT_PENDING →
  PAYMENT_CONFIRMED → RELEASED top-to-bottom.
- **Trust model, stated plainly:** the release direction fully trusts the phase-1 oracle.
  If the oracle is compromised or misattests, there is no on-chain defense — that is
  accepted for phase 1 (phase 2 replaces the single oracle with a k-of-n guard set,
  `specs/oracle-integration.md` §3.2). The claim direction never depends on the oracle:
  the seller-signed handoff record alone opens it. And because the record is signed
  under the same R5 seller key that reclaims collateral, a seller opening its own claim
  is self-defeating — the residual is a seller who takes the cash and refuses to sign,
  which leaves the buyer with no on-chain artifact; the buyer's protection is procedural
  (don't hand over cash without the in-app verified record), the same exposure as any
  face-to-face cash trade (see `onramp-insurance.md` §2).

## 2. Identities and keys

- **Deal key** (buyer): a fresh secp256k1 keypair per deal, generated in the app (Android
  Keystore-backed) or derived from the buyer's existing Ergo wallet. It signs nothing on
  the protocol paths in v2 — it identifies the buyer in the deal terms (vault R6) and
  receives the claim payout (path D pays the buyer's deal-key address). It never custodies
  funds. Discardable after RELEASED/RECLAIMED; required until then for the dispute path.
- **Seller key** (the operator-side vault key): appears in the deal terms and the vault's
  R5. It funds, reclaims, and receives releases, and — same key — it signs the handoff
  record at the meeting (the cash-received acknowledgment verified in path B). It is
  long-lived but privacy-managed: funding and reclaim flows route through mixer/stealth
  addresses (`onramp-insurance.md` §2), implemented by the backend
  (`specs/operator-backend.md` §collateral management). Note what the single-key shape
  means for the claim path: the dispute artifact is seller-signed under the same key that
  reclaims the collateral — self-defeating for a cheating seller (the claim pays the
  *buyer*), with the residual that a seller who takes cash and refuses to sign leaves the
  buyer no on-chain artifact (`onramp-insurance.md` §2).
- **Oracle**: phase 1 — a single trusted oracle, authenticated on-chain by an NFT
  pinned in the vault's R7; it attests the seller's USDT transfer to the buyer by
  posting the bare 32-byte `dealId` in its singleton's R4, and that attestation alone
  moves collateral on the release paths (`specs/oracle-integration.md`
  §3.1). Phase 2 — a k-of-n guard set via a GuardSign-style box: same register, same
  32-byte `dealId` payload (threshold-signed), only the vault's authentication check changes
  (`specs/oracle-integration.md` §3.2).

Recovery: a deal link carries a random token; the deal key is additionally recoverable from
a seed phrase shown once at deal creation (`onramp-ux.md` §6, "app dies mid-deal").

## 3. Wire formats

All multi-byte integers big-endian. All hashes `blake2b256` (32 bytes). Byte layouts here
are the canonical ones; implementations must not deviate — the contract checks hashes of
these exact serializations.

### 3.1 Deal terms (off-chain, agreed at QUOTED)

```
version       1 byte    = 0x01
dealNonce     16 bytes  random, generated by the operator at quote acceptance
asset         1 byte    0x01 = USDT (0x02 BTC, 0x03 XMR reserved)
srcChainId    1 byte    0x01 = Tron, 0x02 = Ethereum
amount        8 bytes   uint64, USDT amount in smallest source-chain unit (10^-6)
fiatAmount    8 bytes   uint64, smallest fiat unit (cash the buyer hands over)
fiatCurrency  3 bytes   ISO-4217 (e.g. "EGP")
buyerPubKey   33 bytes  compressed secp256k1 — the buyer's deal key
sellerPubKey  33 bytes  compressed secp256k1 — the seller's key (R5; also verifies the
                        handoff record in path B)
quoteExpiry   4 bytes   uint32 unix seconds
```

`dealId = blake2b256(deal terms serialization)` — 32 bytes. It binds amount, asset, chain,
and both keys; the vault's R4 stores it and every proof references it, so no proof is
replayable across deals (vault-contract test 36). The `dealId` is also the entire
on-chain oracle attestation (§3.4).

**Register note:** the oracle NFT id is pinned on-chain in R7 as the bare 32-byte
`oracleNftId` (the 65-byte packing of oracle NFT id plus a second deal-scoped key, used
by earlier revisions, is gone — path B verifies the handoff record against R5's
`sellerPubKey` instead). Because `sellerPubKey` is part of the deal terms, `dealId` binds
the handoff-record signer: a record from a different seller opens nothing
(vault-contract tests 5, 42).

The phase-1 oracle observes **both Tron (`0x01`) and Ethereum (`0x02`)** from launch
(`specs/oracle-integration.md` §3.2 — self-built observers; Tron is USDT's biggest rail).

### 3.2 Handoff record (single-signed: seller key)

The cash-received acknowledgment, created at the meeting, before the seller's USDT leg
starts. The seller's app displays: *"I collected 15,600 EGP from the buyer for deal
#A3F9"* — the signed bytes are:

```
magic         4 bytes   "P2PH"
version       1 byte    = 0x01
dealId        32 bytes
fiatAmount    8 bytes   fiat amount, whole basic units (no decimals) — matches deal terms
fiatCurrency  3 bytes
timestamp     4 bytes   uint32 unix seconds
```

52 bytes total; the timestamp sits at bytes 48..52 (path B's freshness check slices
exactly those bytes). One Schnorr signature over the record under `sellerPubKey`,
verified in-script by vault path B against the vault's R5 (ergoforum.org/t/3407 variant,
`specs/vault-contract.md` §5); the timestamp freshness bound is enforced in-script by
path B against the claim tx timestamp. Signatures are never part of the message bytes.
**Sequencing rule:** the seller signs only after physically counting the cash; cash
changes hands as the signature lands (the buyer app must have persisted the signed
record before releasing the cash). The buyer obtains the signed record at the meeting as
the dispute artifact — a record missing the seller's signature is worthless, so:

- a seller who collects cash but never signs leaves the buyer with no on-chain dispute
  artifact — the buyer's protection is procedural: never hand over cash before the
  verified record is persisted *in their app*; this is the same exposure as any
  face-to-face cash trade, not a protocol case;
- a seller signature without collected cash unlocks nothing the seller side could want:
  the claim it would open pays the *buyer* the seller's own collateral (§2).

The handoff record is the on-ramp's replacement for the off-ramp's oracle-gated claim: it
gates path B and anchors maturation.

### 3.3 QR payloads

- **Seller → buyer (handoff):** `p2pgate://handoff?m=<base64url(handoff record)>`.
  The seller renders the unsigned record; the buyer app validates amount/currency against
  the deal terms, the seller signs, the record is complete.
- **Buyer → seller (payout address):** the buyer's USDT address (Tron base58 string in
  phase 1 — deals are Tron-only, `srcChainId = 0x01`; the EIP-55/Ethereum encoding stays
  defined in `specs/oracle-integration.md`) + expected amount, shared at QUOTED and
  registered with the operator/oracle watch set **off-chain** at funding (since
  2026-09-21 it is no longer pinned on-chain — the R9 `recipientAddr` binding is
  removed, §3.4).
  Exact per-chain URI formats are an implementation detail of `specs/android-app.md`.

### 3.4 Oracle attestation

The attestation **is the bare 32-byte `dealId`** of §3.1, posted in the oracle
singleton's R4 — a per-deal signal meaning "this deal's USDT transfer seller→buyer is
done and the funds screened non-tainted" (owned by `specs/oracle-integration.md` §2.2;
the 112-byte field payload that preceded it was retired pre-launch, 2026-09-21). The
binding rule is a single equality: the release path checks the attestation box's R4
against the vault's R4 `dealId` — there are no field checks anymore, and the R9
funding binding (`srcChainId | tokenId | recipientAddr | expectedAmount`) that backed
them is removed from the vault. What the old binding pinned on-chain is now
**wholly the oracle's off-chain assertion**: the buyer's USDT receive address and the
exact amount are registered with the operator/oracle watch set at funding (off-chain,
§3.3), and the oracle's observer matches the exact `(recipient, amount)` transfer
before it will post the dealId (`specs/oracle-integration.md` §4.1) — the contract
cannot verify any of that, and this spec does not pretend otherwise.
In phase 1 the attestation is realized as the oracle box being a **data
input** to the **release** transaction (the oracle singleton carries the NFT and the
`dealId` in R4; the vault checks the NFT against R7 and the R4 dealId against its own —
data-input scripts never execute, so no oracle signature is in the tx); in phase 2 as a
k-of-n guard signature bundle over the same 32-byte `dealId`. The attestation is **solely
sufficient** on the release paths — no buyer or seller signature accompanies it.

## 4. Failure and edge transitions

Mapping of `onramp-ux.md` §6 onto the state machine:

| Situation | Transition | Result |
|---|---|---|
| Buyer never shows up (no-show) | FUNDED → (RECLAIM_TIMEOUT) → RECLAIMED | silent close; collateral back to seller |
| Cash collected, seller never sends USDT | PAYMENT_PENDING → buyer hits dispute → CLAIM_OPENED | claim timeline shown (~`CLAIM_MATURATION`) |
| Seller sends partial USDT | blocked operationally ("send exactly N USDT in one tx"); if it happens, the oracle never attests it — its observer matches the exact `(recipient, amount)` registered at funding, so a partial/short payment simply stays unconfirmed → no release; claim path unaffected | flagged in dashboard dispute inbox |
| Buyer claims without cause | CLAIM_OPENED → oracle signal lands → claim contested → seller presents the oracle's attestation alone → RELEASED (path C′) | dashboard shows "evidence attached" |
| Buyer ghosts after USDT arrives | PAYMENT_CONFIRMED → (RECLAIM_TIMEOUT) → RECLAIMED | Buyer keeps USDT; seller recovers collateral |
| Seller takes the cash and refuses to sign the record | Buyer app never released the cash (sequencing rule §3.2); if the buyer handed cash over anyway, they hold no artifact — procedurally the same exposure as any face-to-face cash trade | no on-chain case; off-chain escalation, not a contract case |
| App dies mid-deal | recovery by deal-link token or deal-key seed; state re-derived from chain + backend | no server-side account to lose |
| Oracle lag | quote publishing auto-pauses operator-side (`specs/operator-backend.md` §infra); in-flight claims are unaffected — the buyer's claim never depends on oracle liveness; releases and without-cause contests wait for the oracle | |

## 5. Privacy requirements (binding on all implementations)

- No accounts, no KYC in the apps; AML is seller-side and off-chain (decision recorded,
  report not stored — `onramp-ux.md` §4).
- Deal-scoped everything: keys (buyer deal key), link token. Nothing correlates deal X
  with deal Y except the operator's internal records.
- No phone numbers exchanged; meeting coordination via the deal link.
- Notifications opt-in, default off; Tor/no-notification mode available.

## 6. Cross-references

- Contract states and paths: `specs/vault-contract.md` §1–§4; revision items for this
  direction: `specs/vault-contract.md` §8.3
- Oracle attestation format: `specs/oracle-integration.md`
- UX flows this renders: `onramp-ux.md` §2, §6
- Design rationale: `onramp-insurance.md` §2–§3
