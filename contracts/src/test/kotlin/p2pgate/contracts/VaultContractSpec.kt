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
 * Convention: inputs are ordered [vaultBox, oracleBox...] so the vault is SELF. The
 * driver proves/verifies SELF only — co-input scripts (e.g. the oracle box's own
 * proveDlog(oracleKey)) are exercised in OracleContractSpec and in test 20's second leg.
 */
class VaultContractSpec {

    private val proofHeight = VaultFixture.PROOF_HEIGHT

    /** Courier-signed handoff record plus its signature and record id, as path B computes them. */
    private class SignedRecord(val fx: VaultFixture, val record: ByteArray) {
        val courierSig: Schnorr.Signature = Schnorr.sign(fx.courierKey.w(), record, fx.courierPk)
        val id: ByteArray get() = fx.recordId(record, courierSig)
        fun vars(): Map<Int, sigma.ast.EvaluatedValue<out sigma.ast.SType>> =
            fx.handoffVars(record, courierSig = courierSig)
    }

    // ------------------------------------------------------------- FUNDED box

    @Test
    fun `1 reclaim before timeoutHeight fails`() {
        val fx = VaultFixture(feeBps = 0)
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
        val fx = VaultFixture(feeBps = 0)
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
        val fx = VaultFixture(feeBps = 0)
        assertFalse(
            fx.verifySpend(
                fx.fundedTree, fx.fundedBox,
                inputs = listOf(fx.fundedBox, fx.oracleBox),
                outputs = listOf(fx.sellerOut()),
                height = fx.timeoutHeight + 1,
                secrets = listOf(fx.userKey),
            ),
        )
    }

