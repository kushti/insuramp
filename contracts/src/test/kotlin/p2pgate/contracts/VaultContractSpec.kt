package p2pgate.contracts

import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.util.BigIntegers
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import sigmastate.crypto.SigmaProtocolPrivateInput
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Test matrix from specs/vault-contract.md §7 (v2). Test numbering follows that section.
 * Convention: inputs are ordered [vaultBox, ...] so the vault is SELF. The release
 * paths C/C′ take the oracle box as a DATA INPUT (its script never executes — no
 * oracle signature in the tx) and additionally require the seller's
 * proveDlog(sellerKey) — the attestation alone must never direct funds. Payout
 * addresses are free on A/C/C′/D (key rotation): only the full collateral is
 * conserved. The driver proves/verifies SELF only, with the
 * data inputs attached to the unsigned transaction exactly as on-chain.
 *
 * Every FUNDED-box spend carries the explicit action byte in context var 0
 * (`ContractParams.ACTION_*`, added 2026-10-04); the PAYMENT_PROVEN box still
 * infers its path from the data input, since only two paths share that script.
 */
class VaultContractSpec {

    private val proofHeight = VaultFixture.PROOF_HEIGHT

    /** Seller-signed handoff record plus its signature and record id, as path B computes them. */
    private class SignedRecord(val fx: VaultFixture, val record: ByteArray) {
        val sig: Schnorr.Signature = Schnorr.sign(fx.sellerKey.w(), record, fx.sellerPk)
        val id: ByteArray get() = fx.recordId(record, sig)
        fun vars(): Map<Int, sigma.ast.EvaluatedValue<out sigma.ast.SType>> =
            fx.handoffVars(record, sig = sig)
    }

    // ------------------------------------------------------------- FUNDED box

    @Test
    fun `1 reclaim before timeoutHeight fails`() {
        val fx = VaultFixture()
        assertFalse(
            fx.verifySpend(
                fx.fundedTree, fx.fundedBox,
                inputs = listOf(fx.fundedBox, fx.oracleBox),
                outputs = listOf(fx.sellerOut()),
                height = fx.timeoutHeight - 1,
                vars = fx.actionVars(ContractParams.ACTION_RECLAIM),
                secrets = listOf(fx.sellerKey),
            ),
        )
    }

    @Test
    fun `2 reclaim after timeoutHeight by seller key passes`() {
        val fx = VaultFixture()
        assertTrue(
            fx.verifySpend(
                fx.fundedTree, fx.fundedBox,
                inputs = listOf(fx.fundedBox, fx.oracleBox),
                outputs = listOf(fx.sellerOut(), fx.changeOut()),
                height = fx.timeoutHeight + 1,
                vars = fx.actionVars(ContractParams.ACTION_RECLAIM),
                secrets = listOf(fx.sellerKey),
            ),
        )
    }

    @Test
    fun `3 reclaim by wrong key fails`() {
        val fx = VaultFixture()
        assertFalse(
            fx.verifySpend(
                fx.fundedTree, fx.fundedBox,
                inputs = listOf(fx.fundedBox, fx.oracleBox),
                outputs = listOf(fx.sellerOut()),
                height = fx.timeoutHeight + 1,
                vars = fx.actionVars(ContractParams.ACTION_RECLAIM),
                secrets = listOf(fx.buyerKey),
            ),
        )
    }

    @Test
    fun `4 open claim with valid seller-signed handoff record passes`() {
        val fx = VaultFixture()
        val sr = SignedRecord(fx, fx.handoffRecord())
        assertTrue(
            fx.verifySpend(
                fx.fundedTree, fx.fundedBox,
                inputs = listOf(fx.fundedBox),
                outputs = listOf(fx.provenOut(proofHeight, sr.id), fx.changeOut()),
                height = proofHeight,
                vars = sr.vars(),
            ),
        )
    }

    @Test
    fun `5 record signed under the buyer key instead of the seller key fails`() {
        // The v2 gate is the SELLER half ONLY: signature material that would have been
        // a buyer-side signature (signed under R6's buyerPubKey) opens nothing — the
        // in-script challenge binds R5's seller key.
        val fx = VaultFixture()
        val sr = SignedRecord(fx, fx.handoffRecord())
        val wrongSig = Schnorr.sign(fx.buyerKey.w(), sr.record, fx.buyerPk)
        val wrongId = fx.recordId(sr.record, wrongSig)
        assertFalse(
            fx.verifySpend(
                fx.fundedTree, fx.fundedBox,
                inputs = listOf(fx.fundedBox),
                outputs = listOf(fx.provenOut(proofHeight, wrongId)),
                height = proofHeight,
                vars = fx.handoffVars(sr.record, signer = fx.buyerKey, pub = fx.buyerPk),
            ),
        )
    }

    @Test
    fun `6 tampered record byte in every field region fails`() {
        // One flipped bit per field region of the 52-byte record (magic, version,
        // dealId, amount, currency, timestamp), honest seller half otherwise.
        // Rejection comes from the in-script challenge binding the carried
        // record bytes (the dealId flip additionally fails the in-branch
        // record-dealId binding — the action byte names path B but says nothing
        // about the record, so a flipped dealId still enters path B and is
        // rejected there).
        val fx = VaultFixture()
        val sr = SignedRecord(fx, fx.handoffRecord())
        for (index in listOf(0, 4, 10, 40, 46, 50)) {
            assertFalse(
                fx.verifySpend(
                    fx.fundedTree, fx.fundedBox,
                    inputs = listOf(fx.fundedBox),
                    outputs = listOf(fx.provenOut(proofHeight, sr.id)),
                    height = proofHeight,
                    vars = sr.vars() + (1 to SigmaBridge.bytesConst(fx.flippedByte(sr.record, index))),
                ),
                "flipped record byte at index $index must be rejected",
            )
        }
    }

