// P2PGATE vault — PAYMENT_PROVEN box (claim opened; the SELLER-signed handoff record
// is on-chain — this box carries the record's id in R8, NOT the payment proof).
//
// The box exists so CLAIM_MATURATION is anchored to the on-chain moment the claim —
// the handoff record — landed (proofHeight in R7). Spending paths (see
// specs/vault-contract.md §4):
//   C' — release: oracle box as full input (phase-1 NFT, compile-time pin — the proven
//        box has no spare register for it, R9 carries the copied funding binding) with
//        the payment-proof digest supplied in-tx (context var 0) — nothing else (v2:
//        no buyer receipt signature; the oracle is trusted, period); seller paid minus
//        fee. Oracle-only C' is also how an honest seller counters ANY claim: the
//        digest alone resolves it.
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
// Context variables (release path only):
//   (0) Coll[Byte]  payment-proof payload (112 B, specs/oracle-integration.md §2.2 —
//                   digest of the SELLER's USDT transfer to the R9 buyer address)
//
// ErgoScript vals are lazy and if / Boolean && / || short-circuit, so the release-path
// material below is only evaluated when the release path is actually taken — a claim
// spend must not supply context variables. (SigmaProp || would evaluate every branch
// during proof reduction, so the paths are selected with a Boolean if instead.)
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
  // The protocol fee is a compile-time constant (%%FEE_BPS%% bps, §6) — always
  // charged, so the treasury fee output is always required. NB: rounds down, so
  // sub-400-unit collaterals yield fee 0 and can never satisfy the fee output
  // (a box cannot carry a 0-amount token) — the practical minimum deal size.
  val fee = collateral * %%FEE_BPS%%.toLong / 10000L

  // Oracle authentication (path C'): the oracle box must be a full INPUT of this
  // transaction carrying the phase-1 oracle NFT. The FUNDED box pins the NFT id in
  // its R7; the proven box has no spare register for it (R9 carries the copied
  // funding binding the digest fields are checked against), so the NFT id is a
  // compile-time pin — the same oracle the funding vault pinned (phase 2 replaces
  // this check with the guard-set box, §3.3 of the spec). A data input is not sufficient.
  val oracleOk = INPUTS.exists { (b: Box) => b.tokens.exists { (t: (Coll[Byte], Long)) => t._1 == %%ORACLE_NFT_ID%% } }

  val feeOutOk = OUTPUTS.exists { (o: Box) =>
    blake2b256(o.propositionBytes) == %%TREASURY_SCRIPT_HASH%% &&
    o.tokens(0)._1 == useTokenId &&
    o.tokens(0)._2 == fee
  }
  // The fee is always charged (compile-time constant), so the treasury fee
  // output is always required.
  val feeOk = feeOutOk

  // Path D pays the buyer into OUTPUTS(0); path C′ shares the transaction with the
  // oracle box, whose contract pins its own reproduction at OUTPUTS(0) (oracle.es),
  // so the release payout goes to OUTPUTS(1).
  val buyerPaid =
    OUTPUTS(0).propositionBytes == proveDlog(buyerKey).propBytes &&
    OUTPUTS(0).tokens(0)._1 == useTokenId &&
    OUTPUTS(0).tokens(0)._2 == collateral - fee
  val sellerPaid =
    OUTPUTS(1).propositionBytes == proveDlog(sellerKey).propBytes &&
    OUTPUTS(1).tokens(0)._1 == useTokenId &&
    OUTPUTS(1).tokens(0)._2 == collateral - fee

  // NB: the proof reducer evaluates every val of the block it is in, so all
  // getVar-dependent material lives inside the branch that consumes it — a claim
  // spend never touches it (specs/vault-contract.md §4.2). The payment conditions
  // are part of the guard so each branch is a bare SigmaProp (a mixed proveDlog &&
  // Boolean would not type as SigmaProp).
  if (HEIGHT.toLong > proofH + %%CLAIM_MATURATION_BLOCKS%%.toLong && buyerPaid && feeOk) {
    // Path D — claim after maturation; the buyer discharges proveDlog(buyerKey).
    proveDlog(buyerKey)
  } else {
    // Path C' — release, oracle-only: the oracle digest of the SELLER's USDT transfer
    // (supplied in-tx — this box carries the handoff record, not the digest); no key
    // needed from the submitter (funds can only go to the seller). Context var 0.
    // NB: sigma-state 6 only typechecks byteArrayToBigInt when its argument is a
    // direct expression (no val references), so the conversions stay fully inline.
    val payload = getVar[Coll[Byte]](0).get
    // The attestation must match THIS vault's deal (fields pinned in R4/R9).
    val fieldsOk =
      payload.slice(1, 33) == SELF.R4[Coll[Byte]].get &&
      payload(33) == chainId &&
      payload(34) == tokenIdF &&
      payload.slice(35, 56) == recipient &&
      byteArrayToBigInt(getVar[Coll[Byte]](0).get.slice(56, 64)) == byteArrayToBigInt(SELF.R9[Coll[Byte]].get.slice(23, 31))
    sigmaProp(oracleOk && fieldsOk && sellerPaid && feeOk)
  }
}