    @Test
    fun `4 open claim with valid courier-signed handoff record passes`() {
        val fx = VaultFixture(feeBps = 0)
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
    fun `5 record signed under the user key instead of courierPubKey fails`() {
        // The v2 gate is the courier half ONLY: signature material that would have been
        // the user half of the old dual-signed record (signed under R6's userPubKey)
        // opens nothing — the in-script challenge binds R7's courier key.
        val fx = VaultFixture(feeBps = 0)
        val sr = SignedRecord(fx, fx.handoffRecord())
        val wrongSig = Schnorr.sign(fx.userKey.w(), sr.record, fx.userPk)
        val wrongId = fx.recordId(sr.record, wrongSig)
        assertFalse(
            fx.verifySpend(
                fx.fundedTree, fx.fundedBox,
                inputs = listOf(fx.fundedBox),
                outputs = listOf(fx.provenOut(proofHeight, wrongId)),
                height = proofHeight,
                vars = fx.handoffVars(sr.record, courierSigner = fx.userKey, courierPub = fx.userPk),
            ),
        )
    }

    @Test
    fun `6 tampered record byte in every field region fails`() {
        // One flipped bit per field region of the 84-byte record (magic, version,
        // dealId, amount, currency, timestamp, courierIdHash), honest courier half
        // otherwise. Rejection comes from the in-script challenge binding the carried
        // record bytes (and from the freshness binding for the timestamp region; the
        // dealId flip diverts the spend into the path-C branch, which rejects too).
        val fx = VaultFixture(feeBps = 0)
        val sr = SignedRecord(fx, fx.handoffRecord())
        for (index in listOf(0, 4, 10, 40, 46, 50, 70)) {
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
        val fx = VaultFixture(feeBps = 0)
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
        val fx = VaultFixture(feeBps = 0)
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
        val fx = VaultFixture(feeBps = 0)
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
        val fx = VaultFixture(feeBps = 0)
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
        val fx = VaultFixture(feeBps = 0)
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
        val fx = VaultFixture(feeBps = 0)
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
        val fx = VaultFixture(feeBps = 0)
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
    fun `14 PAYMENT_PROVEN output with wrong packed R7 fails`() {
        // R7 of the proven box is (proofHeight << 32) | feeBps; an output recording a
        // different height or fee is rejected.
        val fx = VaultFixture(feeBps = 0)
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
        // R8 must equal the in-script blake2b256 of the carried courier half + record;
        // an id computed over different bytes is rejected even when every other check
        // passes.
        val fx = VaultFixture(feeBps = 0)
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
        // v2 path C: oracle box as full input + digest fields vs R4/R9 — nothing else.
        // OUTPUTS(0) is the oracle box's pinned reproduction (oracle.es); the seller
        // payout sits at OUTPUTS(1).
        val fx = VaultFixture(feeBps = 0)
        assertTrue(
            fx.verifySpend(
                fx.fundedTree, fx.fundedBox,
                inputs = listOf(fx.fundedBox, fx.oracleBox),
                outputs = listOf(fx.oracleOut(), fx.sellerOut()),
                height = proofHeight,
                vars = fx.payloadVars(),
            ),
        )
    }

    @Test
    fun `17 release from FUNDED without the oracle box among inputs fails`() {
        val fx = VaultFixture(feeBps = 0)
        assertFalse(
            fx.verifySpend(
                fx.fundedTree, fx.fundedBox,
                inputs = listOf(fx.fundedBox),
                outputs = listOf(fx.sellerOut()),
                height = proofHeight,
                vars = fx.payloadVars(),
            ),
        )
    }

    @Test
    fun `18 release from FUNDED with the oracle box as mere data input fails`() {
        // A stale or foreign attestation must not be attachable without the oracle's
        // live consent: the oracle box must be a signing input, not a data input.
        val fx = VaultFixture(feeBps = 0)
        assertFalse(
            fx.verifySpend(
                fx.fundedTree, fx.fundedBox,
                inputs = listOf(fx.fundedBox),
                dataInputs = listOf(fx.oracleBox),
                outputs = listOf(fx.sellerOut()),
                height = proofHeight,
                vars = fx.payloadVars(),
            ),
        )
    }

    @Test
    fun `19 release from FUNDED with an oracle box carrying a different NFT fails`() {
        val fx = VaultFixture(feeBps = 0)
        assertFalse(
            fx.verifySpend(
                fx.fundedTree, fx.fundedBox,
                inputs = listOf(fx.fundedBox, fx.wrongNftBox),
                outputs = listOf(fx.sellerOut()),
                height = proofHeight,
                vars = fx.payloadVars(),
            ),
        )
    }

    @Test
    fun `20 release from FUNDED with a foreign-script oracle box fails on-chain`() {
        // The vault's oracleOk checks NFT presence only, so at the vault-script level a
        // foreign box carrying the NFT is accepted (leg 1 — with the release shape
        // otherwise satisfied: oracle reproduction at OUTPUTS(0), seller payout at
        // OUTPUTS(1)) — the on-chain defense is that spending the NFT out of the real
        // oracle box requires its script's proveDlog(oracleKey): leg 2 shows the foreign
        // box's own script refusing to be spent without its key, so the full
        // transaction can never validate.
        val fx = VaultFixture(feeBps = 0)
        assertTrue(
            fx.verifySpend(
                fx.fundedTree, fx.fundedBox,
                inputs = listOf(fx.fundedBox, fx.foreignOracleBox),
                outputs = listOf(fx.oracleOut(), fx.sellerOut()),
                height = proofHeight,
                vars = fx.payloadVars(),
            ),
        )
        assertFalse(
            fx.verifySpend(
                fx.sellerTree, fx.foreignOracleBox, // the foreign box's own script
                inputs = listOf(fx.foreignOracleBox),
                outputs = listOf(fx.sellerOut()),
                height = proofHeight,
            ),
        )
    }

    @Test
    fun `21 release from FUNDED paying collateral to a non-seller address fails`() {
        val fx = VaultFixture(feeBps = 0)
        assertFalse(
            fx.verifySpend(
                fx.fundedTree, fx.fundedBox,
                inputs = listOf(fx.fundedBox, fx.oracleBox),
                outputs = listOf(fx.userOut()), // user's address, not the seller's
                height = proofHeight,
                vars = fx.payloadVars(),
            ),
        )
    }

    @Test
    fun `22 release from FUNDED with digest amount different from R9 fails`() {
        val fx = VaultFixture(feeBps = 0)
        assertFalse(
            fx.verifySpend(
                fx.fundedTree, fx.fundedBox,
                inputs = listOf(fx.fundedBox, fx.oracleBox),
                outputs = listOf(fx.sellerOut()),
                height = proofHeight,
                vars = fx.payloadVars(fx.paymentPayload(amount = fx.dealAmount + 1)),
            ),
        )
    }

    @Test
    fun `23 release from FUNDED with digest recipient different from R9 fails`() {
        val fx = VaultFixture(feeBps = 0)
        val otherRecipient = ByteArray(21) { (it * 23 + 7).toByte() }
        assertFalse(
            fx.verifySpend(
                fx.fundedTree, fx.fundedBox,
                inputs = listOf(fx.fundedBox, fx.oracleBox),
                outputs = listOf(fx.sellerOut()),
                height = proofHeight,
                vars = fx.payloadVars(fx.paymentPayload(recipientAddr = otherRecipient)),
            ),
        )
    }

    @Test
    fun `24 release from FUNDED with digest dealId different from R4 fails`() {
        val fx = VaultFixture(feeBps = 0)
        val otherDealId = ByteArray(32) { (it + 99).toByte() }
        assertFalse(
            fx.verifySpend(
                fx.fundedTree, fx.fundedBox,
                inputs = listOf(fx.fundedBox, fx.oracleBox),
                outputs = listOf(fx.sellerOut()),
                height = proofHeight,
                vars = fx.payloadVars(fx.paymentPayload(dealId = otherDealId)),
            ),
        )
    }

    @Test
    fun `25 digest srcTxId is not bound on-chain and passes`() {
        // Documentation of the checked-field set: only dealId, chainId, tokenId,
        // recipient and amount are pinned against R4/R9; srcTxId/srcHeight/srcTime ride
        // along for audit and the dashboard but are NOT checked in-script (R9 has no
        // field for them). The oracle's co-signature is the trust root for those bytes.
        val fx = VaultFixture(feeBps = 0)
        val otherSrcTxId = ByteArray(32) { (it * 29 + 9).toByte() }
        assertTrue(
            fx.verifySpend(
                fx.fundedTree, fx.fundedBox,
                inputs = listOf(fx.fundedBox, fx.oracleBox),
                outputs = listOf(fx.oracleOut(), fx.sellerOut()),
                height = proofHeight,
                vars = fx.payloadVars(fx.paymentPayload(srcTxId = otherSrcTxId)),
            ),
        )
    }

    // ------------------------------------------------------------- PAYMENT_PROVEN box

    @Test
    fun `26 release from PAYMENT_PROVEN oracle-only passes`() {
        val fx = VaultFixture(feeBps = 0)
        val box = fx.provenBox(proofHeight)
        assertTrue(
            fx.verifySpend(
                fx.provenTree, box,
                inputs = listOf(box, fx.oracleBox),
                outputs = listOf(fx.oracleOut(), fx.sellerOut()),
                height = proofHeight + 1,
                vars = fx.payloadVars(),
            ),
        )
    }

    @Test
    fun `27 release from PAYMENT_PROVEN without the oracle box among inputs fails`() {
        val fx = VaultFixture(feeBps = 0)
        val box = fx.provenBox(proofHeight)
        assertFalse(
            fx.verifySpend(
                fx.provenTree, box,
                inputs = listOf(box),
                outputs = listOf(fx.sellerOut()),
                height = proofHeight + 1,
                vars = fx.payloadVars(),
            ),
        )
    }

    @Test
    fun `28 release from PAYMENT_PROVEN with the oracle box as mere data input fails`() {
        val fx = VaultFixture(feeBps = 0)
        val box = fx.provenBox(proofHeight)
        assertFalse(
            fx.verifySpend(
                fx.provenTree, box,
                inputs = listOf(box),
                dataInputs = listOf(fx.oracleBox),
                outputs = listOf(fx.sellerOut()),
                height = proofHeight + 1,
                vars = fx.payloadVars(),
            ),
        )
    }

    @Test
    fun `29 release from PAYMENT_PROVEN with digest amount different from R9 fails`() {
        val fx = VaultFixture(feeBps = 0)
        val box = fx.provenBox(proofHeight)
        assertFalse(
            fx.verifySpend(
                fx.provenTree, box,
                inputs = listOf(box, fx.oracleBox),
                outputs = listOf(fx.sellerOut()),
                height = proofHeight + 1,
                vars = fx.payloadVars(fx.paymentPayload(amount = fx.dealAmount + 1)),
            ),
        )
    }

    @Test
    fun `30 release from PAYMENT_PROVEN with digest dealId different from R4 fails`() {
        val fx = VaultFixture(feeBps = 0)
        val box = fx.provenBox(proofHeight)
        val otherDealId = ByteArray(32) { (it + 99).toByte() }
        assertFalse(
            fx.verifySpend(
                fx.provenTree, box,
                inputs = listOf(box, fx.oracleBox),
                outputs = listOf(fx.sellerOut()),
                height = proofHeight + 1,
                vars = fx.payloadVars(fx.paymentPayload(dealId = otherDealId)),
            ),
        )
    }

    @Test
    fun `31 oracle-only release counters a live claim`() {
        // The v2 residual fix (spec §4.2): the buyer opens a claim with a VALID record
        // (leg 1, path B); the seller resolves it with the oracle digest alone (leg 2,
        // path C') — no user receipt signature exists to withhold.
        val fx = VaultFixture(feeBps = 0)
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
                inputs = listOf(box, fx.oracleBox),
                outputs = listOf(fx.oracleOut(), fx.sellerOut()),
                height = proofHeight + 1,
                vars = fx.payloadVars(),
            ),
        )
    }

    @Test
    fun `32 claim before maturation fails`() {
        val fx = VaultFixture(feeBps = 0)
        val box = fx.provenBox(proofHeight)
        assertFalse(
            fx.verifySpend(
                fx.provenTree, box,
                inputs = listOf(box),
                outputs = listOf(fx.userOut()),
                height = proofHeight + ContractParams.CLAIM_MATURATION_BLOCKS - 1,
                secrets = listOf<SigmaProtocolPrivateInput<*>>(fx.userKey),
            ),
        )
    }

    @Test
    fun `33 claim after maturation by user key passes`() {
        val fx = VaultFixture(feeBps = 0)
        val box = fx.provenBox(proofHeight)
        assertTrue(
            fx.verifySpend(
                fx.provenTree, box,
                inputs = listOf(box),
                outputs = listOf(fx.userOut(), fx.changeOut()),
                height = proofHeight + ContractParams.CLAIM_MATURATION_BLOCKS + 1,
                secrets = listOf<SigmaProtocolPrivateInput<*>>(fx.userKey),
            ),
        )
    }

    @Test
    fun `34 claim by wrong key fails`() {
        val fx = VaultFixture(feeBps = 0)
        val box = fx.provenBox(proofHeight)
        assertFalse(
            fx.verifySpend(
                fx.provenTree, box,
                inputs = listOf(box),
                outputs = listOf(fx.userOut()),
                height = proofHeight + ContractParams.CLAIM_MATURATION_BLOCKS + 1,
                secrets = listOf<SigmaProtocolPrivateInput<*>>(fx.sellerKey),
            ),
        )
    }

    // ------------------------------------------------------------- fee arithmetic (35)

    @Test
    fun `35a reclaim with feeBps 25 pays seller minus fee and fee output passes`() {
        val fx = VaultFixture(feeBps = 25)
        assertTrue(
            fx.verifySpend(
                fx.fundedTree, fx.fundedBox,
                inputs = listOf(fx.fundedBox, fx.oracleBox),
                outputs = listOf(fx.sellerOut(), fx.treasuryOut()),
                height = fx.timeoutHeight + 1,
                secrets = listOf(fx.sellerKey),
            ),
        )
    }

    @Test
    fun `35b reclaim with feeBps 100 and boundary rounding passes`() {
        val fx = VaultFixture(feeBps = 100) // fee = 5_000_000 exactly
        assertTrue(
            fx.verifySpend(
                fx.fundedTree, fx.fundedBox,
                inputs = listOf(fx.fundedBox, fx.oracleBox),
                outputs = listOf(fx.sellerOut(), fx.treasuryOut()),
                height = fx.timeoutHeight + 1,
                secrets = listOf(fx.sellerKey),
            ),
        )
    }

    @Test
    fun `35c reclaim with feeBps 25 but missing fee output fails`() {
        val fx = VaultFixture(feeBps = 25)
        assertFalse(
            fx.verifySpend(
                fx.fundedTree, fx.fundedBox,
                inputs = listOf(fx.fundedBox, fx.oracleBox),
                outputs = listOf(fx.sellerOut()),
                height = fx.timeoutHeight + 1,
                secrets = listOf(fx.sellerKey),
            ),
        )
    }

    @Test
    fun `35d reclaim with feeBps 25 but wrong fee amount fails`() {
        val fx = VaultFixture(feeBps = 25)
        assertFalse(
            fx.verifySpend(
                fx.fundedTree, fx.fundedBox,
                inputs = listOf(fx.fundedBox, fx.oracleBox),
                outputs = listOf(fx.sellerOut(), fx.treasuryOut(amount = fx.fee + 1)),
                height = fx.timeoutHeight + 1,
                secrets = listOf(fx.sellerKey),
            ),
        )
    }

    @Test
    fun `35e claim after maturation deducts fee from the user's payout`() {
        val fx = VaultFixture(feeBps = 25)
        val box = fx.provenBox(proofHeight)
        assertTrue(
            fx.verifySpend(
                fx.provenTree, box,
                inputs = listOf(box),
                outputs = listOf(fx.userOut(), fx.treasuryOut()),
                height = proofHeight + ContractParams.CLAIM_MATURATION_BLOCKS + 1,
                secrets = listOf<SigmaProtocolPrivateInput<*>>(fx.userKey),
            ),
        )
    }

    // ------------------------------------------------------------- cross-deal replay (36-37)

    @Test
    fun `36 oracle digest from deal X applied to vault of deal Y fails`() {
        val fx = VaultFixture(feeBps = 0)
        val dealX = VaultFixture(feeBps = 0)
        assertFalse(
            fx.verifySpend(
                fx.fundedTree, fx.fundedBox,
                inputs = listOf(fx.fundedBox, fx.oracleBox),
                outputs = listOf(fx.sellerOut()),
                height = proofHeight,
                vars = fx.payloadVars(dealX.paymentPayload(dealId = ByteArray(32) { 42 })), // dealX digest
            ),
        )
    }

    @Test
    fun `37 handoff record from deal X applied to vault of deal Y fails`() {
        // Cross-deal replay on the claim path: a record courier-signed for deal X is
        // rejected by deal Y's vault (the dealId discriminator diverts it, and the
        // challenge binds the carried record bytes).
        val fx = VaultFixture(feeBps = 0) // deal Y vault
        val dealX = VaultFixture(feeBps = 0) // deal X record
        val sr = SignedRecord(dealX, dealX.handoffRecord())
        assertFalse(
            fx.verifySpend(
                fx.fundedTree, fx.fundedBox,
                inputs = listOf(fx.fundedBox),
                outputs = listOf(fx.provenOut(proofHeight, sr.id)),
                height = proofHeight,
                vars = fx.handoffVars(sr.record, courierSig = sr.courierSig),
            ),
        )
    }

    // ------------------------------------------------------------- adversarial Schnorr inputs (38-41)

    @Test
    fun `38 signature over tampered handoff record fails`() {
        // Honest (a, z) from Schnorr.sign over the correct record, but the record
        // context var has one flipped bit: the in-script challenge e is recomputed over
        // the var bytes, so the group equation no longer holds.
        val fx = VaultFixture(feeBps = 0)
        val sr = SignedRecord(fx, fx.handoffRecord())
        assertFalse(
            fx.verifySpend(
                fx.fundedTree, fx.fundedBox,
                inputs = listOf(fx.fundedBox),
                outputs = listOf(fx.provenOut(proofHeight, sr.id)),
                height = proofHeight,
                vars = fx.handoffVars(
                    fx.flippedByte(sr.record), courierSig = sr.courierSig,
                    aOverride = sr.courierSig.a, zOverride = sr.courierSig.z,
                ),
            ),
        )
    }

    @Test
    fun `39 z equal to the curve group order fails`() {
        // z = secp256k1 n as 32 big-endian bytes; its top bit is set, so the contract's
        // signed two's-complement byteArrayToBigInt reads it as a negative scalar that is
        // not the signer's response — the group equation fails.
        val fx = VaultFixture(feeBps = 0)
        val sr = SignedRecord(fx, fx.handoffRecord())
        val order = CustomNamedCurves.getByName("secp256k1").n
        val zBad = BigIntegers.asUnsignedByteArray(32, order)
        val tamperedId = fx.recordId(sr.record, Schnorr.Signature(sr.courierSig.a, zBad))
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
        val fx = VaultFixture(feeBps = 0)
        val sr = SignedRecord(fx, fx.handoffRecord())
        val zNeg = sr.courierSig.z.copyOf().also { it[0] = (it[0].toInt() or 0x80).toByte() }
        val tamperedId = fx.recordId(sr.record, Schnorr.Signature(sr.courierSig.a, zNeg))
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
        val fx = VaultFixture(feeBps = 0)
        val sr = SignedRecord(fx, fx.handoffRecord())
        val badNonce = ByteArray(33) { 0xff.toByte() }
        val badId = fx.recordId(sr.record, Schnorr.Signature(badNonce, sr.courierSig.z))
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

    // ------------------------------------------------------------- R7 packing (42-44)

    @Test
    fun `42 R7 packed with a wrong courier key fails path B`() {
        // The courier credential is pinned at funding (R7 bytes 32..65); a vault whose
        // R7 courier slice is a different (valid) key cannot be claimed even by the
        // honest record and courier half.
        val probe = VaultFixture(feeBps = 0) // only to borrow a distinct valid point
        val fx = VaultFixture(feeBps = 0, r7CourierPk = probe.userPk)
        val sr = SignedRecord(fx, fx.handoffRecord())
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
    fun `43 R7 packed with a wrong oracleNftId fails path C`() {
        // The release path's oracle check slices bytes 0..32 of R7; a vault pinned to a
        // different NFT id rejects the genuine oracle box.
        val fx = VaultFixture(feeBps = 0, r7OracleNftId = ByteArray(32) { (it * 11 + 2).toByte() })
        assertFalse(
            fx.verifySpend(
                fx.fundedTree, fx.fundedBox,
                inputs = listOf(fx.fundedBox, fx.oracleBox),
                outputs = listOf(fx.sellerOut()),
                height = proofHeight,
                vars = fx.payloadVars(),
            ),
        )
    }

    @Test
    fun `44 R7 missing the courier key fails path B`() {
        // Packing omission: a 32-byte R7 (oracleNftId only). decodePoint of the empty
        // courier slice throws during reduction and the spend is rejected there.
        val fx = VaultFixture(feeBps = 0, r7Bytes = ByteArray(32) { (it * 5 + 1).toByte() })
        val sr = SignedRecord(fx, fx.handoffRecord())
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
