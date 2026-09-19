// P2PGATE vault — PAYMENT_PROVEN box (claim opened; the SELLER-signed handoff record
// is on-chain — this box carries the record's id in R8, NOT the payment proof).
//
// The box exists so CLAIM_MATURATION is anchored to the on-chain moment the claim —
// the handoff record — landed (proofHeight in R7). Spending paths (see
// specs/vault-contract.md §4):
//   C' — release: the oracle singleton box as a DATA INPUT (phase-1 NFT,
//        compile-time pin — the proven box has no spare register for it, R9
//        carries the copied funding binding) with the 112-byte attestation
//        payload in the data input's R4 — nothing else (v2: no buyer receipt
//        signature; the oracle is trusted, period); seller paid in full.
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
//   R9 Coll[Byte]        funding binding copied from the FUNDED box, 31 B: srcChainId(1) |
//                        tokenId(1) | recipientAddr(21) | expectedAmount(8, big-endian) —
//                        the path C' digest fields are checked against this;
//                        recipientAddr is the buyer's USDT address (the seller pays the buyer)
//
// Context variables: none on either path — the claim discharges proveDlog(buyerKey)
// and the release payload rides in the oracle data input's R4.
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
  val r9 = SELF.R9[Coll[Byte]].get
  val chainId = r9(0)
  val tokenIdF = r9(1)
  val recipient = r9.slice(2, 23)
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
    // (its script never executes — no oracle signature), its R4 carrying the
    // 112-byte attestation payload of the SELLER's USDT transfer (this box
    // carries the handoff record, not the digest). The data input must carry
    // the phase-1 oracle NFT — the compile-time pin of this tree (the FUNDED
    // box pins the NFT id in its R7; the proven box has no spare register for
    // it, R9 carries the copied funding binding; phase 2 replaces this check
    // with the guard-set box, §3.3 of the spec). NFT custody is the whole
    // phase-1 trust root — a data input's script never executes, so any box
    // carrying the pinned NFT id and a field-matching R4 payload passes.
    val attestationBox = CONTEXT.dataInputs(0)
    val payload = attestationBox.R4[Coll[Byte]].get
    val oracleNftOk = attestationBox.tokens(0)._1 == %%ORACLE_NFT_ID%%
    // The attestation must match THIS vault's deal (fields pinned in R4/R9).
    // The amount is compared as raw big-endian bytes (both sides carry the
    // same 8-byte form) — byteArrayToBigInt on a val reference fails the
    // sigma-state 6 typer.
    val fieldsOk =
      payload.slice(1, 33) == SELF.R4[Coll[Byte]].get &&
      payload(33) == chainId &&
      payload(34) == tokenIdF &&
      payload.slice(35, 56) == recipient &&
      payload.slice(56, 64) == r9.slice(23, 31)
    sigmaProp(oracleNftOk && fieldsOk && sellerPaid)
  }
}
