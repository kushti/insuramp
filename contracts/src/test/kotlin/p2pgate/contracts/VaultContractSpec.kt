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
 * oracle signature in the tx); the driver proves/verifies SELF only, with the
 * data inputs attached to the unsigned transaction exactly as on-chain.
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
        // record bytes (and from the freshness binding for the timestamp region;
        // the dealId flip fails the in-branch record-dealId binding — the path
        // discriminator is only context-var presence, so a flipped dealId still
        // enters path B and is rejected there).
        val fx = VaultFixture()
        val sr = SignedRecord(fx, fx.handoffRecord())
        for (index in listOf(0, 4, 10, 40, 46, 50)) {
            assertFalse(
                fx.verifySpend(
                    fx.fundedTree, fx.fundedBox,
                    inputs = listOf(fx.fundedBox),
                    outputs = listOf(fx.provenOut(proofHeight, sr.id)),
                    height = proofHeight,
                    vars = sr.vars() + (0 to SigmaBridge.bytesConst(fx.flippedByte(sr.record, index))),
                ),
                "flipped record byte at index $index must be rejected",
            )
        }
    }

    @Test
    fun `7 open claim with stale record timestamp fails`() {
        val fx = VaultFixture()
        val staleSec = VaultFixture.NOW_MS / 1000 - 5 * 3600 // 5h old, bound is 4h
        val sr = SignedRecord(fx, fx.handoffRecord(tsSec = staleSec))
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
    fun `8 open claim with future record timestamp fails`() {
        val fx = VaultFixture()
        val futureSec = VaultFixture.NOW_MS / 1000 + 3600
        val sr = SignedRecord(fx, fx.handoffRecord(tsSec = futureSec))
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
    fun `9 fresh tsMs var bound to a stale record fails`() {
        // The 5h-old record alone is blocked by the 4h window (test 7); here the Long
        // timestamp var says NOW, so the window passes and rejection must come from the
        // binding longToByteArray(tsMs/1000).slice(4,8) == msg.slice(48,52) instead.
        val fx = VaultFixture()
        val staleSec = VaultFixture.NOW_MS / 1000 - 5 * 3600
        val sr = SignedRecord(fx, fx.handoffRecord(tsSec = staleSec))
        assertFalse(
            fx.verifySpend(
                fx.fundedTree, fx.fundedBox,
                inputs = listOf(fx.fundedBox),
                outputs = listOf(fx.provenOut(proofHeight, sr.id)),
                height = proofHeight,
                vars = sr.vars() + (3 to SigmaBridge.longVal(VaultFixture.NOW_MS) as sigma.ast.EvaluatedValue<out sigma.ast.SType>),
            ),
        )
    }

    @Test
    fun `10 in-window record paired with a stale tsMs var fails`() {
        // Reverse of test 9: the record bytes carry an in-window timestamp, but the
        // Long var claims the record is 5h old — the window check rejects it, and the
        // slice binding (var vs msg bytes 48..52) fails too.
        val fx = VaultFixture()
        val sr = SignedRecord(fx, fx.handoffRecord())
        assertFalse(
            fx.verifySpend(
                fx.fundedTree, fx.fundedBox,
                inputs = listOf(fx.fundedBox),
                outputs = listOf(fx.provenOut(proofHeight, sr.id)),
                height = proofHeight,
                vars = sr.vars() + (3 to SigmaBridge.longVal(VaultFixture.NOW_MS - 5 * 3600_000) as sigma.ast.EvaluatedValue<out sigma.ast.SType>),
            ),
        )
    }

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
    fun `16 release from FUNDED oracle-only passes`() {
        // v2 path C: the oracle singleton box as a DATA INPUT (NFT == R7, R4 the
        // 112-byte attestation payload) — no context vars, no oracle signature.
        // The seller payout sits at OUTPUTS(0), paid in full.
        val fx = VaultFixture()
        assertTrue(
            fx.verifySpend(
                fx.fundedTree, fx.fundedBox,
                inputs = listOf(fx.fundedBox),
                dataInputs = listOf(fx.oracleDataBox()),
                outputs = listOf(fx.sellerOut()),
                height = proofHeight,
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
            ),
        )
    }

    @Test
    fun `19 release from FUNDED with a data input carrying a different NFT fails`() {
        // The payload is field-valid; only the token id differs — the data-input
        // NFT pin (== R7) must reject it.
        val fx = VaultFixture()
        assertFalse(
            fx.verifySpend(
                fx.fundedTree, fx.fundedBox,
                inputs = listOf(fx.fundedBox),
                dataInputs = listOf(fx.wrongNftBox),
                outputs = listOf(fx.sellerOut()),
                height = proofHeight,
            ),
        )
    }

    @Test
    fun `20 release from FUNDED with a foreign-script box carrying the NFT and payload passes`() {
        // HONEST SEMANTIC DOCUMENTATION of the data-input design: a data input's
        // script NEVER executes, so the vault cannot require oracle.es to govern
        // the attestation box — ANY box carrying the pinned NFT id as tokens(0)
        // and a field-matching R4 payload releases the vault. NFT custody alone
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
            ),
        )
    }

    @Test
    fun `21 release from FUNDED paying collateral to a non-seller address fails`() {
        val fx = VaultFixture()
        assertFalse(
            fx.verifySpend(
                fx.fundedTree, fx.fundedBox,
                inputs = listOf(fx.fundedBox),
                dataInputs = listOf(fx.oracleDataBox()),
                outputs = listOf(fx.buyerOut()), // buyer's address, not the seller's
                height = proofHeight,
            ),
        )
    }

    @Test
    fun `22 release from FUNDED with digest amount different from R9 fails`() {
        val fx = VaultFixture()
        assertFalse(
            fx.verifySpend(
                fx.fundedTree, fx.fundedBox,
                inputs = listOf(fx.fundedBox),
                dataInputs = listOf(fx.oracleDataBox(payload = fx.paymentPayload(amount = fx.dealAmount + 1))),
                outputs = listOf(fx.sellerOut()),
                height = proofHeight,
            ),
        )
    }

    @Test
    fun `23 release from FUNDED with digest recipient different from R9 fails`() {
        val fx = VaultFixture()
        val otherRecipient = ByteArray(21) { (it * 23 + 7).toByte() }
        assertFalse(
            fx.verifySpend(
                fx.fundedTree, fx.fundedBox,
                inputs = listOf(fx.fundedBox),
                dataInputs = listOf(fx.oracleDataBox(payload = fx.paymentPayload(recipientAddr = otherRecipient))),
                outputs = listOf(fx.sellerOut()),
                height = proofHeight,
            ),
        )
    }

    @Test
    fun `24 release from FUNDED with digest dealId different from R4 fails`() {
        val fx = VaultFixture()
        val otherDealId = ByteArray(32) { (it + 99).toByte() }
        assertFalse(
            fx.verifySpend(
                fx.fundedTree, fx.fundedBox,
                inputs = listOf(fx.fundedBox),
                dataInputs = listOf(fx.oracleDataBox(payload = fx.paymentPayload(dealId = otherDealId))),
                outputs = listOf(fx.sellerOut()),
                height = proofHeight,
            ),
        )
    }

    @Test
    fun `25 digest srcTxId is not bound on-chain and passes`() {
        // Documentation of the checked-field set: only dealId, chainId, tokenId,
        // recipient and amount are pinned against R4/R9; srcTxId/srcHeight/srcTime ride
        // along for audit and the dashboard but are NOT checked in-script (R9 has no
        // field for them). The oracle's NFT custody is the trust root for those bytes.
        val fx = VaultFixture()
        val otherSrcTxId = ByteArray(32) { (it * 29 + 9).toByte() }
        assertTrue(
            fx.verifySpend(
                fx.fundedTree, fx.fundedBox,
                inputs = listOf(fx.fundedBox),
                dataInputs = listOf(fx.oracleDataBox(payload = fx.paymentPayload(srcTxId = otherSrcTxId))),
                outputs = listOf(fx.sellerOut()),
                height = proofHeight,
            ),
        )
    }

    // ------------------------------------------------------------- PAYMENT_PROVEN box

    @Test
    fun `26 release from PAYMENT_PROVEN oracle-only passes`() {
        val fx = VaultFixture()
        val box = fx.provenBox(proofHeight)
        assertTrue(
            fx.verifySpend(
                fx.provenTree, box,
                inputs = listOf(box),
                dataInputs = listOf(fx.oracleDataBox()),
                outputs = listOf(fx.sellerOut()),
                height = proofHeight + 1,
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
            ),
        )
    }

    @Test
    fun `29 release from PAYMENT_PROVEN with digest amount different from R9 fails`() {
        val fx = VaultFixture()
        val box = fx.provenBox(proofHeight)
        assertFalse(
            fx.verifySpend(
                fx.provenTree, box,
                inputs = listOf(box),
                dataInputs = listOf(fx.oracleDataBox(payload = fx.paymentPayload(amount = fx.dealAmount + 1))),
                outputs = listOf(fx.sellerOut()),
                height = proofHeight + 1,
            ),
        )
    }

    @Test
    fun `30 release from PAYMENT_PROVEN with digest dealId different from R4 fails`() {
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
            ),
        )
    }

    @Test
    fun `31 oracle-only release counters a live claim`() {
        // The v2 residual fix (spec §4.2): the buyer opens a claim with a VALID record
        // (leg 1, path B); the seller resolves it with the oracle attestation alone
        // (leg 2, path C′) — no buyer receipt signature exists to withhold.
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
                secrets = listOf<SigmaProtocolPrivateInput<*>>(fx.sellerKey),
            ),
        )
    }

    // ------------------------------------------------------------- cross-deal replay (36-37)

    @Test
    fun `36 oracle digest from deal X applied to vault of deal Y fails`() {
        val fx = VaultFixture()
        val dealX = VaultFixture()
        assertFalse(
            fx.verifySpend(
                fx.fundedTree, fx.fundedBox,
                inputs = listOf(fx.fundedBox),
                dataInputs = listOf(fx.oracleDataBox(payload = dealX.paymentPayload(dealId = ByteArray(32) { 42 }))), // dealX digest
                outputs = listOf(fx.sellerOut()),
                height = proofHeight,
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
                vars = sr.vars() + (2 to SigmaBridge.bytesConst(zBad)),
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
                vars = sr.vars() + (2 to SigmaBridge.bytesConst(zNeg)),
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
                vars = sr.vars() + (1 to SigmaBridge.bytesConst(badNonce)),
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
            ),
        )
    }

    @Test
    fun `44 open claim passes with a wrong R7 oracleNftId`() {
        // R7 pins the release path's oracle NFT only; path B reads R5 (not R7), so a
        // vault whose R7 names a different oracle NFT is still claimable with the
        // honest seller-signed record.
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

    // ------------------------------------------------------------- phase-2 readiness (45)

    @Test
    @Disabled(
        "Phase 2: swap oracleOk for a 2-of-3 GuardSign-style guard box (Rosen pattern) and " +
            "rerun the release tests (16-31) — the digest field checks (22-24, 29-30) must " +
            "be untouched (specs/vault-contract.md §7).",
    )
    fun `45 phase-2 GuardSign oracle swap reruns the release tests`() {
    }
}
