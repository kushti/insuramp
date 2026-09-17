// P2PGATE vault — FUNDED box (collateral locked, deal live). v2 (specs/vault-contract.md
// §8.4): the claim is gated on ONE Schnorr signature (the courier half of the P2PH
// handoff record, under the deal-scoped courier key pinned in R7); release is
// oracle-only (the trusted phase-1 oracle's co-signed input plus the payment-proof
// digest — no user receipt signature anywhere).
//
// Spending paths (see specs/vault-contract.md §3.3):
//   A — reclaim: HEIGHT > timeoutHeight (R8), seller signs, paid minus fee
//   B — open claim: the courier Schnorr signature (R7 bytes 32..65) over the P2PH
//       handoff record with freshness; spends into the PAYMENT_PROVEN box.
//       No oracle input on this path
//   C — release: oracle box as full input (NFT == R7 bytes 0..32, script requires
//       proveDlog(oracleKey)) + payment-proof digest fields (context var 0, 112 B
//       payload per specs/oracle-integration.md §2.2, describing the SELLER's USDT
//       transfer to the user's address in R9) — nothing else; seller paid minus fee
//
// Registers:
//   R4 Coll[Byte]              dealId (32 B)
//   R5 Coll[Byte]              sellerPubKey — 33 B compressed secp256k1 point
//   R6 Coll[Byte]              userPubKey   — 33 B compressed secp256k1 point
//   R7 Coll[Byte]              65 B packed: oracleNftId(32) | courierPubKey(33) — token id
//                              of the oracle box authenticating release paths (phase 1:
//                              trusted centralized oracle; phase 2: guard-set box NFT —
//                              same register, same role) PLUS the deal-scoped courier
//                              credential verified in path B. Packed because Ergo boxes
//                              have registers R4–R9 only (no R10); the oracle check
//                              slices bytes 0..32
//   R8 Long                 (timeoutHeight << 32) | feeBps — packed ints (sigma 6
//                              registers store tuples as Coll, so ints are packed arithmetically)
//   R9 Coll[Byte]              funding binding, 31 B: srcChainId(1) | tokenId(1) |
//                              recipientAddr(21) | expectedAmount(8, big-endian) —
//                              recipientAddr is the USER's USDT address (the seller pays
//                              the user); the digest fields are checked against this
//
// Context variables:
//   Path B (claim):    (0) Coll[Byte] handoff record msg (84 B, "P2PH")
//                      (1) Coll[Byte] a_courier — Schnorr nonce point, 33 B
//                      (2) Coll[Byte] z_courier — Schnorr response, 32 B
//                      (3) Long        record timestamp in millis (msg bytes 48..52 * 1000)
//   Path C (release):  (0) Coll[Byte] payment-proof payload (112 B)
//
// Path selection: the branch condition may only touch context variable 0, which both
// non-reclaim paths supply — the proof reducer forces every val of every block it
// enters, so a condition reaching for a variable the other path does not supply would
// reject that path outright. Path B is recognized by its record carrying THIS deal's
// dealId at bytes 5..37 (a path C payload cannot match: its dealId sits at bytes 1..33,
// so bytes 5..37 are a dealId tail plus chain/token bytes); everything else — the
// courier Schnorr half, freshness, the PAYMENT_PROVEN output shape — is checked in the
// branch body, which only evaluates when the discriminator matches. (SigmaProp || would
// evaluate every branch during proof reduction, hence the Boolean if.)
{
  val sellerKey = decodePoint(SELF.R5[Coll[Byte]].get)
  val collateral = SELF.tokens(0)._2
  val useTokenId = SELF.tokens(0)._1
  val packed8 = SELF.R8[Long].get
  val timeoutH = (packed8 / 4294967296L).toInt
  val feeBps = (packed8 % 4294967296L).toInt
  val r9 = SELF.R9[Coll[Byte]].get
  val chainId = r9(0)
  val tokenIdF = r9(1)
  val recipient = r9.slice(2, 23)
  val fee = collateral * feeBps.toLong / 10000L

  // Oracle authentication (path C only — on-ramp claims do not involve the oracle):
  // the oracle box must be a full INPUT of this transaction, carrying the NFT pinned
  // in R7 bytes 0..32 (specs/vault-contract.md §3.3). A data input is not sufficient.
  val oracleOk = INPUTS.exists { (b: Box) => b.tokens.exists { (t: (Coll[Byte], Long)) => t._1 == SELF.R7[Coll[Byte]].get.slice(0, 32) } }

  val feeOutOk = OUTPUTS.exists { (o: Box) =>
    blake2b256(o.propositionBytes) == %%TREASURY_SCRIPT_HASH%% &&
    o.tokens(0)._1 == useTokenId &&
    o.tokens(0)._2 == fee
  }
  val feeOk = if (feeBps == 0) true else feeOutOk

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
    // Path B — open claim on the courier-signed handoff record; anyone may submit.
    // Context vars 0..3. NB: sigma-state 6 only typechecks byteArrayToBigInt when its
    // argument is a direct expression (no val references), so the conversions stay
    // fully inline.
    val courierKey = decodePoint(SELF.R7[Coll[Byte]].get.slice(32, 65))
    val msg = getVar[Coll[Byte]](0).get
    // Freshness is checked on a Long context var (record ts seconds * 1000), bound to
    // the signed record bytes by Coll equality; byteArrayToBigInt cannot do ordering
    // comparisons on a slice (sigma-state 6 assignType limitation).
    val tsMs = getVar[Long](3).get
    val freshOk =
      longToByteArray(tsMs / 1000L).slice(4, 8) == msg.slice(48, 52) &&
      tsMs <= CONTEXT.preHeader.timestamp &&
      tsMs > CONTEXT.preHeader.timestamp - %%HANDOFF_RECORD_MAX_AGE_MS%%
    val courierSigOk =
      groupGenerator.exp(byteArrayToBigInt(getVar[Coll[Byte]](2).get)) ==
        decodePoint(getVar[Coll[Byte]](1).get).multiply(courierKey.exp(byteArrayToBigInt(blake2b256(
          decodePoint(getVar[Coll[Byte]](1).get).getEncoded ++
          getVar[Coll[Byte]](0).get ++
          decodePoint(SELF.R7[Coll[Byte]].get.slice(32, 65)).getEncoded))))
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
      OUTPUTS(0).R7[Long].get == HEIGHT.toLong * 4294967296L + feeBps.toLong &&
      OUTPUTS(0).R8[Coll[Byte]].get == recordId &&
      OUTPUTS(0).tokens(0)._1 == useTokenId &&
      OUTPUTS(0).tokens(0)._2 == collateral &&
      OUTPUTS(0).value == SELF.value
    sigmaProp(freshOk && courierSigOk && provenOutOk)
  } else {
    // Path C — fast close, oracle-only: the oracle-authenticated digest of the
    // SELLER's USDT transfer (recipient = R9's user address). No receipt signature
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
