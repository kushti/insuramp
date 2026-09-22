// P2PGATE vault — PAYMENT_PROVEN box (claim opened; the SELLER-signed handoff record
// is on-chain — this box carries the record's id in R8, NOT the payment proof).
//
// The box exists so CLAIM_MATURATION is anchored to the on-chain moment the claim —
// the handoff record — landed (proofHeight in R7). Spending paths (see
// specs/vault-contract.md §4):
//   C' — release: the oracle singleton box as a DATA INPUT (phase-1 NFT,
//        compile-time pin — the proven box has no spare register for it)
//        with exactly the 32-byte dealId in the data input's R4 — the
//        oracle's per-deal signal that the seller's USDT transfer to the
//        buyer is confirmed AND screened non-tainted (off-chain
//        preconditions, unchecked here; semantics in oracle.es). Nothing
//        else (v2: no buyer receipt signature; the oracle is trusted,
//        period); seller paid in full.
//        A data input's script never executes, so no oracle signature rides
//        in the contest tx. Oracle-only C' is also how an honest seller
//        counters ANY claim: the attestation alone resolves it.
//   D  — claim:   HEIGHT > proofHeight + CLAIM_MATURATION_BLOCKS, buyer signs
//
// Registers:
//   R4 Coll[Byte]        dealId (32 B, blake2b256 of deal terms — specs/deal-protocol.md §3.1)
//   R5 Coll[Byte]        sellerPubKey — 33 B compressed secp256k1 point
//   R6 Coll[Byte]        buyerPubKey — 33 B compressed secp256k1 point
//   R7 Long              proofHeight (the claim-open height, blocks)
//   R8 Coll[Byte]        handoff-record id (32 B, blake2b256 of a_sig | z_sig |
//                        record — the dashboard's evidence view)
//
// Context variables: none on either path — the claim discharges proveDlog(buyerKey)
// and the release attestation (the dealId itself) rides in the oracle data
// input's R4.
//
// ErgoScript vals are lazy and if / Boolean && / || short-circuit, so the
// release-path material below is only evaluated when the release path is
// actually taken — a claim spend never touches the oracle data input. (SigmaProp
// || would evaluate every branch during proof reduction, so the paths are
// selected with a Boolean if instead.)
{
  val sellerKey = decodePoint(SELF.R5[Coll[Byte]].get)
  val buyerKey = decodePoint(SELF.R6[Coll[Byte]].get)
  val collateral = SELF.tokens(0)._2
  val useTokenId = SELF.tokens(0)._1
  val proofH = SELF.R7[Long].get
  // Paths C′ and D both pay out in full at OUTPUTS(0) — the release is no
  // longer a joint spend with the oracle box (it rides as a data input), so
  // the old OUTPUTS(1) seller-payout convention is gone.
  val buyerPaid =
    OUTPUTS(0).propositionBytes == proveDlog(buyerKey).propBytes &&
    OUTPUTS(0).tokens(0)._1 == useTokenId &&
    OUTPUTS(0).tokens(0)._2 == collateral
  val sellerPaid =
    OUTPUTS(0).propositionBytes == proveDlog(sellerKey).propBytes &&
    OUTPUTS(0).tokens(0)._1 == useTokenId &&
    OUTPUTS(0).tokens(0)._2 == collateral

  // NB: the proof reducer evaluates every val of the block it is in, so all
  // data-input-dependent material lives inside the branch that consumes it —
  // a claim spend never touches it (specs/vault-contract.md §4.2). The payment
  // conditions are part of the guard so each branch is a bare SigmaProp (a
  // mixed proveDlog && Boolean would not type as SigmaProp).
  if (HEIGHT.toLong > proofH + %%CLAIM_MATURATION_BLOCKS%%.toLong && buyerPaid) {
    // Path D — claim after maturation; the buyer discharges proveDlog(buyerKey).
    proveDlog(buyerKey)
  } else {
    // Path C' — release, oracle-only: the oracle singleton box as a DATA INPUT
    // (its script never executes — no oracle signature), its R4 carrying
    // exactly the 32-byte dealId (this box carries the handoff record in R8,
    // not the payment signal). The data input must carry the phase-1 oracle
    // NFT — the compile-time pin of this tree (the FUNDED box pins the NFT id
    // in its R7; the proven box has no spare register for it; phase 2
    // replaces this check with the guard-set box, §3.3 of the spec). NFT
    // custody is the whole phase-1 trust root — a data input's script never
    // executes, so any box carrying the pinned NFT id and this deal's dealId
    // in R4 passes. The payload carries nothing but the dealId, so "from the
    // seller, to the right address, confirmed, non-tainted" is wholly the
    // oracle's trusted off-chain assertion.
    val attestationBox = CONTEXT.dataInputs(0)
    val oracleNftOk = attestationBox.tokens(0)._1 == %%ORACLE_NFT_ID%%
    // The attestation must name THIS vault's deal (R4, the dealId).
    val fieldsOk = attestationBox.R4[Coll[Byte]].get == SELF.R4[Coll[Byte]].get
    sigmaProp(oracleNftOk && fieldsOk && sellerPaid)
  }
}