    // Tests 7–10 (stale/future record timestamps, tsMs var binding probes) were
    // removed 2026-10-04 with the in-script freshness window (owner decision:
    // dealId uniqueness + the sign-after-counting sequencing rule carry it).

    @Test
    fun `11 open claim with a record bound to a different deal fails`() {
        val fx = VaultFixture()
        val otherDealId = ByteArray(32) { (it + 99).toByte() }
        val sr = SignedRecord(fx, fx.handoffRecord(dealId = otherDealId)) // record carries another deal's dealId
        assertFalse(
            fx.verifySpend(
                fx.fundedTree, fx.fundedBox,
                inputs = listOf(fx.fundedBox),
                outputs = listOf(fx.provenOut(proofHeight, sr.id)),
                height = proofHeight,
                vars = sr.vars(),
            ),
        )
    }

    @Test
    fun `12 open claim draining tokens to a non-PAYMENT_PROVEN output fails`() {
        val fx = VaultFixture()
        val sr = SignedRecord(fx, fx.handoffRecord())
        assertFalse(
            fx.verifySpend(
                fx.fundedTree, fx.fundedBox,
                inputs = listOf(fx.fundedBox),
                outputs = listOf(fx.sellerOut(amount = fx.dealAmount)),
                height = proofHeight,
                vars = sr.vars(),
            ),
        )
    }

    @Test
    fun `13 open claim with an oracle box among the inputs fails`() {
        // Path B involves no oracle, and an oracle box can no longer ride along as an
        // input: oracle.es pins the NFT reproduction at OUTPUTS(0) — the very slot
        // path B's PAYMENT_PROVEN box must occupy. Leg 1: the vault's path B alone
        // still verifies against this shape; leg 2: the oracle box's own script rejects
        // it (its reproduction sits at OUTPUTS(1) here), so the joint transaction can
        // never validate.
        val fx = VaultFixture()
        val sr = SignedRecord(fx, fx.handoffRecord())
        val outputs = listOf(fx.provenOut(proofHeight, sr.id), fx.oracleOut())
        assertTrue(
            fx.verifySpend(
                fx.fundedTree, fx.fundedBox,
                inputs = listOf(fx.fundedBox, fx.oracleBox),
                outputs = outputs,
                height = proofHeight,
                vars = sr.vars(),
            ),
        )
        assertFalse(
            fx.verifySpend(
                fx.oracleTree, fx.oracleBox, // the oracle box's own script
                inputs = listOf(fx.oracleBox, fx.fundedBox), // oracle at index 0 so it is SELF
                outputs = outputs,
                height = fx.creationHeight,
                secrets = listOf(fx.oracleKey),
            ),
        )
    }

    @Test
    fun `14 PAYMENT_PROVEN output with wrong proofHeight R7 fails`() {
        // R7 of the proven box is the plain Long proofHeight; an output recording a
        // different height is rejected.
        val fx = VaultFixture()
        val sr = SignedRecord(fx, fx.handoffRecord())
        assertFalse(
            fx.verifySpend(
                fx.fundedTree, fx.fundedBox,
                inputs = listOf(fx.fundedBox),
                outputs = listOf(fx.provenOut(proofHeight + 1, sr.id)),
                height = proofHeight,
                vars = sr.vars(),
            ),
        )
    }

    @Test
    fun `15 PAYMENT_PROVEN output with a wrong R8 record id fails`() {
        // R8 must equal the in-script blake2b256 of the carried signature + record;
        // an id computed over different bytes is rejected even when every other check
        // passes.
        val fx = VaultFixture()
        val sr = SignedRecord(fx, fx.handoffRecord())
        assertFalse(
            fx.verifySpend(
                fx.fundedTree, fx.fundedBox,
                inputs = listOf(fx.fundedBox),
                outputs = listOf(fx.provenOut(proofHeight, ByteArray(32) { 42 })),
                height = proofHeight,
                vars = sr.vars(),
            ),
        )
    }

    @Test
    fun `16 release from FUNDED with the oracle data input and seller signature passes`() {
        // v2 path C: the oracle singleton box as a DATA INPUT (NFT == R7, R4 the
        // 32-byte dealId attestation payload) — no context vars, no oracle
        // signature. The seller co-signs proveDlog(sellerKey) (the attestation
        // alone must never direct funds) and the payout sits at OUTPUTS(0),
        // paid in full.
        val fx = VaultFixture()
        assertTrue(
            fx.verifySpend(
                fx.fundedTree, fx.fundedBox,
                inputs = listOf(fx.fundedBox),
                dataInputs = listOf(fx.oracleDataBox()),
                outputs = listOf(fx.sellerOut()),
                height = proofHeight,
                vars = fx.actionVars(ContractParams.ACTION_RELEASE),
                secrets = listOf(fx.sellerKey),
            ),
        )
    }

    @Test
    fun `17 release from FUNDED without the oracle data input fails`() {
        // Path C reads CONTEXT.dataInputs(0) — with no data input attached the
        // script throws during reduction and the spend is rejected.
        val fx = VaultFixture()
        assertFalse(
            fx.verifySpend(
                fx.fundedTree, fx.fundedBox,
                inputs = listOf(fx.fundedBox),
                outputs = listOf(fx.sellerOut()),
                height = proofHeight,
                vars = fx.actionVars(ContractParams.ACTION_RELEASE),
                secrets = listOf(fx.sellerKey),
            ),
        )
    }

