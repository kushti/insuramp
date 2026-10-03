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
//   R9 Coll[Byte]        oracleNftId (32 B) — copied from the FUNDED box's R7 by
//                        the claim-open (path B enforces the copy); the contest
//                        gate reads it per-box, the same shape as vault_funded.es
//
// Context variables: var 0 (a `Byte`) names the spending path on BOTH paths —
// 0 = claim payout (D), 1 = contest (C′) — the same action-byte shape as the
// FUNDED box (2026-10-03; before, the path was inferred from `dataInputs.size`).
// Path D additionally discharges proveDlog(buyerKey) and the release attestation
// (the dealId itself) rides in the oracle data input's R4 on path C′.
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

  // The path discriminator: context var 0, a `Byte` naming the spending path —
  // the same shape as the FUNDED box's action byte (specs/vault-contract.md §3.3).
  // 0 = claim payout (D), 1 = contest (C′); anything else is rejected. Mandatory
  // (`.get`): a spend that declines to name a path fails during reduction.
  // Before 2026-10-03 the path was inferred from `dataInputs.size`; the explicit
  // action removes the inference, and each path's conditions are now checked
  // inside its own branch, so a misnamed spend is rejected rather than
  // re-routed. The codes are hardcoded literals, mirrored by
  // `ContractParams.ACTION_CLAIM_PAYOUT`/`ACTION_CONTEST` (`FundedActionSpec`
  // guards the pair against drift).
  val action = getVar[Byte](0).get

  // The maturation delay is the hardcoded literal 360 (blocks; ~12h at the
  // ~2-minute target, specs/vault-contract.md §2). Nothing varies it between
  // deployments, so it is not a %%...%% substitution parameter (2026-10-03);
  // `ContractParams.CLAIM_MATURATION_BLOCKS` mirrors it for the off-chain code
  // and `FundedActionSpec` guards the pair against drift.
  if (action == 0) {
    // Path D — the waiting period is over and nobody contested: the buyer takes
    // it. The maturation and the collateral conservation are checked inside the
    // branch; `dataInputs.size == 0` stays as a hygiene condition — a payout
    // never carries an attestation box (a contest always does), so a stray data
    // input rejects the spend rather than being silently ignored.
    sigmaProp(
      HEIGHT.toLong > claimOpenedAtHeight + 360L && payoutOk && CONTEXT.dataInputs.size == 0
    ) && proveDlog(buyerKey)
  } else if (action == 1) {
    // Path C' — the seller answers the claim with the oracle's word that the
    // payment went through, and takes the collateral back. Reachable at ANY
    // height, including after maturation: naming the action is what routes the
    // spend, not the clock.
    //
    // The oracle NFT id rides per-box in R9, copied from the FUNDED box's R7 by
    // the claim-open — the same per-box pin vault_funded.es uses for path C,
    // replacing the compile-time %%ORACLE_NFT_ID%% pin (2026-10-03). Phase 2
    // therefore changes the check itself, not a constant.
    //
    // Having the NFT and a matching R4 is all that is verified. The message it
    // stands for — from the seller, to the right address, confirmed, not tainted —
    // is the oracle's word, and no contract can check it.
    val attestationBox = CONTEXT.dataInputs(0)
    val oracleNftOk = attestationBox.tokens(0)._1 == SELF.R9[Coll[Byte]].get
    val fieldsOk = attestationBox.R4[Coll[Byte]].get == SELF.R4[Coll[Byte]].get
    // And the seller signs too: the attestation alone must never move funds, or
    // anyone could pay themselves the moment one appeared.
    sigmaProp(oracleNftOk && fieldsOk && payoutOk) && proveDlog(sellerKey)
  } else {
    // An unrecognized action is rejected — never fall through to a payout.
    sigmaProp(false)
  }
}
