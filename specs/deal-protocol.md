# Deal Protocol Specification — Off-Chain State Machine & Wire Formats

*Protocol spec for the vault-insured cash→USDT on-ramp in `onramp-insurance.md`. This file is the
**single source of truth** for the deal lifecycle and message formats that every component
implements: the vault contract (`specs/vault-contract.md`), the oracle
(`specs/oracle-integration.md`), the user app (`specs/android-app.md`), the courier app
(`specs/courier-app.md`), and the operator backend (`specs/operator-backend.md`). Timing and
fee constants are owned by `specs/vault-contract.md` §2 and referenced by name here.
Direction: **cash→USDT on-ramp only** — the user hands cash to the courier and receives
USDT; the seller holds the USDT and locks the vault collateral (it acts last). The
reverse direction is out of scope and not specified.*

*v2 (owner decision, 2026-09-13): the buyer's receipt signature is gone from the protocol
entirely — release paths C/C′ are gated on the oracle attestation **alone** (the phase-1
oracle is trusted, period), and the handoff record is single-signed by the courier under
the deal-scoped courier key pinned in vault R7.*

## 1. Deal state machine

Canonical states. Every component uses these names verbatim.

```
                ┌──────── user ghosts / quote expires ───────┐
                ▼                                            │
 QUOTED ──▶ FUNDED ──▶ PAYMENT_PENDING ──▶ PAYMENT_CONFIRMED ──▶ RELEASED
              │                │                             ▲
              │                │ (user opens claim: cash     │ path C′ — the seller
              │                │  collected, seller never    │ counters with the oracle
              │                │  paid)                      │ digest alone; an oracle
              │                ▼                             │ signal landing during
              │ (RECLAIM_   CLAIM_OPENED ──▶ CLAIMABLE ──▶ CLAIMED
              │  TIMEOUT)        └──▶ RELEASED (contested) ──┘  kills the claim
              ▼
 (no-show) RECLAIMED
              ▲
              │ (RECLAIM_TIMEOUT, seller already paid, user ghosted)
              └────────────────────── PAYMENT_CONFIRMED
```

| State | Meaning | Who moves it | On-chain footprint |
|---|---|---|---|
| QUOTED | quote selected, terms agreed, no vault yet | user picks quote | none |
| FUNDED | vault box created, collateral locked, waiting for the meeting | operator backend | FUNDED box |
| PAYMENT_PENDING | cash collected (courier-signed handoff record exists); seller is obligated to send USDT | courier signs the handoff record | FUNDED box |
| PAYMENT_CONFIRMED | oracle observed the seller's USDT transfer to the user | oracle attestation (off-chain signal) | FUNDED box (still) |
| RELEASED | payment-proof path spent; collateral to seller minus fee | seller submits tx (path C/C′, oracle digest alone) | box spent (path C/C′) |
| RECLAIMED | timeout path spent; collateral back to seller minus fee | backend reclaim job | box spent (path A) |
| CLAIM_OPENED | courier-signed handoff record on-chain; box is PAYMENT_PROVEN | user app (path B tx) | PAYMENT_PROVEN box |
| CLAIMABLE | `CLAIM_MATURATION` elapsed since `proofHeight` | automatic (height) | PAYMENT_PROVEN box |
| CLAIMED | user took collateral minus fee | user app (path D tx) | box spent (path D) |

Rules:

- **Order is fixed by who acts first.** The user hands cash before the crypto leg:
  FUNDED → PAYMENT_PENDING requires the courier-signed handoff record; the seller's USDT
  transfer and the oracle observation come after.
- **PAYMENT_CONFIRMED does not touch the chain** (routine deals leave one funding tx and
  one release/reclaim tx). The oracle digest only goes on-chain when the seller releases
  (path C/C′) — it is the seller's *proof of performance*.
- **Claim gating flipped vs. a naive mirror:** a claim asserts "I handed over cash and the
  seller never paid". There is no payment for an oracle to attest at that point, so the
  claim is gated on the **courier-signed handoff record** (one Schnorr under the
  deal-scoped courier key over the cash-collection message), pinned via `courierPubKey`
  at funding. The oracle instead gates *release*: path C/C′ present the oracle digest of
  the seller's USDT transfer (recipient = the user's address, R9). `PAYMENT_CONFIRMED`
  still allows a claim (the "claims without cause" case — the seller counters during
  maturation by presenting the digest, which proves performance).