    @Test
    fun `18 release from FUNDED with the oracle box as full input but NOT data input fails`() {
        // The inversion of the data-input design: carrying the oracle box among
        // the INPUTS does not help — path C never looks at INPUTS, and with no
        // data input attached dataInputs(0) throws. (As a full input the box's
        // own script would also demand the oracle signature and pin its
        // reproduction at OUTPUTS(0) — the vault payout slot — so the joint
        // spend can never validate either.)
        val fx = VaultFixture()
        assertFalse(
            fx.verifySpend(
                fx.fundedTree, fx.fundedBox,
                inputs = listOf(fx.fundedBox, fx.oracleDataBox()),
                outputs = listOf(fx.sellerOut()),
                height = proofHeight,
                vars = fx.actionVars(ContractParams.ACTION_RELEASE),
                secrets = listOf(fx.sellerKey),
            ),
        )
    }

    @Test
    fun `19 release from FUNDED with a data input carrying a different NFT fails`() {
        // The payload is this deal's dealId; only the token id differs — the
        // data-input NFT pin (== R7) must reject it.
        val fx = VaultFixture()
        assertFalse(
            fx.verifySpend(
                fx.fundedTree, fx.fundedBox,
                inputs = listOf(fx.fundedBox),
                dataInputs = listOf(fx.wrongNftBox),
                outputs = listOf(fx.sellerOut()),
                height = proofHeight,
                vars = fx.actionVars(ContractParams.ACTION_RELEASE),
                secrets = listOf(fx.sellerKey),
            ),
        )
    }

    @Test
    fun `20 release from FUNDED with a foreign-script box carrying the NFT and payload passes`() {
        // HONEST SEMANTIC DOCUMENTATION of the data-input design: a data input's
        // script NEVER executes, so the vault cannot require oracle.es to govern
        // the attestation box — ANY box carrying the pinned NFT id as tokens(0)
        // and this deal's dealId in R4 releases the vault. NFT custody alone
        // is the phase-1 trust root: whoever can mint/hold a box with the oracle
        // NFT can attest. (Phase 2 replaces this with the guard-set threshold.)
        val fx = VaultFixture()
        assertTrue(
            fx.verifySpend(
                fx.fundedTree, fx.fundedBox,
                inputs = listOf(fx.fundedBox),
                dataInputs = listOf(fx.foreignOracleBox),
                outputs = listOf(fx.sellerOut()),
                height = proofHeight,
                vars = fx.actionVars(ContractParams.ACTION_RELEASE),
                secrets = listOf(fx.sellerKey),
            ),
        )
    }

    @Test
    fun `21 release from FUNDED paying collateral to a non-seller address passes with the seller signature`() {
        // The payee is deliberately unpinned (key rotation): the seller's
        // proveDlog(sellerKey) authorizes the spend, OUTPUTS(0) may pay ANY
        // address as long as the full collateral is conserved.
        val fx = VaultFixture()
        assertTrue(
            fx.verifySpend(
                fx.fundedTree, fx.fundedBox,
                inputs = listOf(fx.fundedBox),
                dataInputs = listOf(fx.oracleDataBox()),
                outputs = listOf(fx.buyerOut()), // any address, not the R5 key's
                height = proofHeight,
                vars = fx.actionVars(ContractParams.ACTION_RELEASE),
                secrets = listOf(fx.sellerKey),
            ),
        )
    }

    // Tests 22 (tampered amount) and 23 (tampered recipient) were removed
    // 2026-09-21 with the dealId-only payload: the release path no longer
    // checks amount/recipient fields (there are none).

    @Test
    fun `24 release from FUNDED with attestation dealId different from R4 fails`() {
        val fx = VaultFixture()
        val otherDealId = ByteArray(32) { (it + 99).toByte() }
        assertFalse(
            fx.verifySpend(
                fx.fundedTree, fx.fundedBox,
                inputs = listOf(fx.fundedBox),
                dataInputs = listOf(fx.oracleDataBox(payload = fx.paymentPayload(dealId = otherDealId))),
                outputs = listOf(fx.sellerOut()),
                height = proofHeight,
                vars = fx.actionVars(ContractParams.ACTION_RELEASE),
                secrets = listOf(fx.sellerKey),
            ),
        )
    }

    // Test 25 (srcTxId unbound passes) was removed 2026-09-21 with the
    // dealId-only payload: srcTxId/srcHeight/srcTime left the payload entirely,
    // so there is nothing unbound to demonstrate.

    // ------------------------------------------------------------- PAYMENT_PROVEN box

    @Test
    fun `26 release from PAYMENT_PROVEN with the oracle data input and seller signature passes`() {
        val fx = VaultFixture()
        val box = fx.provenBox(proofHeight)
        assertTrue(
            fx.verifySpend(
                fx.provenTree, box,
                inputs = listOf(box),
                dataInputs = listOf(fx.oracleDataBox()),
                outputs = listOf(fx.sellerOut()),
                height = proofHeight + 1,
                vars = fx.actionVars(ContractParams.ACTION_CONTEST),
                secrets = listOf(fx.sellerKey),
            ),
        )
    }

    @Test
    fun `27 release from PAYMENT_PROVEN without the oracle data input fails`() {
        val fx = VaultFixture()
        val box = fx.provenBox(proofHeight)
        assertFalse(
            fx.verifySpend(
                fx.provenTree, box,
                inputs = listOf(box),
                outputs = listOf(fx.sellerOut()),
                height = proofHeight + 1,
                vars = fx.actionVars(ContractParams.ACTION_CONTEST),
                secrets = listOf(fx.sellerKey),
            ),
        )
    }

