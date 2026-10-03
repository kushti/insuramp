// TERMS, defined once (full glossary: specs/deal-protocol.md "Key concepts"):
//   CLAIM   the buyer demanding the locked collateral, because the seller took the
//           cash at the meeting and never sent the USDT. Needs the seller's signature
//           on the handoff record.
//   RELEASE the seller taking the collateral back, because the oracle confirmed the
//           USDT reached the buyer. Needs the oracle's attestation.
//   RECLAIM the seller taking the collateral back because the deal window closed
//           without a claim. Needs nothing but the passage of time and his own key.
//   CONTEST a release that lands while a claim is still maturing, killing the claim.
//   The seller signature appears on both claim and release: he may open a claim and
//   then pay out, but he cannot make the collateral pay him twice.
//
// P2PGATE vault — FUNDED box (collateral locked, deal live). v2 (specs/vault-contract.md
// §8.4): the claim needs ONE Schnorr signature (the seller's signature over the P2PH
// handoff record — the cash-received acknowledgment signed at the meeting under the R5
// seller key); release needs the trusted phase-1 oracle's attestation and nothing else
// (no buyer receipt signature anywhere) with the seller co-signing the payout.
//
// Spending paths (see specs/vault-contract.md §3.3):
//   A — reclaim: HEIGHT > timeoutHeight (R8), seller signs (proveDlog(R5 key)),
//       paid in full to ANY seller-chosen address (key rotation: the signature
//       authorizes the spend, so the payee is not pinned to the R5 key)
//   B — open claim: the SELLER's Schnorr signature (R5 key) over the P2PH handoff
//       record with freshness; spends into the PAYMENT_PROVEN box.
//       No oracle input on this path
//   C — release: the oracle singleton box as a DATA INPUT (NFT == R7) whose R4
//       carries exactly the 32-byte dealId — the oracle's single per-deal signal
//       that the seller's USDT transfer to the buyer is confirmed AND screened
//       non-tainted; both preconditions (and "to the right address") are
//       off-chain and unchecked here (semantics in oracle.es). Checked on-chain:
//       NFT custody + dealId equality, full-collateral payout to ANY
//       seller-chosen address, AND the seller's proveDlog(R5) signature — the
//       attestation alone must never direct funds (anyone could otherwise pay
//       themselves once an attestation box exists), so the seller co-signs and
//       may pay a fresh key. A data input's script never executes, so no oracle
//       signature rides in the release tx
//
// WHY THESE THREE and not one: they are the only ways collateral can leave, and each
// one needs proof of something different having happened — a deadline passed (A), a
// dispute the seller signed off on (B), a payment the oracle confirmed (C). A fourth
// possibility, "the buyer simply takes it", does not exist here: that is path D, and
// it lives in the successor box (vault_payment_proven.es).
//
// Registers:
//   R4 Coll[Byte]              dealId (32 B)
//   R5 Coll[Byte]              sellerPubKey — 33 B compressed secp256k1 point
//   R6 Coll[Byte]              buyerPubKey — 33 B compressed secp256k1 point
//   R7 Coll[Byte]              oracleNftId (32 B) — token id of the oracle box
//                              authenticating release paths (phase 1: trusted
//                              centralized oracle; phase 2: guard-set box NFT —
//                              same register, same role)
//   R8 Long                 timeoutHeight (reclaim timeout, blocks)
//
// Context variables:
//   (0) Byte           action — which path is being invoked:
//                      0 = CLAIM (open a claim), 1 = RECLAIM, 2 = RELEASE.
//                      Mandatory on every spend. These are structural constants,
//                      hardcoded like basis.es's action codes — they are NOT
//                      deployment parameters (nothing varies them between a
//                      mainnet and a fast e2e compile). `ContractParams.ACTION_*`
//                      mirrors them for the tx builders, and `FundedActionSpec`
//                      asserts the two never drift.
//   Path B (claim):    (1) Coll[Byte] handoff record msg (52 B, "P2PH")
//                      (2) Coll[Byte] a_sig — Schnorr nonce point, 33 B
//                      (3) Coll[Byte] z_sig — Schnorr response, 32 B
//                      (4) Long        record timestamp in millis (msg bytes 48..52 * 1000)
//   Paths A/C:         no further vars — the release attestation (the dealId
//                      itself) rides in the oracle data input's R4
//
// PATH SELECTION: the spender names the path in context var 0, and each branch
// then proves its own conditions independently. This is the Basis reserve
// contract's shape (`basis.es` §Actions), and it buys two things over inferring
// the path from what the transaction happens to carry:
//
//   1. Every path is positively identified. Previously release was the residual
//      branch ("anything else"), so a reader had to reconstruct a priority order
//      from prose to learn which path a given spend took.
//   2. The paths stop competing. The old discriminator ranked them — claim-open
//      by var-0 presence, reclaim by HEIGHT, release by elimination — because
//      the PAYMENT_PROVEN output also satisfies the payout conservation check,
//      so a height-first order would have swallowed every post-timeout
//      claim-open into the reclaim path. With an explicit action the ordering
//      hazard is structurally impossible: a claim-open IS a claim-open at any
//      height, and a reclaim IS a reclaim. "A claim beats a reclaim" is now
//      carried by the absence of a reclaim path on the PAYMENT_PROVEN box
//      (a script cannot delete one of its own paths — see vault_payment_proven.es)
//      rather than by a branch order an auditor has to trust.
//
// The action byte is NOT covered by the seller's handoff-record signature, and
// need not be: choosing CLAIM is the only choice that helps the buyer, so no
// party can be coerced into it by a third party.
//
// Everything else — the record's dealId binding, freshness, the PAYMENT_PROVEN
// output shape — is checked in the branch body, which only evaluates when the
// action matches. (SigmaProp || would evaluate every branch during proof
// reduction, hence the Boolean if.) An unrecognized action code is rejected
// outright rather than falling through to the release branch.
{
  val action = getVar[Byte](0).get

  val sellerKey = decodePoint(SELF.R5[Coll[Byte]].get)
  val collateral = SELF.tokens(0)._2
  val useTokenId = SELF.tokens(0)._1
  // R8: the height past which the seller may reclaim unaided. Fixed at funding —
  // the buyer never writes it (the claim-open creates a different box instead,
//  which is why R8's meaning does not have to stretch across both lifetimes).
  val reclaimTimeoutHeight = SELF.R8[Long].get
  // Paths A and C both pay out in full at OUTPUTS(0) (reclaim and release
  // are payout-identical): the right token, the full collateral, ANY payee —
  // each path's proveDlog signature authorizes the spend, so the payee is
  // deliberately not pinned to a key (the seller may pay a fresh key for the
  // next iteration). Release txs carry the oracle box as a data input — its
  // script never executes — so the old joint-spend OUTPUTS(1) convention is gone.
  val payoutOk =
    OUTPUTS(0).tokens(0)._1 == useTokenId &&
    OUTPUTS(0).tokens(0)._2 == collateral

  if (action == 0) {
    // Path B — open claim on the SELLER-signed handoff record (the cash-received
    // acknowledgment from the meeting); anyone may submit. Context vars 1..4.
    // NB: sigma-state 6 only typechecks byteArrayToBigInt when its
    // argument is a direct expression (no val references), so anything feeding
    // a conversion stays inline — the results themselves may be named vals.
    val handoffRecord = getVar[Coll[Byte]](1).get
    // The action byte names the path but says nothing about the record, so the
    // record's dealId (bytes 5..37) must still bind THIS deal — checked here,
    // in the branch body.
    val recordDealOk = handoffRecord.slice(5, 37) == SELF.R4[Coll[Byte]].get
    // Freshness is checked on a Long context var (record ts seconds * 1000), bound to
    // the signed record bytes by Coll equality; byteArrayToBigInt cannot do ordering
    // comparisons on a slice (sigma-state 6 assignType limitation).
    val recordTimestampMs = getVar[Long](4).get
    val freshOk =
      longToByteArray(recordTimestampMs / 1000L).slice(4, 8) == handoffRecord.slice(48, 52) &&
      recordTimestampMs <= CONTEXT.preHeader.timestamp &&
      recordTimestampMs > CONTEXT.preHeader.timestamp - %%HANDOFF_RECORD_MAX_AGE_MS%%
    // The signature is the SELLER's Schnorr half, verified under R5's sellerKey —
    // the same key that proves the reclaim; the record is the buyer's dispute
    // evidence when the seller took cash but never sent the USDT.
    // Schnorr equation: g^z == R · P^e with e = H(R ‖ record ‖ P).
    // byteArrayToBigInt only typechecks on direct expressions (sigma-state 6
    // rejects val references as its argument), so the challenge preimage
    // stays inline — getVar(2) is the commitment R, getVar(1) the record.
    val sigR = decodePoint(getVar[Coll[Byte]](2).get)
    val sigZ = byteArrayToBigInt(getVar[Coll[Byte]](3).get)
    val challenge = byteArrayToBigInt(blake2b256(
      decodePoint(getVar[Coll[Byte]](2).get).getEncoded ++
      getVar[Coll[Byte]](1).get ++
      sellerKey.getEncoded))
    val gToZ = groupGenerator.exp(sigZ)
    val rTimesPe = sigR.multiply(sellerKey.exp(challenge))
    val sellerSigOk = gToZ == rTimesPe
    // Handoff-record id for the dashboard's evidence view.
    val recordId = blake2b256(
      getVar[Coll[Byte]](2).get ++ getVar[Coll[Byte]](3).get ++
      getVar[Coll[Byte]](1).get)

    // Output: the PAYMENT_PROVEN box carrying everything, registers copied,
    // proofHeight = HEIGHT, record id in R8.
    val provenOutOk =
      OUTPUTS(0).propositionBytes == %%PAYMENT_PROVEN_SCRIPT%% &&
      OUTPUTS(0).R4[Coll[Byte]].get == SELF.R4[Coll[Byte]].get &&
      OUTPUTS(0).R5[Coll[Byte]].get == SELF.R5[Coll[Byte]].get &&
      OUTPUTS(0).R6[Coll[Byte]].get == SELF.R6[Coll[Byte]].get &&
      OUTPUTS(0).R7[Long].get == HEIGHT.toLong &&
      OUTPUTS(0).R8[Coll[Byte]].get == recordId &&
      OUTPUTS(0).tokens(0)._1 == useTokenId &&
      OUTPUTS(0).tokens(0)._2 == collateral &&
      OUTPUTS(0).value == SELF.value
    sigmaProp(recordDealOk && freshOk && sellerSigOk && provenOutOk)
  } else if (action == 1) {
    // Path A — reclaim after timeout; the seller discharges proveDlog(sellerKey).
    // The timeout is checked INSIDE the branch, not in the discriminator: a
    // reclaim naming an action is a reclaim at any height, so this rejection is
    // unambiguous rather than "some other path matched instead".
    sigmaProp(HEIGHT > reclaimTimeoutHeight && payoutOk) && proveDlog(sellerKey)
  } else if (action == 2) {
    // Path C — fast close: the oracle singleton box as a DATA INPUT, its R4
    // carrying exactly the 32-byte dealId of the SELLER's USDT transfer to the
    // buyer. No receipt signature (v2 — the oracle is trusted, period) and no
    // oracle signature: a data input's script never executes. NFT custody is
    // the whole phase-1 trust root — ANY box carrying the pinned NFT id and
    // this deal's dealId in R4 passes (documented as intended in
    // VaultContractSpec test 20). The payload carries nothing but the dealId,
    // so "from the seller", "to the right address", "confirmed", and
    // "non-tainted" all remain the trusted oracle's off-chain assertions
    // (semantics in oracle.es). The seller discharges proveDlog(sellerKey) on
    // top: the attestation alone must never direct funds — without this
    // signature ANYONE could pay themselves the collateral the moment an
    // attestation box exists — and the seller may pay any address (key
    // rotation; the payee is free, only the full collateral is conserved).
    val attestationBox = CONTEXT.dataInputs(0)
    // The data input must carry the oracle NFT pinned in R7.
    val oracleNftOk = attestationBox.tokens(0)._1 == SELF.R7[Coll[Byte]].get
    // The attestation must name THIS vault's deal (R4, the dealId).
    val fieldsOk = attestationBox.R4[Coll[Byte]].get == SELF.R4[Coll[Byte]].get
    sigmaProp(oracleNftOk && fieldsOk && payoutOk) && proveDlog(sellerKey)
  } else {
    // No such path. A garbage action byte is rejected rather than being read as
    // a release (the pre-2026-10-04 residual branch): an unrecognized request
    // fails closed and says so.
    sigmaProp(false)
  }
}
