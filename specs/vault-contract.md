# Vault Contract Specification — USDT Leg (Oracle-Verified)

*Contract spec for the vault-insured cash→USDT on-ramp in `onramp-insurance.md` (read that first —
this file turns §2 and §3.1 into implementable ErgoScript). State names and wire formats are
shared with `specs/deal-protocol.md`; the oracle side is specced in `specs/oracle-integration.md`.
Implementation target: cash→USDT on-ramp, USE collateral — the seller acts last
and locks the vault. The phase-1 oracle is a
**trusted centralized entity authenticated by NFT** (owner decision); the Rosen-derived
guard threshold is the post-launch upgrade and changes exactly one check (§3.3, §5).
BTC and XMR legs are extension notes (§8.1/§8.2), not full specs.
**Scope note:** the v2 revision (§8.4) has landed — the `.es` contracts on disk
implement it: R7 holds the bare 32-byte `oracleNftId`, path B is gated on ONE Schnorr
signature (the seller half of the handoff record, under the seller key in R5), and
paths C/C′ are oracle-only (oracle box as full input + digest field checks — there is
no buyer receipt signature anywhere in v2). §7 documents the implemented contract
mechanics.*

## 1. Design summary

The seller locks collateral (USE tokens) in a vault box. The vault pays out to whichever side
presents cryptographic proof of the deal outcome. Direction: cash→USDT on-ramp — the buyer
hands cash to the seller first (they meet in person); the seller sends USDT afterwards (it
acts last, so it locks the collateral).

| Path | Condition | Pays |
|---|---|---|
| A — Reclaim | `RECLAIM_TIMEOUT` elapsed, no claim opened | seller (minus fee) |
| B — Open claim | the **seller-signed handoff record**: one Schnorr signature from `sellerPubKey` (R5) over the cash-received record (§5) | moves box FUNDED → PAYMENT_PROVEN |
| C — Release | **oracle-only**: the oracle box as a full input, co-signing a digest of the **seller's** USDT transfer to the buyer (recipient = R9), digest fields checked against R4/R9 — nothing else (from FUNDED, and from PAYMENT_PROVEN as path C′; the digest is supplied in-tx either way — path B carried the handoff record, not the digest) | seller, immediately (minus fee) |
| D — Claim | PAYMENT_PROVEN + `CLAIM_MATURATION` elapsed | Buyer (minus fee) |

Two box states are required because the maturation delay must be anchored to an on-chain
event (the moment the claim — the handoff record — lands). ErgoScript cannot timestamp a
past off-chain event, so path B spends the FUNDED box into a PAYMENT_PROVEN box that records
the proof height in a register.

Canonical deal states (see `specs/deal-protocol.md` §1):
`QUOTED → FUNDED → PAYMENT_PENDING → PAYMENT_CONFIRMED → RELEASED`,
timeout branch `FUNDED → RECLAIMED` (buyer no-show) or `PAYMENT_CONFIRMED → RECLAIMED`
(seller paid, buyer ghosted), dispute branch `PAYMENT_PENDING → CLAIM_OPENED →
CLAIMABLE → CLAIMED`. On-chain, `CLAIM_OPENED`/`CLAIMABLE` are the PAYMENT_PROVEN box.