    @Test
    fun `28 release from PAYMENT_PROVEN with the oracle box as full input but NOT data input fails`() {
        // Mirror of test 18 for path C′: an oracle box among the INPUTS does not
        // satisfy the data-input check (and as a full input its own script would
        // fight the seller-payout slot at OUTPUTS(0)).
        val fx = VaultFixture()
        val box = fx.provenBox(proofHeight)
        assertFalse(
            fx.verifySpend(
                fx.provenTree, box,
                inputs = listOf(box, fx.oracleDataBox()),
                outputs = listOf(fx.sellerOut()),
                height = proofHeight + 1,
                vars = fx.actionVars(ContractParams.ACTION_CONTEST),
                secrets = listOf(fx.sellerKey),
            ),
        )
    }

    // Test 29 (tampered amount, C′) was removed 2026-09-21 with the dealId-only
    // payload: path C′ no longer checks amount/recipient fields (there are none).

    @Test
    fun `30 release from PAYMENT_PROVEN with attestation dealId different from R4 fails`() {
        val fx = VaultFixture()
        val box = fx.provenBox(proofHeight)
        val otherDealId = ByteArray(32) { (it + 99).toByte() }
        assertFalse(
            fx.verifySpend(
                fx.provenTree, box,
                inputs = listOf(box),
                dataInputs = listOf(fx.oracleDataBox(payload = fx.paymentPayload(dealId = otherDealId))),
                outputs = listOf(fx.sellerOut()),
                height = proofHeight + 1,
                vars = fx.actionVars(ContractParams.ACTION_CONTEST),
                secrets = listOf(fx.sellerKey),
            ),
        )
    }

    @Test
    fun `31 oracle-attested release counters a live claim`() {
        // The v2 residual fix (spec §4.2): the buyer opens a claim with a VALID record
        // (leg 1, path B); the seller resolves it with the oracle attestation plus the
        // seller signature (leg 2, path C′) — no buyer receipt signature exists to
        // withhold, and the attestation alone never directs funds.
        val fx = VaultFixture()
        val sr = SignedRecord(fx, fx.handoffRecord())
        assertTrue(
            fx.verifySpend(
                fx.fundedTree, fx.fundedBox,
                inputs = listOf(fx.fundedBox),
                outputs = listOf(fx.provenOut(proofHeight, sr.id), fx.changeOut()),
                height = proofHeight,
                vars = sr.vars(),
            ),
        )
        val box = fx.provenBox(proofHeight, sr.id)
        assertTrue(
            fx.verifySpend(
                fx.provenTree, box,
                inputs = listOf(box),
                dataInputs = listOf(fx.oracleDataBox()),
                outputs = listOf(fx.sellerOut()),
                height = proofHeight + 1,
                vars = fx.actionVars(ContractParams.ACTION_CONTEST),
                secrets = listOf(fx.sellerKey),
            ),
        )
    }

    @Test
    fun `32 claim before maturation fails`() {
        val fx = VaultFixture()
        val box = fx.provenBox(proofHeight)
        assertFalse(
            fx.verifySpend(
                fx.provenTree, box,
                inputs = listOf(box),
                outputs = listOf(fx.buyerOut()),
                height = proofHeight + ContractParams.CLAIM_MATURATION_BLOCKS - 1,
                vars = fx.actionVars(ContractParams.ACTION_CLAIM_PAYOUT),
                secrets = listOf<SigmaProtocolPrivateInput<*>>(fx.buyerKey),
            ),
        )
    }

    @Test
    fun `33 claim after maturation by buyer key passes`() {
        val fx = VaultFixture()
        val box = fx.provenBox(proofHeight)
        assertTrue(
            fx.verifySpend(
                fx.provenTree, box,
                inputs = listOf(box),
                outputs = listOf(fx.buyerOut(), fx.changeOut()),
                height = proofHeight + ContractParams.CLAIM_MATURATION_BLOCKS + 1,
                vars = fx.actionVars(ContractParams.ACTION_CLAIM_PAYOUT),
                secrets = listOf<SigmaProtocolPrivateInput<*>>(fx.buyerKey),
            ),
        )
    }

    @Test
    fun `34 claim by wrong key fails`() {
        val fx = VaultFixture()
        val box = fx.provenBox(proofHeight)
        assertFalse(
            fx.verifySpend(
                fx.provenTree, box,
                inputs = listOf(box),
                outputs = listOf(fx.buyerOut()),
                height = proofHeight + ContractParams.CLAIM_MATURATION_BLOCKS + 1,
                vars = fx.actionVars(ContractParams.ACTION_CLAIM_PAYOUT),
                secrets = listOf<SigmaProtocolPrivateInput<*>>(fx.sellerKey),
            ),
        )
    }

    // ------------------------------------------------------------- cross-deal replay (36-37)

    @Test
    fun `36 oracle attestation from deal X applied to vault of deal Y fails`() {
        val fx = VaultFixture()
        val dealX = VaultFixture()
        assertFalse(
            fx.verifySpend(
                fx.fundedTree, fx.fundedBox,
                inputs = listOf(fx.fundedBox),
                dataInputs = listOf(fx.oracleDataBox(payload = dealX.paymentPayload(dealId = ByteArray(32) { 42 }))), // dealX's dealId
                outputs = listOf(fx.sellerOut()),
                height = proofHeight,
                vars = fx.actionVars(ContractParams.ACTION_RELEASE),
                secrets = listOf(fx.sellerKey),
            ),
        )
    }

