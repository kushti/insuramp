// P2PGATE vault — PAYMENT_PROVEN box: the buyer has opened a claim and this box
// waits out the maturation period. It carries the id of the seller's handoff
// record in R8 — a reminder of what the claim is about, not evidence of payment.
//
// WHY A SECOND BOX AT ALL: the FUNDED box cannot hold both a claim-open height
// and a reclaim deadline, because a register cannot be written twice and the
// deadline was fixed at funding. Opening the claim therefore re-creates the box
// under this script, whose R7 records the height the claim landed at — and that
// height is what the waiting period counts from. Terms as defined in
// vault_funded.es; glossary in specs/deal-protocol.md "Key concepts".
//
// Spending paths (specs/vault-contract.md §4):
//   D  — the buyer takes the collateral, once CLAIM_MATURATION has elapsed and
//        no contest landed. Buyer signs (proveDlog(R6)); paid in full to ANY
//        buyer-chosen address.
//   C' — the seller counters the claim: the oracle box as a DATA INPUT, its R4
//        naming this deal, and the seller co-signing (proveDlog(R5)). This is how
//        an honest seller wins a claim the oracle has already disproved. No
//        oracle signature rides along — a data input's script never executes.
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
// Reading the oracle data input is confined to the C′ branch on purpose: a buyer
// taking the collateral must not be forced to supply an attestation box, and the
// contract must not read a data input that may not be there. ErgoScript `if`
// branches are lazy, so that works.
{
  val sellerKey = decodePoint(SELF.R5[Coll[Byte]].get)
  val buyerKey = decodePoint(SELF.R6[Coll[Byte]].get)

  // R7: the height at which the buyer's claim landed on chain — the anchor the
  // maturation wait counts from. Named for what it is, not for the register: the
  // FUNDED box's R8 holds a timeout height measured from funding, which is a
  // different clock entirely.
  val claimOpenedAtHeight = SELF.R7[Long].get

  // Both paths pay out in full at OUTPUTS(0), to any address the signer chooses —
  // each path's signature authorizes the spend, so the collateral is conserved but
  // the destination is free (key rotation).
  val payoutOk =
    OUTPUTS(0).tokens(0)._1 == SELF.tokens(0)._1 &&
    OUTPUTS(0).tokens(0)._2 == SELF.tokens(0)._2

  // The buyer may take the collateral only when all three hold: the waiting period
  // has elapsed, the payout conserves it, and no attestation box is attached.
  // That last condition is what separates the two paths — a contest always
  // carries one, a payout never does. Deciding on the waiting period alone would
  // misroute a contest that lands late: its payout satisfies `payoutOk` just as
  // well, so the buyer would be paid for a claim the oracle had already
  // disproved. Reading `dataInputs.size` keeps the contest reachable at any height.
  //
  // The conditions sit in the guard rather than in each branch because a mixed
  // `proveDlog && Boolean` is not a SigmaProp — each branch has to stand alone.
  //
  // The maturation delay is the hardcoded literal 360 (blocks; ~12h at the
  // ~2-minute target, specs/vault-contract.md §2). Nothing varies it between
  // deployments, so it is not a %%...%% substitution parameter (2026-10-03);
  // `ContractParams.CLAIM_MATURATION_BLOCKS` mirrors it for the off-chain code
  // and `FundedActionSpec` guards the pair against drift.
  if (HEIGHT.toLong > claimOpenedAtHeight + 360L && payoutOk && CONTEXT.dataInputs.size == 0) {
    // Path D — the waiting period is over and nobody contested: the buyer takes it.
    proveDlog(buyerKey)
  } else {
    // Path C' — the seller answers the claim with the oracle's word that the
    // payment went through, and takes the collateral back.
    //
    // The oracle NFT is pinned at compile time here rather than per-box as in R7,
    // because this box has no register to spare. Phase 2 therefore changes the
    // check itself, not a constant.
    //
    // Having the NFT and a matching R4 is all that is verified. The message it
    // stands for — from the seller, to the right address, confirmed, not tainted —
    // is the oracle's word, and no contract can check it.
    val attestationBox = CONTEXT.dataInputs(0)
    val oracleNftOk = attestationBox.tokens(0)._1 == %%ORACLE_NFT_ID%%
    val fieldsOk = attestationBox.R4[Coll[Byte]].get == SELF.R4[Coll[Byte]].get
    // And the seller signs too: the attestation alone must never move funds, or
    // anyone could pay themselves the moment one appeared.
    sigmaProp(oracleNftOk && fieldsOk && payoutOk) && proveDlog(sellerKey)
  }
}
