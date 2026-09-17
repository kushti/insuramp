// P2PGATE vault — FUNDED box (collateral locked, deal live). v2 (specs/vault-contract.md
// §8.4): the claim is gated on ONE Schnorr signature (the seller's signature over the
// P2PH handoff record — the cash-received acknowledgment signed at the meeting under
// the R5 seller key); release is oracle-only (the trusted phase-1 oracle's co-signed
// input plus the payment-proof digest — no buyer receipt signature anywhere).
//
// Spending paths (see specs/vault-contract.md §3.3):
//   A — reclaim: HEIGHT > timeoutHeight (R8), seller signs, paid minus fee
//   B — open claim: the SELLER's Schnorr signature (R5 key) over the P2PH handoff
//       record with freshness; spends into the PAYMENT_PROVEN box.
//       No oracle input on this path
//   C — release: oracle box as full input (NFT == R7, script requires
//       proveDlog(oracleKey)) + payment-proof digest fields (context var 0, 112 B
//       payload per specs/oracle-integration.md §2.2, describing the SELLER's USDT
//       transfer to the buyer's address in R9) — nothing else; seller paid minus fee
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
//   R9 Coll[Byte]              funding binding, 31 B: srcChainId(1) | tokenId(1) |
//                              recipientAddr(21) | expectedAmount(8, big-endian) —
//                              recipientAddr is the buyer's USDT address (the seller pays
//                              the buyer); the digest fields are checked against this
//
// Context variables:
//   Path B (claim):    (0) Coll[Byte] handoff record msg (52 B, "P2PH")
//                      (1) Coll[Byte] a_sig — Schnorr nonce point, 33 B
//                      (2) Coll[Byte] z_sig — Schnorr response, 32 B
//                      (3) Long        record timestamp in millis (msg bytes 48..52 * 1000)
//   Path C (release):  (0) Coll[Byte] payment-proof payload (112 B)
//
// Path selection: the branch condition may only touch context variable 0, which both
// non-reclaim paths supply — the proof reducer forces every val of every block it
// enters, so a condition reaching for a variable the other path does not supply would
// reject that path outright. Path B is recognized by its record carrying THIS deal's
// dealId at bytes 5..37 (a path C payload cannot match: its dealId sits at bytes 1..33,
// so bytes 5..37 are a dealId tail plus chain/token bytes); everything else — the
// seller Schnorr signature, freshness, the PAYMENT_PROVEN output shape — is checked in
// the branch body, which only evaluates when the discriminator matches. (SigmaProp || would
// evaluate every branch during proof reduction, hence the Boolean if.)
{
  val sellerKey = decodePoint(SELF.R5[Coll[Byte]].get)
  val collateral = SELF.tokens(0)._2
  val useTokenId = SELF.tokens(0)._1
  val timeoutH = SELF.R8[Long].get
  val r9 = SELF.R9[Coll[Byte]].get
  val chainId = r9(0)
  val tokenIdF = r9(1)
  val recipient = r9.slice(2, 23)
  // The protocol fee is a compile-time constant (%%FEE_BPS%% bps, §6) — every
  // collateral-moving path charges it, so the treasury fee output is always
  // required. NB: rounds down, so sub-400-unit collaterals yield fee 0 and
  // can never satisfy the fee output (a box cannot carry a 0-amount token) —
  // the practical minimum deal size.
  val fee = collateral * %%FEE_BPS%%.toLong / 10000L

  // Oracle authentication (path C only — on-ramp claims do not involve the oracle):
  // the oracle box must be a full INPUT of this transaction, carrying the NFT pinned
  // in R7 (specs/vault-contract.md §3.3). A data input is not sufficient.
  val oracleOk = INPUTS.exists { (b: Box) => b.tokens.exists { (t: (Coll[Byte], Long)) => t._1 == SELF.R7[Coll[Byte]].get } }

  val feeOutOk = OUTPUTS.exists { (o: Box) =>
    blake2b256(o.propositionBytes) == %%TREASURY_SCRIPT_HASH%% &&
    o.tokens(0)._1 == useTokenId &&
    o.tokens(0)._2 == fee
  }
  // The fee is always charged (compile-time constant), so the treasury fee
  // output is always required.
  val feeOk = feeOutOk

  // Path A pays the seller into OUTPUTS(0); path C shares the transaction with the
  // oracle box, whose contract pins its own reproduction at OUTPUTS(0) (oracle.es),
  // so the release payout goes to OUTPUTS(1).
  val sellerPaidReclaim =
    OUTPUTS(0).propositionBytes == proveDlog(sellerKey).propBytes &&
    OUTPUTS(0).tokens(0)._1 == useTokenId &&
    OUTPUTS(0).tokens(0)._2 == collateral - fee
  val sellerPaidRelease =
    OUTPUTS(1).propositionBytes == proveDlog(sellerKey).propBytes &&
    OUTPUTS(1).tokens(0)._1 == useTokenId &&
    OUTPUTS(1).tokens(0)._2 == collateral - fee

  if (HEIGHT > timeoutH && sellerPaidReclaim && feeOk) {
    // Path A — reclaim after timeout; the seller discharges proveDlog(sellerKey).
    proveDlog(sellerKey)
  } else if (getVar[Coll[Byte]](0).get.slice(5, 37) == SELF.R4[Coll[Byte]].get) {
    // Path B — open claim on the SELLER-signed handoff record (the cash-received
    // acknowledgment from the meeting); anyone may submit. Context vars 0..3.
    // NB: sigma-state 6 only typechecks byteArrayToBigInt when its
    // argument is a direct expression (no val references), so the conversions stay
    // fully inline.
    val msg = getVar[Coll[Byte]](0).get
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
    // Output: the PAYMENT_PROVEN box carrying everything, registers copied
    // (R9 funding binding included), proofHeight = HEIGHT, record id in R8.
    val provenOutOk =
      OUTPUTS(0).propositionBytes == %%PAYMENT_PROVEN_SCRIPT%% &&
      OUTPUTS(0).R4[Coll[Byte]].get == SELF.R4[Coll[Byte]].get &&
      OUTPUTS(0).R5[Coll[Byte]].get == SELF.R5[Coll[Byte]].get &&
      OUTPUTS(0).R6[Coll[Byte]].get == SELF.R6[Coll[Byte]].get &&
      OUTPUTS(0).R9[Coll[Byte]].get == r9 &&
      OUTPUTS(0).R7[Long].get == HEIGHT.toLong &&
      OUTPUTS(0).R8[Coll[Byte]].get == recordId &&
      OUTPUTS(0).tokens(0)._1 == useTokenId &&
      OUTPUTS(0).tokens(0)._2 == collateral &&
      OUTPUTS(0).value == SELF.value
    sigmaProp(freshOk && sellerSigOk && provenOutOk)
  } else {
    // Path C — fast close, oracle-only: the oracle-authenticated digest of the
    // SELLER's USDT transfer (recipient = R9's buyer address). No receipt signature
    // (v2 — the oracle is trusted, period). Context var 0. The payout sits at
    // OUTPUTS(1): OUTPUTS(0) is the oracle box's pinned reproduction (oracle.es).
    val payload = getVar[Coll[Byte]](0).get
    // The attestation must match THIS vault's deal (fields pinned in R4/R9).
    val fieldsOk =
      payload.slice(1, 33) == SELF.R4[Coll[Byte]].get &&
      payload(33) == chainId &&
      payload(34) == tokenIdF &&
      payload.slice(35, 56) == recipient &&
      byteArrayToBigInt(getVar[Coll[Byte]](0).get.slice(56, 64)) == byteArrayToBigInt(SELF.R9[Coll[Byte]].get.slice(23, 31))
    sigmaProp(oracleOk && fieldsOk && sellerPaidRelease && feeOk)
  }
}