- **RELEASED** is reachable from PAYMENT_CONFIRMED (routine: path C, oracle digest alone)
  and from CLAIM_OPENED/CLAIMABLE (path C′ — the seller counters a claim by presenting
  the same digest). The seller's contest is **oracle-signal-only**: if the oracle signal
  lands while a claim is open, the claim is without cause and dead — the state machine
  records it as contested and awaits the seller's C′ counter-spend rather than leaving
  a zombie claim. (The contract itself never sees the off-chain signal, so a matured
  path D spend remains possible on-chain; the seller is expected to win the race with C′.)
- **RECLAIMED is reachable only from FUNDED and PAYMENT_CONFIRMED**, always after
  `RECLAIM_TIMEOUT`. From FUNDED = user no-show, nothing happened. From PAYMENT_CONFIRMED =
  the seller already paid (digest exists) and the user ghosted — the user holds the USDT,
  so the seller reclaiming its own collateral harms no one. Reclaim is rejected from
  PAYMENT_PENDING: cash has changed hands and no payment proof exists, so reclaim there
  is a theft path — the user's answer is the claim.
- Terminal states: RELEASED, RECLAIMED, CLAIMED.
- The user app's vertical timeline (`onramp-ux.md` §2.2) renders FUNDED → PAYMENT_PENDING →
  PAYMENT_CONFIRMED → RELEASED top-to-bottom.
- **Trust model, stated plainly:** the release direction fully trusts the phase-1 oracle.
  If the oracle is compromised or misattests, there is no on-chain defense — that is
  accepted for phase 1 (phase 2 replaces the single oracle with a k-of-n guard set,
  `specs/oracle-integration.md` §3.2). The claim direction never depends on the oracle:
  the courier-signed handoff record alone opens it.

## 2. Identities and keys

- **Deal key** (user): a fresh secp256k1 keypair per deal, generated in the app (Android
  Keystore-backed) or derived from the user's existing Ergo wallet. It signs nothing on
  the protocol paths in v2 — it identifies the user in the deal terms (vault R6) and
  receives the claim payout (path D pays the user's deal-key address). It never custodies
  funds. Discardable after RELEASED/RECLAIMED; required until then for the dispute path.
- **Courier key** (deal-scoped): the courier app holds a **deal-scoped signing key** issued
  by the operator backend — new responsibility versus the off-ramp, where the courier held
  no keys. It alone signs the cash-collection handoff record. Because the courier is
  seller-side, a colluding seller+courier gains nothing by faking a record: the claim
  it would unlock pays the *user* the seller's own collateral. A rogue courier signing
  records for cash never collected is an operational-security matter (GPS, dual-control),
  same trust as the cash leg itself.
- **Seller vault key**: the operator's key that funds, reclaims, and receives releases.
  Long-lived but privacy-managed: funding and reclaim flows route through mixer/stealth
  addresses (`onramp-insurance.md` §2), implemented by the backend
  (`specs/operator-backend.md` §collateral management).
- **Oracle**: phase 1 — a single trusted oracle, authenticated on-chain by an NFT
  pinned in the vault's R7; it attests the seller's USDT transfer to the user and its
  attestation alone moves collateral on the release paths (`specs/oracle-integration.md`
  §3.1). Phase 2 — a k-of-n guard set via a GuardSign-style box: same register, same
  digest format, only the vault's authentication check changes
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
fiatAmount    8 bytes   uint64, smallest fiat unit (cash the user hands over)
fiatCurrency  3 bytes   ISO-4217 (e.g. "EGP")
userPubKey    33 bytes  compressed secp256k1 — the user's deal key
sellerPubKey 33 bytes  compressed secp256k1
courierPubKey 33 bytes  compressed secp256k1 — the deal-scoped courier credential,
                        pinned at funding so path B can verify the handoff record
quoteExpiry   4 bytes   uint32 unix seconds
```

`dealId = blake2b256(deal terms serialization)` — 32 bytes. It binds amount, asset, chain,
and all keys; the vault's R4 stores it and every proof references it, so no proof is
replayable across deals (vault-contract test 22).

**Register note:** `courierPubKey` is pinned on-chain packed into R7 (`oracleNftId || courierPubKey`, 65 B — Ergo boxes have registers R4–R9 only, there is no R10). The `.es` contracts in `contracts/` implement this packing (§8.3 revision landed 2026-09-12); `courierPubKey` is part of the deal terms, so `dealId` was stable across the contract revision.

The phase-1 oracle observes **both Tron (`0x01`) and Ethereum (`0x02`)** from launch
(`specs/oracle-integration.md` §3.2 — self-built observers; Tron is USDT's biggest rail).

### 3.2 Handoff record (single-signed: courier key)

The cash-collection proof, created at the meeting, before the seller's USDT leg starts.
The courier app displays: *"I collected 15,600 EGP from the customer for deal #A3F9"* —
the signed bytes are:

```
magic         4 bytes   "P2PH"
version       1 byte    = 0x01
dealId        32 bytes
amount        8 bytes   fiat amount, smallest unit — matches deal terms
fiatCurrency  3 bytes
timestamp     4 bytes   uint32 unix seconds
courierIdHash 32 bytes  blake2b256 of the courier's deal-scoped credential id
```

One Schnorr signature over `blake2b256(handoff record)` under `courierPubKey`, verified
in-script by vault path B (ergoforum.org/t/3407 variant, `specs/vault-contract.md` §5);
the timestamp freshness bound is enforced in-script by path B against the claim tx
timestamp. Signatures are never part of the message bytes. **Sequencing rule:** the
courier signs only after physically counting the cash; cash changes hands as the
signature lands (the user app must have persisted the signed record before releasing
the cash). The buyer obtains the signed record at the meeting as the dispute artifact —
a record missing the courier's signature is worthless, so:

- a courier who collects cash but never signs leaves the user unable to claim — operationally treated as courier theft (GPS, dual-control), and the user never hands cash before the signed record is complete *in their app*;
- a courier signature without collected cash unlocks nothing the courier side could want: the claim it would open pays the *user* the seller's own collateral (§2).

The handoff record is the on-ramp's replacement for the off-ramp's oracle-gated claim: it
gates path B and anchors maturation.

### 3.3 QR payloads

- **Courier → user (handoff):** `p2pgate://handoff?m=<base64url(handoff record)>`.
  The courier renders the unsigned record; the user app validates amount/currency against
  the deal terms, the courier signs, the record is complete.
- **User → seller (payout address):** the user's USDT address (plain Tron base58 or
  EIP-55 string) + expected amount, shared at QUOTED so R9's `recipientAddr` pins it.
  Exact per-chain URI formats are an implementation detail of `specs/android-app.md`.