    @Test
    fun `37 handoff record from deal X applied to vault of deal Y fails`() {
        // Cross-deal replay on the claim path: a record seller-signed for deal X is
        // rejected by deal Y's vault (the dealId discriminator diverts it, and the
        // challenge binds the carried record bytes).
        val fx = VaultFixture() // deal Y vault
        val dealX = VaultFixture() // deal X record
        val sr = SignedRecord(dealX, dealX.handoffRecord())
        assertFalse(
            fx.verifySpend(
                fx.fundedTree, fx.fundedBox,
                inputs = listOf(fx.fundedBox),
                outputs = listOf(fx.provenOut(proofHeight, sr.id)),
                height = proofHeight,
                vars = fx.handoffVars(sr.record, sig = sr.sig),
            ),
        )
    }

    // ------------------------------------------------------------- adversarial Schnorr inputs (38-41)

    @Test
    fun `38 signature over tampered handoff record fails`() {
        // Honest (a, z) from Schnorr.sign over the correct record, but the record
        // context var has one flipped bit: the in-script challenge e is recomputed over
        // the var bytes, so the group equation no longer holds.
        val fx = VaultFixture()
        val sr = SignedRecord(fx, fx.handoffRecord())
        assertFalse(
            fx.verifySpend(
                fx.fundedTree, fx.fundedBox,
                inputs = listOf(fx.fundedBox),
                outputs = listOf(fx.provenOut(proofHeight, sr.id)),
                height = proofHeight,
                vars = fx.handoffVars(
                    fx.flippedByte(sr.record), sig = sr.sig,
                    aOverride = sr.sig.a, zOverride = sr.sig.z,
                ),
            ),
        )
    }

    @Test
    fun `39 z equal to the curve group order fails`() {
        // z = secp256k1 n as 32 big-endian bytes; its top bit is set, so the contract's
        // signed two's-complement byteArrayToBigInt reads it as a negative scalar that is
        // not the signer's response — the group equation fails.
        val fx = VaultFixture()
        val sr = SignedRecord(fx, fx.handoffRecord())
        val order = CustomNamedCurves.getByName("secp256k1").n
        val zBad = BigIntegers.asUnsignedByteArray(32, order)
        val tamperedId = fx.recordId(sr.record, Schnorr.Signature(sr.sig.a, zBad))
        assertFalse(
            fx.verifySpend(
                fx.fundedTree, fx.fundedBox,
                inputs = listOf(fx.fundedBox),
                outputs = listOf(fx.provenOut(proofHeight, tamperedId)),
                height = proofHeight,
                vars = sr.vars() + (3 to SigmaBridge.bytesConst(zBad)),
            ),
        )
    }

    @Test
    fun `40 z with the sign bit set fails`() {
        // Honest z (always positive — the signer grinds to 254 bits) with 0x80 OR-ed into
        // the first byte: negative under byteArrayToBigInt, so g^z no longer equals
        // a * Y^e for the honest a and e.
        val fx = VaultFixture()
        val sr = SignedRecord(fx, fx.handoffRecord())
        val zNeg = sr.sig.z.copyOf().also { it[0] = (it[0].toInt() or 0x80).toByte() }
        val tamperedId = fx.recordId(sr.record, Schnorr.Signature(sr.sig.a, zNeg))
        assertFalse(
            fx.verifySpend(
                fx.fundedTree, fx.fundedBox,
                inputs = listOf(fx.fundedBox),
                outputs = listOf(fx.provenOut(proofHeight, tamperedId)),
                height = proofHeight,
                vars = sr.vars() + (3 to SigmaBridge.bytesConst(zNeg)),
            ),
        )
    }

    @Test
    fun `41 nonce point that is not on the curve fails`() {
        // a replaced with 33 bytes that are not a valid compressed secp256k1 point;
        // sigma 6's decodePoint throws during evaluation, and the spend is rejected
        // there — before the Schnorr equation is ever evaluated.
        val fx = VaultFixture()
        val sr = SignedRecord(fx, fx.handoffRecord())
        val badNonce = ByteArray(33) { 0xff.toByte() }
        val badId = fx.recordId(sr.record, Schnorr.Signature(badNonce, sr.sig.z))
        assertFalse(
            fx.verifySpend(
                fx.fundedTree, fx.fundedBox,
                inputs = listOf(fx.fundedBox),
                outputs = listOf(fx.provenOut(proofHeight, badId)),
                height = proofHeight,
                vars = sr.vars() + (2 to SigmaBridge.bytesConst(badNonce)),
            ),
        )
    }

    // ------------------------------------------------------------- R7 (42-44)

    @Test
    fun `42 handoff record signed by a fresh random key fails path B`() {
        // The v2 gate is the SELLER half ONLY: a record signed by a key that pins
        // nothing in the vault (neither R5 nor R6) opens nothing — the in-script
        // challenge binds R5's seller key, and a stray key's signature fails it.
        val fx = VaultFixture()
        val stray = SigmaBridge.dlogRandom() // only to borrow a distinct valid point
        val strayPk = SigmaBridge.ecpEncoded(SigmaBridge.ecp(stray), true)
        val record = fx.handoffRecord()
        val straySig = Schnorr.sign(stray.w(), record, strayPk)
        assertFalse(
            fx.verifySpend(
                fx.fundedTree, fx.fundedBox,
                inputs = listOf(fx.fundedBox),
                outputs = listOf(fx.provenOut(proofHeight, fx.recordId(record, straySig))),
                height = proofHeight,
                vars = fx.handoffVars(record, signer = stray, pub = strayPk),
            ),
        )
    }

