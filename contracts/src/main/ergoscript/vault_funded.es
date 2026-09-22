// P2PGATE vault — FUNDED box (collateral locked, deal live). v2 (specs/vault-contract.md
// §8.4): the claim is gated on ONE Schnorr signature (the seller's signature over the
// P2PH handoff record — the cash-received acknowledgment signed at the meeting under
// the R5 seller key); release is oracle-only (the trusted phase-1 oracle's attestation
// alone — no buyer receipt signature anywhere).
//
// Spending paths (see specs/vault-contract.md §3.3):
//   A — reclaim: HEIGHT > timeoutHeight (R8), seller signs, paid in full
//   B — open claim: the SELLER's Schnorr signature (R5 key) over the P2PH handoff
//       record with freshness; spends into the PAYMENT_PROVEN box.
//       No oracle input on this path
//   C — release: the oracle singleton box as a DATA INPUT (NFT == R7) whose R4
//       carries exactly the 32-byte dealId — the oracle's single per-deal signal
//       that the seller's USDT transfer to the buyer is confirmed AND screened
//       non-tainted; both preconditions (and "to the right address") are
//       off-chain and unchecked here (semantics in oracle.es). Checked on-chain:
//       NFT custody + dealId equality ONLY; seller paid in full. A data
//       input's script never executes, so no oracle signature rides in the
//       release tx
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
//   Path B (claim):    (0) Coll[Byte] handoff record msg (52 B, "P2PH")
//                      (1) Coll[Byte] a_sig — Schnorr nonce point, 33 B
//                      (2) Coll[Byte] z_sig — Schnorr response, 32 B
//                      (3) Long        record timestamp in millis (msg bytes 48..52 * 1000)
//   Path C (release):  none — the attestation (the dealId itself) rides in
//                      the oracle data input's R4
//
// Path selection: path A is recognized by HEIGHT alone; of the two remaining
// paths, path B supplies context variable 0 and path C supplies NONE, so the
// discriminator is `getVar(0).isDefined` — safe on an absent var (unlike `.get`,
// which the proof reducer would force and reject). Everything else — the
// record's dealId binding, freshness, the PAYMENT_PROVEN output shape — is
// checked in the branch body, which only evaluates when the discriminator
// matches. (SigmaProp || would evaluate every branch during proof reduction,
// hence the Boolean if.)
{
  val sellerKey = decodePoint(SELF.R5[Coll[Byte]].get)
  val collateral = SELF.tokens(0)._2
  val useTokenId = SELF.tokens(0)._1
  val timeoutH = SELF.R8[Long].get
  // Paths A and C pay the seller in full at OUTPUTS(0) (reclaim and release
  // are payout-identical): release txs carry the oracle box as a data input —
  // its script never executes — so the old joint-spend OUTPUTS(1) convention
  // is gone.
  val sellerPaid =
    OUTPUTS(0).propositionBytes == proveDlog(sellerKey).propBytes &&
    OUTPUTS(0).tokens(0)._1 == useTokenId &&
    OUTPUTS(0).tokens(0)._2 == collateral

  if (HEIGHT > timeoutH && sellerPaid) {
    // Path A — reclaim after timeout; the seller discharges proveDlog(sellerKey).
    proveDlog(sellerKey)
  } else if (getVar[Coll[Byte]](0).isDefined) {
    // Path B — open claim on the SELLER-signed handoff record (the cash-received
    // acknowledgment from the meeting); anyone may submit. Context vars 0..3.
    // NB: sigma-state 6 only typechecks byteArrayToBigInt when its
    // argument is a direct expression (no val references), so the conversions stay
    // fully inline.
    val msg = getVar[Coll[Byte]](0).get
    // The discriminator is only var PRESENCE, so the record's dealId (bytes
    // 5..37) must bind THIS deal — checked here, in the branch body.
    val recordDealOk = msg.slice(5, 37) == SELF.R4[Coll[Byte]].get
    // Freshness is checked on a Long context var (record ts seconds * 1000), bound to
    // the signed record bytes by Coll equality; byteArrayToBigInt cannot do ordering
    // comparisons on a slice (sigma-state 6 assignType limitation).
    val tsMs = getVar[Long](3).get
    val freshOk =
      longToByteArray(tsMs / 1000L).slice(4, 8) == msg.slice(48, 52) &&
      tsMs <= CONTEXT.preHeader.timestamp &&
      tsMs > CONTEXT.preHeader.timestamp - %%HANDOFF_RECORD_MAX_AGE_MS%%
    // The signature is the SELLER's Schnorr half, verified under R5's sellerKey —
    // the same key that proves the reclaim; the record is the buyer's dispute
    // evidence when the seller took cash but never sent the USDT.
    val sellerSigOk =
      groupGenerator.exp(byteArrayToBigInt(getVar[Coll[Byte]](2).get)) ==
        decodePoint(getVar[Coll[Byte]](1).get).multiply(sellerKey.exp(byteArrayToBigInt(blake2b256(
          decodePoint(getVar[Coll[Byte]](1).get).getEncoded ++
          getVar[Coll[Byte]](0).get ++
          sellerKey.getEncoded))))
    // Handoff-record id for the dashboard's evidence view.
    val recordId = blake2b256(
      getVar[Coll[Byte]](1).get ++ getVar[Coll[Byte]](2).get ++
      getVar[Coll[Byte]](0).get)
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
  } else {
    // Path C — fast close, oracle-only: the oracle singleton box as a DATA
    // INPUT, its R4 carrying exactly the 32-byte dealId of the SELLER's
    // USDT transfer to the buyer. No receipt signature (v2 — the oracle is
    // trusted, period) and no oracle signature: a data input's script never
    // executes. NFT custody is the whole phase-1 trust root — ANY box
    // carrying the pinned NFT id and this deal's dealId in R4 passes
    // (documented as intended in VaultContractSpec test 20). The payload
    // carries nothing but the dealId, so "from the seller", "to the right
    // address", "confirmed", and "non-tainted" all remain the trusted
    // oracle's off-chain assertions (semantics in oracle.es).
    val attestationBox = CONTEXT.dataInputs(0)
    // The data input must carry the oracle NFT pinned in R7.
    val oracleNftOk = attestationBox.tokens(0)._1 == SELF.R7[Coll[Byte]].get
    // The attestation must name THIS vault's deal (R4, the dealId).
    val fieldsOk = attestationBox.R4[Coll[Byte]].get == SELF.R4[Coll[Byte]].get
    sigmaProp(oracleNftOk && fieldsOk && sellerPaid)
  }
}