### 3.4 Oracle attestation

Digest layout defined in `specs/oracle-integration.md` §2. The binding rule flips with the
direction: the digest's `dealId`, `amount`, `srcChainId`, and `recipient` fields must equal
the vault's R4/R9 values, where `recipient` is the **user's** USDT address (the seller
pays the user). In phase 1 the attestation is realized as the oracle box being a full
input to the **release** transaction (the oracle holds the NFT-bearing oracle box); in
phase 2 as a k-of-n guard signature bundle over the same digest. The attestation is
**solely sufficient** on the release paths — no user or courier signature accompanies it.

## 4. Failure and edge transitions

Mapping of `onramp-ux.md` §6 onto the state machine:

| Situation | Transition | Result |
|---|---|---|
| User never shows up (no-show) | FUNDED → (RECLAIM_TIMEOUT) → RECLAIMED | silent close; collateral back to seller |
| Cash collected, seller never sends USDT | PAYMENT_PENDING → user hits dispute → CLAIM_OPENED | claim timeline shown (~`CLAIM_MATURATION`) |
| Seller sends partial USDT | blocked operationally ("send exactly N USDT in one tx"); if it happens, the oracle digest amount ≠ expected → release fails; claim path unaffected | flagged in dashboard dispute inbox |
| User claims without cause | CLAIM_OPENED → oracle signal lands → claim contested → seller presents the oracle digest alone → RELEASED (path C′) | dashboard shows "evidence attached" |
| User ghosts after USDT arrives | PAYMENT_CONFIRMED → (RECLAIM_TIMEOUT) → RECLAIMED | user keeps USDT; seller recovers collateral |
| Courier collects cash without signing the record | user app never released the cash (sequencing rule §3.2); operationally courier theft | operator investigation, not a contract case |
| App dies mid-deal | recovery by deal-link token or deal-key seed; state re-derived from chain + backend | no server-side account to lose |
| Oracle lag | quote publishing auto-pauses operator-side (`specs/operator-backend.md` §infra); in-flight claims are unaffected — the user's claim never depends on oracle liveness; releases and without-cause contests wait for the oracle | |

## 5. Privacy requirements (binding on all implementations)

- No accounts, no KYC in the apps; AML is seller-side and off-chain (decision recorded,
  report not stored — `onramp-ux.md` §4).
- Deal-scoped everything: keys (user, courier), link token. Nothing correlates deal X
  with deal Y except the operator's internal records.
- No phone numbers exchanged; meeting coordination via the deal link.
- Notifications opt-in, default off; Tor/no-notification mode available.

## 6. Cross-references

- Contract states and paths: `specs/vault-contract.md` §1–§4; revision items for this
  direction: `specs/vault-contract.md` §8.3
- Oracle attestation format: `specs/oracle-integration.md`
- UX flows this renders: `onramp-ux.md` §2, §6
- Design rationale: `onramp-insurance.md` §2–§3