    @Test
    fun `43 R7 with a wrong oracleNftId fails path C`() {
        // The release path's data-input check compares R7 against the data
        // input's tokens(0) id; a vault pinned to a different NFT id rejects the
        // genuine oracle box.
        val fx = VaultFixture(r7OracleNftId = ByteArray(32) { (it * 11 + 2).toByte() })
        assertFalse(
            fx.verifySpend(
                fx.fundedTree, fx.fundedBox,
                inputs = listOf(fx.fundedBox),
                dataInputs = listOf(fx.oracleDataBox()),
                outputs = listOf(fx.sellerOut()),
                height = proofHeight,
                vars = fx.actionVars(ContractParams.ACTION_RELEASE),
                secrets = listOf(fx.sellerKey),
            ),
        )
    }

    @Test
    fun `44 open claim passes with a wrong R7 oracleNftId`() {
        // Path B reads R5 (not R7), so a vault whose R7 names a different oracle
        // NFT is still claimable with the honest seller-signed record. The wrong
        // pin propagates, though: path B copies R7 into the PROVEN box's R9
        // (since 2026-10-03), so the resulting box's contest path rejects the
        // genuine oracle — a deployment error poisons C′, never the claim.
        val fx = VaultFixture(r7OracleNftId = ByteArray(32) { (it * 11 + 2).toByte() })
        val sr = SignedRecord(fx, fx.handoffRecord())
        assertTrue(
            fx.verifySpend(
                fx.fundedTree, fx.fundedBox,
                inputs = listOf(fx.fundedBox),
                outputs = listOf(fx.provenOut(proofHeight, sr.id), fx.changeOut()),
                height = proofHeight,
                vars = sr.vars(),
            ),
        )
    }

    // ------------------------------------------------------------- payout freedom (46-48)

    @Test
    fun `46 release from FUNDED without the seller signature fails`() {
        // The release path is sigmaProp(attestation conditions) && proveDlog(sellerKey):
        // the attestation alone must NEVER direct funds — without the seller's
        // signature anyone could pay themselves the collateral the moment an
        // attestation box exists on-chain.
        val fx = VaultFixture()
        assertFalse(
            fx.verifySpend(
                fx.fundedTree, fx.fundedBox,
                inputs = listOf(fx.fundedBox),
                dataInputs = listOf(fx.oracleDataBox()),
                outputs = listOf(fx.sellerOut()),
                height = proofHeight,
                vars = fx.actionVars(ContractParams.ACTION_RELEASE),
            ),
        )
    }

    @Test
    fun `47 claim payout to an arbitrary buyer-chosen address passes`() {
        // Path D mirrors the release: the buyer's proveDlog(buyerKey) authorizes
        // the spend, so the payout may go to any address (key rotation).
        val fx = VaultFixture()
        val box = fx.provenBox(proofHeight)
        assertTrue(
            fx.verifySpend(
                fx.provenTree, box,
                inputs = listOf(box),
                outputs = listOf(fx.sellerOut()), // any address, not the R6 key's
                height = proofHeight + ContractParams.CLAIM_MATURATION_BLOCKS + 1,
                vars = fx.actionVars(ContractParams.ACTION_CLAIM_PAYOUT),
                secrets = listOf<SigmaProtocolPrivateInput<*>>(fx.buyerKey),
            ),
        )
    }

    @Test
    fun `48 reclaim paying to an arbitrary seller-chosen address passes`() {
        // Path A: the seller's proveDlog(sellerKey) authorizes the spend, so the
        // reclaim may pay any address (key rotation for the next iteration).
        val fx = VaultFixture()
        assertTrue(
            fx.verifySpend(
                fx.fundedTree, fx.fundedBox,
                inputs = listOf(fx.fundedBox, fx.oracleBox),
                outputs = listOf(fx.buyerOut()), // any address, not the R5 key's
                height = fx.timeoutHeight + 1,
                vars = fx.actionVars(ContractParams.ACTION_RECLAIM),
                secrets = listOf(fx.sellerKey),
            ),
        )
    }

    // ------------------------------------------------------------- branch reachability (49-52)

    @Test
    fun `49 open claim after the reclaim timeout passes`() {
        // Late disputes must stay possible. Under the pre-2026-10-04 discriminator
        // this was a branch-ORDER hazard: the PAYMENT_PROVEN output also satisfies
        // the payout conservation check, so a height-first discriminator would
        // swallow every post-timeout claim-open into path A (proveDlog(sellerKey)).
        // The explicit action byte removes the ordering question entirely — a
        // claim-open is a claim-open at any height — but the guarantee itself is
        // what this test pins.
        val fx = VaultFixture()
        val lateHeight = fx.timeoutHeight + 10
        val sr = SignedRecord(fx, fx.handoffRecord())
        assertTrue(
            fx.verifySpend(
                fx.fundedTree, fx.fundedBox,
                inputs = listOf(fx.fundedBox),
                outputs = listOf(fx.provenOut(lateHeight, sr.id), fx.changeOut()),
                height = lateHeight,
                vars = sr.vars(),
            ),
        )
    }