The direction flip versus the off-ramp is a clean swap of proof roles: the off-ramp gated
the *claim* on the oracle (proof the buyer paid) and the *release* on the buyer's signature
(proof cash arrived); the on-ramp gates the *claim* on the seller-signed handoff record
(proof cash was collected — there is no payment for an oracle to attest at claim time) and
the *release* on the oracle digest alone (proof the seller's USDT arrived). In v2 the
buyer's deal key signs nothing on the claim/release paths — it only authorizes the
maturation payout on path D — and the seller's own signature alone never releases the
vault (self-attestation protects nobody); only the trusted oracle's co-signature does
(§9 states what that costs).

## 2. Parameters (canonical values)

All other docs and code reference these names; the numbers live only here.

| Parameter | Value | Rationale |
|---|---|---|
| `RECLAIM_TIMEOUT` | 24h ≈ 720 Ergo blocks [approx] | deal window; quote expiry on the buyer side |
| `CLAIM_MATURATION` | 12h ≈ 360 blocks [approx] | gives the seller time to counter a claim with the oracle digest (path C′) |
| `HANDOFF_RECORD_MAX_AGE` | 4h [spec] | freshness bound on the handoff-record timestamp (renamed from `DELIVERY_MSG_MAX_AGE` in v2 — same value and role) |
| `BTC_DEADLINE` | ~6h ≈ 180 blocks [approx] | BTC leg only (§8.1) |
| `PROTOCOL_FEE_BPS` | 25, compile-time constant baked into both contract scripts (2026-09-17 hardcode) | `onramp-business-model.md` §2 |
| Hash function | `blake2b256` | ErgoTree has `blake2b256`/`sha256` only — no Keccak-256 |

Block counts assume the ~2-minute Ergo block target [approx]; contracts use heights, and the
operator backend converts wall-clock times to heights conservatively.

## 3. FUNDED box

### 3.1 Contents

- **Tokens:** exactly one token kind — USE — amount = `dealAmount` (the insured value, equal
  to the USDT amount of the deal, per `onramp-insurance.md` §2).
- **Value (ERG):** enough to cover the box itself and the two future spends (claim/release);
  surplus is reclaimed by the seller. [spec: 0.01 ERG default]

### 3.2 Registers

| Reg | Type | Content |
|---|---|---|
| R4 | `Coll[Byte]` | `dealId` = `blake2b256(deal terms)` — see `specs/deal-protocol.md` §3.1 |
| R5 | `Coll[Byte]` | `sellerPubKey` — 33 B compressed secp256k1 point; the contract `decodePoint`s it (sigma 6 cannot lift `GroupElement` constants embedded as `Coll[Byte]` elsewhere, so keys are stored raw) |
| R6 | `Coll[Byte]` | `buyerPubKey` — 33 B compressed point, same encoding as R5 |
| R7 | `Coll[Byte]` | `oracleNftId` — 32 B, the bare token id of the oracle box authenticating payment proofs on the release paths (phase 1; phase 2: the guard-set box's NFT — same register, same role). Path B does not read R7 at all: the claim verifies the handoff record against R5's `sellerPubKey` |
| R8 | `Long` | `timeoutHeight` — plain Long (the fee stopped being a per-box register field on 2026-09-17: `PROTOCOL_FEE_BPS` is a compile-time constant, so no packed ints) |
| R9 | `Coll[Byte]` | source-chain binding, 31 B: `srcChainId(1) \| tokenId(1) \| recipientAddr(21) \| expectedAmount(8, big-endian)` — pinned at funding so the oracle digest fields (layout: `specs/oracle-integration.md` §2.2) can be checked against them. **On-ramp semantics:** `recipientAddr` is the *buyer's* USDT address (the seller pays the buyer) |
| R10 | — | **does not exist** — Ergo boxes have registers R4–R9 only. `dealId` (R4) already binds the handoff-record signer via the deal terms (`specs/deal-protocol.md` §3.1) |

### 3.3 Spending paths (ErgoScript-level conditions)

Path selection uses a Boolean `if` on `HEIGHT`, not `SigmaProp ||`: the proof reducer
evaluates every `val` of every block it enters, so `||` over SigmaProp branches would force
context-variable material of paths that are not being spent. All `getVar`-dependent
material lives inside the branch that consumes it.

**Path A — reclaim (timeout).** Conditions:
- `HEIGHT > timeoutHeight`
- `proveDlog(sellerPubKey)` — the seller signs the spending transaction
- outputs: one output paying all USE tokens minus the `PROTOCOL_FEE_BPS` cut to an address derived from
  `sellerPubKey`; one fee output (§6) — always required, the fee is always charged.

This is the default routine path: buyer no-show, or the seller already paid and the buyer
ghosted (the buyer keeps the USDT, so the reclaim harms no one). The seller reclaims to a
fresh stealth/mixer-derived address so routine reclaims do not
link the seller's vaults (`onramp-insurance.md` §2 privacy paragraph). The protocol state
machine rejects reclaim from PAYMENT_PENDING (cash collected, no payment proof —
a theft path, answered by a claim); the contract itself relies on `RECLAIM_TIMEOUT` plus
operational deterrence, as in the off-ramp design.

**Oracle authentication (shared by paths C and C′ — release only; claims do not involve the
oracle in this direction).** The spending transaction must include the **oracle box as a
full input**: a box whose tokens contain `oracleNftId` (== R7) and whose script requires
`proveDlog(oracleKey)`. The oracle co-signs the transaction only after confirming the
seller's USDT transfer to the R9 recipient (the buyer's address;
`specs/oracle-integration.md` §3.1); its signature on the whole transaction binds the
attestation (the digest supplied in-tx as a context variable). A data input is **not**
sufficient — the oracle
must be a signing input, so no one can attach a stale or foreign attestation without the
oracle's live consent.

Phase-2 upgrade note: this check — and only this check — is replaced by a GuardSign-style
guard-set box (NFT data input holding `Coll[Coll[Byte]]` guard keys + threshold) with
`atLeast(k, guardPks)` Schnorr signatures over the payment-proof digest (§5). The digest
format, registers, and paths are unchanged.

**Path B — open claim (cash-collection proof).** Anyone (in practice the buyer app) spends
the FUNDED box into the PAYMENT_PROVEN box, supplying via context variables the
seller-signed handoff record (`specs/deal-protocol.md` §3.2): its 52-byte message plus
the seller Schnorr half `(a_sig, z_sig)`. No oracle input.

Conditions checked in-script:
- the handoff record's `dealId == R4.dealId` and its amount/currency match the deal terms
  hashed into R4 (the `dealId` equality is the binding — same as every other path);
- a valid Schnorr signature from `sellerPubKey` (R5) over the record (§5) — the
  seller-side attestation that cash was collected. This is the **only** signature
  verified on the claim path (v2): the buyer obtains it at the meeting as the dispute
  artifact; no artifact, no claim;
- record `timestamp` within `HANDOFF_RECORD_MAX_AGE` of `CONTEXT.preHeader.timestamp`
  (freshness — a stale record cannot be replayed into a later dispute);
- output 0 is a PAYMENT_PROVEN box carrying **all** tokens and ERG, with registers copied
  plus `proofHeight = HEIGHT` and the handoff record's identifying bytes for the
  dashboard's evidence view.

The record is signed under the same R5 key that reclaims the collateral, so a seller
opening a claim on its own vault is self-defeating: the claim pays the *buyer* the
seller's own collateral — no new attack. The real residual is behavioral, not
cryptographic: a seller who takes the cash and **refuses to sign** leaves the buyer with
no on-chain dispute artifact at all. The buyer's protection is procedural — do not hand
over cash until the app shows the verified record persisted — the same exposure as any
face-to-face cash trade. `CLAIM_MATURATION` (12h) then gives the seller time to react:
an honest seller that paid counters the claim with the oracle digest alone (path C′), or
raises the alarm off-chain.

**Path C — release (oracle-only, direct from FUNDED).** The routine fast close: the seller
collects the cash and signs the handoff record, the seller sends the USDT, the
oracle confirms the transfer, and the operator backend spends the FUNDED box in a single
transaction carrying the oracle's attestation:
- oracle authentication as above, with the digest field checks against R4/R9
  (`recipientAddr` = the buyer's USDT address) — and **nothing else** (v2: the buyer's
  receipt signature and its freshness binding are gone; the oracle is trusted, §9).

Outputs: USE minus fee to the seller, fee output (§6). Routine deals therefore need
exactly two on-chain transactions — fund and release — and never touch the PAYMENT_PROVEN
state. The two-step route via PAYMENT_PROVEN (path B, then §4 path C′) remains available
as the dispute-recovery route.

## 4. PAYMENT_PROVEN box

### 4.1 Registers

| Reg | Type | Content |
|---|---|---|
| R4 | `Coll[Byte]` | `dealId` (copied) |
| R5 | `Coll[Byte]` | `sellerPubKey` — 33 B compressed point |
| R6 | `Coll[Byte]` | `buyerPubKey` — 33 B compressed point |
| R7 | `Long` | `proofHeight` — plain Long (the fee left the registers on 2026-09-17, see §2 `PROTOCOL_FEE_BPS`) |
| R8 | `Coll[Byte]` | handoff-record id bytes (`blake2b256` of `a_sig \| z_sig \| record` — for the dashboard's evidence view; the single seller half, §8.4) |

### 4.2 Spending paths

**Path C′ — release from PAYMENT_PROVEN (oracle-only).** This box carries the *handoff
record*, not the payment proof (path B was a claim — the seller had not paid), so the
oracle digest must be supplied **in this transaction**:
- oracle authentication as §3.3 (oracle box as full input), with the digest field checks
  against R4/R9 — and **nothing else** (v2: no receipt signature, no freshness vars);
- outputs: USE minus fee to the seller, fee output (§6).

This is how an honest seller resolves **any** claim on a deal it actually fulfilled:
present the oracle digest, alone. Because the release direction needs no buyer signature,
the v1 withheld-receipt corner is gone — a buyer who claims without cause cannot block the
release by withholding anything, so oracle-only C′ resolves every false claim and path D
only ever pays out when the seller genuinely did not deliver (or the oracle itself is
compromised — accepted, see §9).

**Path D — claim (buyer payout).** Conditions:
- `HEIGHT > proofHeight + CLAIM_MATURATION`;
- `proveDlog(buyerPubKey)`;
- outputs: USE minus fee to the buyer's deal-key address, fee output (§6).

The maturation delay exists so an honest seller that *did* deliver can still present the
oracle digest (path C′ is strictly faster and cheaper for the seller than letting the
claim mature).

## 5. In-script Schnorr verification

ErgoScript verifies the handoff-record signature directly with group operations (no
Keccak needed — `blake2b256` is the hash), using the ergoforum.org/t/3407 variant where
the challenge binds the public key:

```
e = blake2b256(a.getEncoded ++ msg ++ sellerKey.getEncoded)   // as BigInt (two's complement)
check: groupGenerator.exp(z) == a.multiply(sellerKey.exp(e))  // z = r + e*x mod n
```

`msg` is the 52-byte handoff record (`specs/deal-protocol.md` §3.2, `magic = "P2PH"`), so
the signature already commits to `dealId`, fiat amount/currency, and timestamp. `byteArrayToBigInt` is a signed two's-complement interpretation on both sides, so
signer and contract agree; the signer grinds the nonce until `z` fits 254 bits so its
encoding is always positive (see `contracts/src/test/kotlin/p2pgate/contracts/Schnorr.kt`).

**Path B verifies this scheme once:** against `sellerPubKey` (R5), over the
handoff record, freshness-bounded as below. One signature opens the claim — there is no
second half (v2). The release paths (C/C′) verify no Schnorr signature at all: the
oracle's co-signed input plus the digest field checks are the whole gate.

Context variables:

| Var | Type | Content |
|---|---|---|
| **Path B (claim)** | | |
| 0 | `Coll[Byte]` | handoff record msg, 52 B |
| 1 | `Coll[Byte]` | `a_sig` — nonce point, 33 B compressed |
| 2 | `Coll[Byte]` | `z_sig` — response, 32 B big-endian |
| 3 | `Long` | record timestamp in millis (msg bytes 48..52 as seconds × 1000) |
| **Paths C/C′ (release)** | | |
| 0 | `Coll[Byte]` | payment-proof payload, 112 B (`specs/oracle-integration.md` §2.2) |

**Freshness** (path B only — the release paths carry no signed message and no freshness
check in v2) is checked against `CONTEXT.preHeader.timestamp` on the `Long` context var,
and the var is bound to the *signed* record bytes by `longToByteArray(tsMs / 1000L).slice(4, 8) == msg.slice(48, 52)`, so a submitter cannot pair a fresh timestamp with a stale signed record. (`byteArrayToBigInt` cannot do ordering comparisons on a slice — sigma 6's typer rejects `assignType` on it — hence the `Long` context var instead of converting `msg` bytes in-script.)

**sigma-state 6 typing constraints** (hard-won, apply to any edit of the contracts):

- `byteArrayToBigInt` only typechecks when its argument is a *direct expression* —
  a bare `getVar[...](n).get`, a `slice` of one, or `blake2b256(...)` thereof. Val
  references as arguments fail; so do arithmetic results (`* bigInt(...)`).
- Ordering/arithmetic on a `byteArrayToBigInt` result fails the typer; only `==`
  comparisons and method arguments (`exp(...)`) work.
- Tuples in registers are stored as `Coll` at runtime — pack paired ints into a `Long`
  arithmetically (`(hi << 32) | lo`) instead of `(Int, Int)`.
- `proveDlog(k) && boolean` types as `SBoolean`, so `if` branches over paths must be
  bare `proveDlog(k)` on one side and `sigmaProp(boolean)` on the other, with the
  payment conditions moved into the `if` guard.
- The proof reducer evaluates every `val` of every block it enters — never place
  `getVar(...).get` in a block that a path without those context variables can reach.

- **Signature usage:** path B is the only in-script Schnorr verification — one invocation
  against `sellerPubKey` over the handoff record (v2). The release paths verify no
  Schnorr signature; the oracle is authenticated by NFT + its input signature (§3.3),
  not by in-script Schnorr.
- **Phase 2:** the oracle threshold is `k` invocations of the same check over
  the payment-proof digest against the guard-set box's key list, with strictly increasing
  key indices for distinctness — the GuardSign/Lock pattern
  (`specs/oracle-integration.md` §3.2).

Reference for Schnorr-in-ErgoScript: ergoforum.org/t/3407 (see `onramp-insurance.md` §6).

## 6. Protocol fee output

Every path that moves collateral out (A, C/C′, D) deducts the protocol fee
(`PROTOCOL_FEE_BPS`, §2 — a compile-time constant baked into both scripts) of the USE amount
into a treasury output:

- fee output script = `TREASURY_SCRIPT_HASH` (compile-time constant; governance rotates it
  by deploying new vault contract versions, per `onramp-business-model.md` §2);
- `fee = dealAmount * PROTOCOL_FEE_BPS / 10000`; rounding down; the fee output is required
  on every collateral-moving path (no fee-free mode);
- hard minimum deal size: `fee` rounds down to 0 below 400 USE base units, and a
  zero-amount token output is unbuildable — so ~400 units is the in-protocol floor;
- on path D the fee comes out of the **buyer's** payout; on A/C/C′ out of the **seller's**
  payout — same economic effect per `onramp-business-model.md` §1.

Note (honest economics): on path A for a ghosted deal the fee is deadweight for the
operator. Acceptable while `PROTOCOL_FEE_BPS` is 25 and deal sizes are small; flagged as an
open question whether timeout-reclaims of *unstarted* deals should be fee-exempt (would
require a contract change — the fee is no longer a parameter).

## 7. Testing strategy (Kotlin / sigma-state)

Contracts live in `contracts/src/main/ergoscript/` as `.es` sources (shipped on the
classpath as resources), compiled and tested in the `contracts` Kotlin Gradle module
against sigma-state 6.0.6 directly. The v2 revision (§8.4) is implemented, and the matrix
below documents the implemented suite in
`contracts/src/test/kotlin/p2pgate/contracts/VaultContractSpec.kt` (plus
`OracleContractSpec.kt` for the oracle box's own progression); run it with
`./gradlew :contracts:test` (JDK 17; see `AGENTS.md`). Status: tests 1–44 pass (35 has
five parts, 35a–35e) and O1–O8 pass; 45 is `@Disabled` pending the phase-2 guard-set
box. Test matrix:

**FUNDED box**
1. Reclaim before `timeoutHeight` — fails.
2. Reclaim after `timeoutHeight` by seller key — passes; tokens minus fee to seller.
3. Reclaim by wrong key — fails.
4. Open claim with a valid seller-signed handoff record — passes; PAYMENT_PROVEN box
   created with `proofHeight = HEIGHT` and the record id in R8.
5. Record signed under the **buyer** key instead of `sellerPubKey` (R5) —
   fails (the v2 gate is the seller half only; what would have been the buyer half of the
   old dual-signed record opens nothing).
6. Honest seller half paired with a tampered record byte in every field region (magic,
   version, dealId, amount, currency, timestamp) — fails (the in-script
   challenge binds the carried record bytes).
7. Open claim with a stale record timestamp (> `HANDOFF_RECORD_MAX_AGE`) — fails.
8. Open claim with a future record timestamp — fails.
9. Fresh `tsMs` var bound to a stale record (window passes, slice binding must not) —
   fails.
10. In-window record paired with a stale `tsMs` var (reverse of 9) — fails.
11. Open claim with a record bound to a different `dealId` — fails.
12. Open claim draining tokens to a non-PAYMENT_PROVEN output — fails.
13. Open claim with an oracle box among the inputs fails (path B involves no oracle:
    oracle.es demands its reproduction at `OUTPUTS(0)`, which the PAYMENT_PROVEN output
    occupies, so no path-B tx can both open the claim and satisfy the oracle's script).
14. PAYMENT_PROVEN output with wrong `proofHeight` R7 (plain Long) — fails.
15. PAYMENT_PROVEN output carrying a wrong R8 record id (everything else honest) — fails.
16. Release from FUNDED (path C) oracle-only: oracle box as full input (NFT == R7)
    + digest fields vs R4/R9, nothing else — passes; seller paid minus fee.
17. Release from FUNDED without the oracle box among inputs — fails.
18. Release from FUNDED with the oracle box as a mere data input (no oracle signature;
    a stale/foreign attestation must not be attachable without the oracle's live
    consent) — fails.
19. Release from FUNDED with an oracle box carrying a different NFT — fails.
20. Release from FUNDED with a foreign-script box carrying the oracle NFT: the vault's
    NFT check accepts it at the vault-script level, but on-chain rejection comes from
    the foreign box's own script refusing to be spent without its key (two-leg test) —
    the full transaction can never validate.
21. Release from FUNDED paying collateral to an address that is not the seller's — fails.
22. Release from FUNDED with digest `amount` ≠ `R9.expectedAmount` — fails.
23. Release from FUNDED with digest `recipientAddr` ≠ R9's recipient — fails.
24. Release from FUNDED with digest `dealId` ≠ R4 — fails.
25. Digest `srcTxId` is **not** bound on-chain — passes (documents the checked-field
    set: only `dealId`, `chainId`, `tokenId`, `recipientAddr`, `amount` are pinned
    against R4/R9; `srcTxId`/`srcHeight`/`srcTime` ride along for audit, and the
    oracle's co-signature is the trust root for those bytes).

**PAYMENT_PROVEN box**
26. Release (path C′) oracle-only — passes; seller paid immediately.
27. Release without the oracle box among inputs — fails.
28. Release with the oracle box as a mere data input — fails.
29. Release with digest `amount` ≠ `R9.expectedAmount` — fails.
30. Release with digest `dealId` ≠ R4 — fails.
31. Oracle-only C′ counters a live claim (the v2 residual fix): a claim opened with a
    VALID record (path B) is resolved by the digest alone — passes; no buyer receipt
    signature exists to withhold.
32. Claim before maturation — fails.
33. Claim after maturation by buyer key — passes; buyer paid minus fee.
34. Claim by wrong key — fails.
35. Fee arithmetic with the hardcoded `PROTOCOL_FEE_BPS` (25): rounding down (incl.
    collateral = dealAmount+1), missing/wrong fee output on each collateral-moving path,
    release-path fee coverage, and the claim fee deducted from the **buyer's** payout —
    fails/passes as specified (35a–35e).

**Cross-deal replay**
36. Oracle digest from deal X applied to vault of deal Y — fails.
37. Handoff record from deal X applied to vault of deal Y — fails.

**Adversarial Schnorr inputs (path B)**
38. Honest (a, z) over the correct record but the record context var carrying one
    flipped bit — the in-script challenge is recomputed over the var bytes — fails.
39. `z` = the secp256k1 group order n as 32 bytes (top bit set, negative under signed
    `byteArrayToBigInt`) — fails.
40. Honest `z` with its sign bit set — fails.
41. A 33-byte nonce point that is not a valid compressed curve point — `decodePoint`
    throws during evaluation and the spend is rejected there — fails.

**R7 oracleNftId (path B / path C)**
42. Handoff record signed by a fresh random key (not the R5 seller key) — path B fails
   (the pinned R5 credential, not any carried key, is authoritative).
43. R7 carrying a wrong `oracleNftId` — path C fails (the NFT check compares the input
    box's token id against R7).
44. Open claim with a wrong R7 `oracleNftId` — path B **passes** (R7 pins the release
    path's oracle NFT only; path B reads R5, not R7, so a vault whose R7 names a
    different oracle NFT is still claimable with a valid record — a deployment error,
    not a claim-path hole).

**Oracle box (oracle.es) — box progression**
O1. Rotation by the oracle key, NFT + value preserved into `OUTPUTS(0)` (pinned position) — passes.
O2. Rotation with increased value (refill/fee-tolerant `out.value >= SELF.value`) — passes.
O3. Spend by a key other than `oracleKey` — fails.
O4. Spend with no oracle signature — fails.
O5. Rotation output missing the NFT — fails.
O6. Rotation output carrying a different token id — fails.
O7. Rotation with the NFT reproduced at `OUTPUTS(1)` instead of `OUTPUTS(0)` — fails (the
    reproduction position is pinned: on joint release spends the oracle box owns
    `OUTPUTS(0)` and the vault pays the seller at `OUTPUTS(1)` — §8.4).
O8. Rotation draining value below `SELF.value` — fails.

**Phase-2 readiness**
45. Swap the oracle-authentication check for a 2-of-3 GuardSign-style guard box and rerun
    the release tests (16–31) — the digest field checks (22–24, 29–30) must be untouched.

## 8. Extension notes

### 8.1 BTC leg (trustless, rsBTC collateral)

Same skeleton; the oracle-authenticated payment proof is replaced by a **Bitcoin relay
inclusion proof**: the buyer opens the claim by presenting a relay-verified proof that
txid `T` (from the seller's signed deal message) paid the promised amount. No oracle NFT
in R7; instead the relay contract expects a proof against a relay-tracked Bitcoin header
chain. Deadline semantics differ: `BTC_DEADLINE` ~6h sizes the seller's window to land
the BTC tx; the buyer can dispute immediately (1 tx) or wait. Research implementation to
build against: github.com/ross-weir/ergohack-sidechain (`BtcTxCheck.es`). Collateral is
rsBTC instead of USE. Trust model: relay soundness + confirmation depth — no trusted
party.

### 8.2 XMR leg (oracle-verified via tx-key reveal, rsXMR hypothetical)

Same skeleton as USDT; the oracle's attestation is backed by re-running Monero
`check_tx_key` verification against a Monero daemon (see `specs/oracle-integration.md`
§7). Two hard blockers today, stated plainly: rsXMR does not exist (Rosen does not
support Monero), and ErgoTree's missing Keccak-256 makes trustless in-script verification
impossible. This leg is a design placeholder, not a roadmap item.

### 8.3 On-ramp revision items (implemented, partially superseded by §8.4)

The on-ramp revision landed in the `.es` sources; the C′ compile-time NFT pin from item 3
still stands. Items 1–2 and 4 describe gates **since replaced by v2 (§8.4)**: R7 no longer
packs a second key (it holds the bare `oracleNftId`), path B is no longer dual-signed, the
receipt signature is gone from C/C′, and the R8 record id covers the single seller half.
For the record, the four items as they originally landed:

1. **DONE, superseded by §8.4 — a second deal-scoped key pinned at funding, packed into
   R7** alongside the oracle NFT id (Ergo boxes have registers R4–R9 only; there is no
   R10). The oracle-token check sliced bytes 0..32.
2. **DONE, superseded by §8.4 — path B re-gated** from the oracle co-signature + payment
   digest to the dual-signed handoff record; v2 reduced this to the single seller half.
   No oracle input on this path — an oracle box present in a claim transaction breaks
   the spend: oracle.es demands its reproduction at `OUTPUTS(0)`, which the PAYMENT_PROVEN
   output occupies, so no path-B tx can both open the claim and satisfy the oracle's
   script (test 13 pins this interaction).
3. **DONE, superseded by §8.4 — paths C/C′ re-gated**: the oracle digest describes the
   **seller's** USDT transfer with `recipientAddr` = the buyer's address (R9 semantics per
   §3.2), and C′ takes the oracle box as a full input too (the PAYMENT_PROVEN box carries
   the handoff record, not the digest — unlike the off-ramp reading where the proof was
   already on-chain). V2 dropped the buyer receipt signature that this item had kept.
   Implementation note that still stands: the PAYMENT_PROVEN box has no spare register
   for the oracle NFT id (R7 carries `proofHeight`, R9 the
   copied funding binding), so `vault_payment_proven.es` pins `%%ORACLE_NFT_ID%%` at
   compile time — the same oracle the funding vault pinned in its R7. An operator must
   deploy both contracts from one parameter set; a mismatch is a deployment error, not a
   contract hole.
4. **DONE, superseded by §8.4 — PAYMENT_PROVEN R8** holds the handoff-record id bytes,
   checked in-script against the carried context variables (test 15). V2's id covers the
   single seller half: `blake2b256(a_sig | z_sig | record)`.

Unchanged by the revision: paths A and D, the fee logic (§6), freshness mechanics,
both height registers (R8 `timeoutHeight`, proven R7 `proofHeight` — plain Longs since
the 2026-09-17 fee hardcode), the oracle box contract itself, and all sigma-6
constraints (§5).

### 8.4 v2 revision (implemented)

The owner's simplified design landed in the `.es` sources on disk; the §7 matrix covers
it. The changes, and why:

1. **Path B verifies ONE Schnorr signature** — the seller half under `sellerPubKey`
   (R5) over the 52-byte `P2PH` record, with the existing freshness binding
   (`longToByteArray(tsMs/1000).slice(4,8) == msg.slice(48,52)` + the 4h window vs
   `CONTEXT.preHeader.timestamp`). The buyer-half verification is dropped: the buyer
   obtains the seller's signature at the meeting as the dispute artifact; no meeting, no
   artifact, no claim. Output-0 PAYMENT_PROVEN construction is unchanged (registers
   copied, `proofHeight`, R8 = `blake2b256(a_sig | z_sig | record)`). No oracle
   input on path B (tests 4–15, 37–42, 44).
2. **Paths C/C′ are oracle-only**: the oracle box as a full input (NFT check vs R7 /
   the compile-time pin, script requires `proveDlog(oracleKey)`) plus the
   digest field checks vs R4/R9 — and nothing else. The buyer's receipt signature, the
   `P2PG` delivery message, the signature context vars, and their freshness binding are
   gone (tests 16–31, 36, 43).
3. **The withheld-receipt residual is eliminated**: an honest seller can now counter ANY
   claim with the digest alone (test 31), so path D only pays out when the seller
   genuinely did not deliver (or the oracle is compromised — accepted, §9).
4. **`CLAIM_MATURATION` 24h → 12h** (720 → 360 blocks; §2): the seller's reaction window
   shrinks accordingly, and oracle-only C′ makes the shorter window sufficient.
5. **The release direction fully trusts the phase-1 oracle** — stated plainly in §9;
   there is no "oracle never sufficient alone" hedge anywhere in v2.

Unchanged by v2: path A, path D's shape (only the maturation constant moved), the fee
logic (§6), path-B freshness mechanics, USE collateral, and all sigma-6 constraints (§5).

Post-v2 contract simplifications (2026-09-17, milestone M3): the joint-spend output layout
is fixed. `oracle.es` pins its NFT self-reproduction at `OUTPUTS(0)` (same tree, NFT id +
amount preserved, `value ≥ SELF.value`), and the vault contracts moved the release-path
seller payout from `OUTPUTS(0)` to `OUTPUTS(1)` (paths C/C′ only — reclaim, open-claim,
and claim payouts stay at `OUTPUTS(0)`, as those transactions carry no oracle box). An
earlier `OUTPUTS.exists` formulation made the reproduction position-free; the owner
chose a fixed position to keep both scripts trivially small (`OracleContractSpec` test 7
pins the inversion). The `oracle.es`-governed box is the release/contest input, exercised
end-to-end in `:apps:core:ergo` (`OperatorTxBuilder` + `DevOracle`, prover-verified).

Second 2026-09-17 simplification: the fee left the registers. `feeBps` was packed with
`timeoutHeight`/`proofHeight` into FUNDED R8 / proven R7; it is now the compile-time
`PROTOCOL_FEE_BPS` (25, §2) substituted into both scripts — both registers are plain
`Long` heights, `TxAssembly.packInts` is deleted, and the treasury fee output is required
on every collateral-moving path (no fee-free mode). Same day, the courier role was
removed entirely (seller-signed handoff record under R5, R7 = bare `oracleNftId`) — see
`specs/deal-protocol.md` §3.2 and the trust-model note in §9.

## 9. Trust model (stated honestly)

- **Phase 1 (launch): the release direction fully trusts a single centralized oracle.**
  Authenticated by NFT, but trust is trust: on paths C and C′ the oracle's attestation
  **alone** is sufficient to move collateral (v2 — there is no receipt signature behind
  it). A malicious or compromised oracle can co-sign a digest of a payment that never
  happened and release the vault to a confederate seller, and **there is no on-chain
  defense; that is accepted** (owner decision, §8.4). Mitigations are operational, not
  cryptographic: oracle operator ≠ marketplace operator, every attestation publicly
  auditable against source-chain data (the digest's `srcTxId` is carried for exactly
  this, though it is not checked in-script — test 25), deal-size caps while centralized
  (`specs/oracle-integration.md` §5.3). Do not describe phase 1 with "cost-to-attack"
  language — there is no threshold to attack.
- **Phase 2:** the k-of-n guard threshold (Rosen GuardSign/Lock pattern) restores the
  intended posture: not trustless, but cost-to-attack (slashable guard collateral +
  future fee income) exceeding extractable value per deal (`onramp-business-model.md` §3).
- **Claim/dispute direction:** trusts nothing cryptographic beyond Schnorr soundness.
  The handoff artifact is **seller-signed under the same R5 key that reclaims the
  collateral**, and the consequences must be stated plainly. (a) The claim pays the
  *buyer*, so a seller opening a claim on its own vault is self-defeating — a fake or
  coerced record unlocks a payout of the seller's own collateral to the buyer's address,
  not to the seller. No new attack there. (b) The real residual is behavioral: a seller
  who takes the cash and **refuses to sign** leaves the buyer with no on-chain dispute
  artifact at all — the claim path simply has nothing to verify. The buyer's protection
  is procedural, and it is the same exposure as any face-to-face cash trade: do not hand
  over the cash until the app shows the verified record persisted. There is no rogue-
  third-party operational-security case anymore — no separate cash-side actor exists whose
  vetting would be the trust boundary; the cash leg's trust boundary is the meeting
  itself.
- The contract is only as good as the collateral's liquidity: USE depth on Ergo DEXs caps
  practical deal size (`onramp-insurance.md` §5).

## 10. Cross-references

- Design: `onramp-insurance.md` §2–§3.1, §5
- Deal protocol (wire formats, state machine): `specs/deal-protocol.md`
- Oracle side (phases, digest format): `specs/oracle-integration.md`
- Product flows these states render: `onramp-ux.md` §2
- Fee economics: `onramp-business-model.md` §2–§3