    @Test
    fun `50 contest after maturation passes`() {
        // Path C′ stays reachable past CLAIM_MATURATION: the D/C′ discriminator
        // is the action byte, not height (and not the data input since
        // 2026-10-03), so an attested-but-slow honest seller must always be
        // able to counter a false claim.
        val fx = VaultFixture()
        val box = fx.provenBox(proofHeight)
        assertTrue(
            fx.verifySpend(
                fx.provenTree, box,
                inputs = listOf(box),
                dataInputs = listOf(fx.oracleDataBox()),
                outputs = listOf(fx.sellerOut()),
                height = proofHeight + ContractParams.CLAIM_MATURATION_BLOCKS + 10,
                vars = fx.actionVars(ContractParams.ACTION_CONTEST),
                secrets = listOf(fx.sellerKey),
            ),
        )
    }

    @Test
    fun `51 release after the reclaim timeout still requires the attestation`() {
        // CHANGED 2026-10-04 (explicit action byte). Before, a post-timeout
        // release tx (no vars, full-collateral payout) landed in the path A branch
        // and the attestation went unchecked — seller-signed and payout-identical
        // to a reclaim, so the two were indistinguishable. Naming the path removes
        // that collapse: a release is always a release, at any height.
        val fx = VaultFixture()
        assertTrue(
            fx.verifySpend(
                fx.fundedTree, fx.fundedBox,
                inputs = listOf(fx.fundedBox),
                dataInputs = listOf(fx.oracleDataBox()),
                outputs = listOf(fx.sellerOut()),
                height = fx.timeoutHeight + 10,
                vars = fx.actionVars(ContractParams.ACTION_RELEASE),
                secrets = listOf(fx.sellerKey),
            ),
        )
        // And the same tx without the attestation no longer sneaks through as a
        // reclaim — pre-2026-10-04 this validated, because with no vars the old
        // discriminator fell through to path A.
        assertFalse(
            fx.verifySpend(
                fx.fundedTree, fx.fundedBox,
                inputs = listOf(fx.fundedBox),
                outputs = listOf(fx.sellerOut()),
                height = fx.timeoutHeight + 10,
                vars = fx.actionVars(ContractParams.ACTION_RELEASE),
                secrets = listOf(fx.sellerKey),
            ),
            "post-timeout release without the attestation must be rejected",
        )
    }

    // ------------------------------------------------------------- action discriminator (53-56)

    @Test
    fun `53 a spend with no action var fails`() {
        // Var 0 is mandatory: `getVar[Byte](0).get` throws during reduction, so a
        // FUNDED box cannot be spent by a tx that declines to name a path. The
        // contract has no default path.
        val fx = VaultFixture()
        assertFalse(
            fx.verifySpend(
                fx.fundedTree, fx.fundedBox,
                inputs = listOf(fx.fundedBox),
                outputs = listOf(fx.sellerOut()),
                height = fx.timeoutHeight + 1,
                secrets = listOf(fx.sellerKey),
            ),
            "a spend with no action byte must be rejected",
        )
    }

    @Test
    fun `54 an unrecognized action code fails`() {
        // Fail-closed: a garbage action byte is not read as "release, by
        // elimination", it is rejected. Pre-2026-10-04 the residual branch meant
        // every unmatched shape was *attempted* as a release.
        val fx = VaultFixture()
        assertFalse(
            fx.verifySpend(
                fx.fundedTree, fx.fundedBox,
                inputs = listOf(fx.fundedBox),
                dataInputs = listOf(fx.oracleDataBox()),
                outputs = listOf(fx.sellerOut()),
                height = proofHeight,
                vars = fx.unknownActionVars(),
                secrets = listOf(fx.sellerKey),
            ),
            "an unknown action code must be rejected even with a valid attestation",
        )
    }

    @Test
    fun `55 a carried handoff record does not excuse the reclaim timeout`() {
        // Carrying a valid seller-signed record changes nothing for the reclaim
        // branch: it reads HEIGHT and the payout conservation check, not the
        // record. Naming RECLAIM before the timeout must fail even with a
        // well-formed claim tx's vars riding along.
        //
        // (After the timeout the same shape IS a reclaim — seller-signed,
        // full-collateral payout — which is why this test pins the height, not
        // the var set: test 2 already covers the post-timeout side.)
        val fx = VaultFixture()
        val sr = SignedRecord(fx, fx.handoffRecord())
        assertFalse(
            fx.verifySpend(
                fx.fundedTree, fx.fundedBox,
                inputs = listOf(fx.fundedBox),
                outputs = listOf(fx.sellerOut(), fx.changeOut()),
                height = fx.timeoutHeight - 1,
                vars = fx.actionVars(ContractParams.ACTION_RECLAIM).plus(sr.vars().filterKeys { it > 0 }),
                secrets = listOf(fx.sellerKey),
            ),
            "a carried record must not bypass the reclaim timeout",
        )
    }

    @Test
    fun `56 the claim action cannot be used to take the collateral`() {
        // Mirror of test 55, and the important one for the buyer's protection:
        // a well-formed seller-signed claim tx pays out to OUTPUTS(0), so under
        // the OLD discriminator (var-0 presence) the seller's proveDlog was the
        // only thing standing between "valid record" and "seller takes the
        // collateral". Under the new one ACTION_CLAIM can only ever produce the
        // PAYMENT_PROVEN box, so there is no payout shape left to redirect.
        val fx = VaultFixture()
        val sr = SignedRecord(fx, fx.handoffRecord())
        assertFalse(
            fx.verifySpend(
                fx.fundedTree, fx.fundedBox,
                inputs = listOf(fx.fundedBox),
                outputs = listOf(fx.sellerOut(), fx.changeOut()),
                height = proofHeight,
                vars = sr.vars(),
                secrets = listOf(fx.sellerKey),
            ),
            "ACTION_CLAIM paying the seller must be rejected — no secret material needed",
        )
    }

    @Test
    fun `52 claim payout with a stray data input fails`() {
        // A claim-payout tx names ACTION_CLAIM_PAYOUT, and the D branch rejects
        // any spend carrying a data input — a payout never has one (a contest
        // always does), so the stray box fails the spend rather than riding
        // along inert. (Honest claim txs carry no data inputs; ClaimTxBuilder
        // attaches none.)
        val fx = VaultFixture()
        val box = fx.provenBox(proofHeight)
        assertFalse(
            fx.verifySpend(
                fx.provenTree, box,
                inputs = listOf(box),
                dataInputs = listOf(fx.oracleDataBox()),
                outputs = listOf(fx.buyerOut()),
                height = proofHeight + ContractParams.CLAIM_MATURATION_BLOCKS + 1,
                vars = fx.actionVars(ContractParams.ACTION_CLAIM_PAYOUT),
                secrets = listOf<SigmaProtocolPrivateInput<*>>(fx.buyerKey),
            ),
        )
    }

    // ------------------------------------------------------------- PAYMENT_PROVEN action byte (57-59)

    @Test
    fun `57 a proven spend with no action var fails`() {
        // Mirror of test 53 for the PROVEN box (the action byte is mandatory
        // there since 2026-10-03): a fully mature, well-formed payout without
        // the var dies in proof reduction on `getVar[Byte](0).get`.
        val fx = VaultFixture()
        val box = fx.provenBox(proofHeight)
        assertFalse(
            fx.verifySpend(
                fx.provenTree, box,
                inputs = listOf(box),
                outputs = listOf(fx.buyerOut()),
                height = proofHeight + ContractParams.CLAIM_MATURATION_BLOCKS + 1,
                secrets = listOf<SigmaProtocolPrivateInput<*>>(fx.buyerKey),
            ),
        )
    }

    @Test
    fun `58 an unrecognized action code on the proven box fails`() {
        // Mirror of test 54: the fail-closed tail catches anything but
        // ACTION_CLAIM_PAYOUT / ACTION_CONTEST.
        val fx = VaultFixture()
        val box = fx.provenBox(proofHeight)
        assertFalse(
            fx.verifySpend(
                fx.provenTree, box,
                inputs = listOf(box),
                outputs = listOf(fx.buyerOut()),
                height = proofHeight + ContractParams.CLAIM_MATURATION_BLOCKS + 1,
                vars = fx.unknownActionVars(),
                secrets = listOf<SigmaProtocolPrivateInput<*>>(fx.buyerKey),
            ),
        )
    }

    @Test
    fun `59 a payout naming the contest action fails`() {
        // A payout-shaped spend (mature, buyer-signed, no data input) that names
        // ACTION_CONTEST is routed to the C′ branch, which reads
        // `CONTEXT.dataInputs(0)` — absent here, so the reduction throws and the
        // spend is rejected. The action byte names the path; the shape cannot
        // talk its way into another one.
        val fx = VaultFixture()
        val box = fx.provenBox(proofHeight)
        assertFalse(
            fx.verifySpend(
                fx.provenTree, box,
                inputs = listOf(box),
                outputs = listOf(fx.buyerOut()),
                height = proofHeight + ContractParams.CLAIM_MATURATION_BLOCKS + 1,
                vars = fx.actionVars(ContractParams.ACTION_CONTEST),
                secrets = listOf<SigmaProtocolPrivateInput<*>>(fx.buyerKey),
            ),
        )
    }

    // ------------------------------------------------------------- per-box NFT pin (60-61, 2026-10-03)

    @Test
    fun `60 claim-open with a wrong R9 oracleNftId in the proven output fails`() {
        // Path B requires OUTPUTS(0).R9 == SELF.R7: the oracle NFT pin is copied
        // per-box (the PROVEN script's compile-time pin is gone since
        // 2026-10-03), so a proven output pinned to a different NFT is rejected.
        val fx = VaultFixture()
        val sr = SignedRecord(fx, fx.handoffRecord())
        assertFalse(
            fx.verifySpend(
                fx.fundedTree, fx.fundedBox,
                inputs = listOf(fx.fundedBox),
                outputs = listOf(fx.provenOut(proofHeight, sr.id, oracleNft = fx.wrongNftId), fx.changeOut()),
                height = proofHeight,
                vars = sr.vars(),
            ),
        )
    }

    @Test
    fun `61 contest from a proven box with a wrong R9 oracleNftId fails`() {
        // Mirror of test 43 for the PROVEN box: its NFT pin is R9 now, so a
        // proven box pinned to a different NFT rejects the genuine oracle box.
        val fx = VaultFixture()
        val box = fx.provenBox(proofHeight, oracleNft = fx.wrongNftId)
        assertFalse(
            fx.verifySpend(
                fx.provenTree, box,
                inputs = listOf(box),
                dataInputs = listOf(fx.oracleDataBox()),
                outputs = listOf(fx.sellerOut()),
                height = proofHeight + 1,
                vars = fx.actionVars(ContractParams.ACTION_CONTEST),
                secrets = listOf(fx.sellerKey),
            ),
        )
    }

    // ------------------------------------------------------------- phase-2 readiness (45)

    @Test
    @Disabled(
        "Phase 2: swap oracleOk for a 2-of-3 GuardSign-style guard box (Rosen pattern) and " +
            "rerun the release tests (16-31) — the dealId-equality checks (24, 30) must " +
            "be untouched (specs/vault-contract.md §7).",
    )
    fun `45 phase-2 GuardSign oracle swap reruns the release tests`() {
    }
}
