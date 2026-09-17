package p2pgate.ergo

import org.junit.jupiter.api.Test
import p2pgate.dealprotocol.Blake2b256
import p2pgate.dealprotocol.DealTerms
import p2pgate.dealprotocol.SourceChainId
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The 112-byte payment-proof payload (`specs/oracle-integration.md` §2.2):
 * layout round-trip, a fixed vector (exact bytes + blake2b256 digest,
 * cross-checked against the pure-Kotlin dealprotocol hash), per-field tamper
 * rejection, recipient padding rules (21 B Tron, 20 B Ethereum right-aligned),
 * and the deal-terms cross-check the builders rely on.
 */
class PaymentAttestationSpec {

    private val f = ErgoTestFixtures

    // Fixed vector (mirrors VaultFixture.paymentPayload's field pattern):
    // version=1, dealId=it*13+4, chain=1 (Tron), token=1, recipient=it*19+6,
    // amount=500_000_000, srcTxId=it*17+5, height=12_345, time=1_700_000_000.
    private val fixedPayloadHex =
        "0104111e2b3845525f6c798693a0adbac7d4e1eefb0815222f3c495663707d8a97" +
            "010106192c3f5265788b9eb1c4d7eafd102336495c6f82000000001dcd6500" +
            "05162738495a6b7c8d9eafc0d1e2f30415263748596a7b8c9daebfd0e1f203" +
            "140000000000003039000000006553f100"
    private val fixedDigestHex = "e73389599e10bbab60982d5643f505a8a293098c0fa598cc2bfb6f0ea961b047"

    private fun fixedAttestation(): PaymentAttestation = PaymentAttestation(
        dealId = ByteArray(32) { (it * 13 + 4).toByte() },
        srcChainId = 1,
        tokenId = 1,
        recipient = ByteArray(21) { (it * 19 + 6).toByte() },
        amount = 500_000_000L,
        srcTxId = ByteArray(32) { (it * 17 + 5).toByte() },
        srcBlockHeight = 12_345L,
        srcBlockTime = 1_700_000_000L,
    )

    @Test
    fun `layout round-trips through encode and decode`() {
        val terms = f.dealTerms()
        val att = PaymentAttestation.build(
            dealTerms = terms,
            recipientAddr = f.recipientRaw,
            srcTxId = ByteArray(32) { 7 },
            srcBlockHeight = 98_765L,
            srcBlockTime = 1_700_111_000L,
        )
        val encoded = att.encode()
        assertEquals(PaymentAttestation.SIZE, encoded.size)
        assertEquals(1, encoded[0]) // version

        val decoded = PaymentAttestation.decode(encoded)
        assertEquals(att.version, decoded.version)
        assertTrue(decoded.dealId.contentEquals(att.dealId))
        assertEquals(att.srcChainId, decoded.srcChainId)
        assertEquals(att.tokenId, decoded.tokenId)
        assertTrue(decoded.recipient.contentEquals(att.recipient))
        assertEquals(att.amount, decoded.amount)
        assertTrue(decoded.srcTxId.contentEquals(att.srcTxId))
        assertEquals(att.srcBlockHeight, decoded.srcBlockHeight)
        assertEquals(att.srcBlockTime, decoded.srcBlockTime)
        assertTrue(decoded.encode().contentEquals(encoded))
    }

    @Test
    fun `fixed vector encodes to the exact bytes and digests as specified`() {
        val att = fixedAttestation()
        val encoded = att.encode()
        assertEquals(fixedPayloadHex, Base16.encode(encoded))
        // blake2b256(payload) — cross-checked against the pure-Kotlin
        // dealprotocol hash (the same function ErgoScript runs in-script).
        assertEquals(fixedDigestHex, Base16.encode(att.digest()))
        assertTrue(Blake2b256.digest(encoded).contentEquals(att.digest()))
        assertTrue(PaymentAttestation.decode(Base16.decode(fixedPayloadHex)).let {
            it.dealId.contentEquals(att.dealId) && it.amount == att.amount
        })
    }

    @Test
    fun `decode rejects a wrong-size payload`() {
        val att = fixedAttestation()
        assertFailsWith<IllegalArgumentException> { PaymentAttestation.decode(att.encode().copyOf(111)) }
        assertFailsWith<IllegalArgumentException> { PaymentAttestation.decode(att.encode() + byteArrayOf(0)) }
    }

    @Test
    fun `decode rejects a non-1 version`() {
        val bytes = fixedAttestation().encode()
        bytes[0] = 2
        assertFailsWith<IllegalArgumentException> { PaymentAttestation.decode(bytes) }
    }

    @Test
    fun `ethereum recipient must be right-aligned in the 21-byte field`() {
        // Well-formed: 20-byte payload left-padded with 0x00.
        val ethRecipient = ByteArray(20) { (it * 3 + 1).toByte() }
        val padded = PaymentAttestation.padRecipient(ethRecipient, SourceChainId.ETHEREUM.wireId)
        assertEquals(21, padded.size)
        assertEquals(0, padded[0])
        assertTrue(padded.copyOfRange(1, 21).contentEquals(ethRecipient))

        // A non-zero first byte in the Ethereum field is rejected (validation
        // runs at construction, before any encoding).
        assertFailsWith<IllegalArgumentException> {
            PaymentAttestation(
                dealId = ByteArray(32) { (it * 13 + 4).toByte() },
                srcChainId = SourceChainId.ETHEREUM.wireId,
                tokenId = 1,
                recipient = ByteArray(21) { (it + 1).toByte() },
                amount = 500_000_000L,
                srcTxId = ByteArray(32) { 5 },
                srcBlockHeight = 12_345L,
                srcBlockTime = 1_700_000_000L,
            )
        }

        // Size rules per chain.
        assertFailsWith<IllegalArgumentException> {
            PaymentAttestation.padRecipient(ByteArray(20), SourceChainId.TRON.wireId)
        }
        assertFailsWith<IllegalArgumentException> {
            PaymentAttestation.padRecipient(ByteArray(21), SourceChainId.ETHEREUM.wireId)
        }
    }

    @Test
    fun `per-field tamper of the funding-set fields breaks the deal-terms match`() {
        val terms = f.dealTerms()
        val att = PaymentAttestation.build(terms, f.recipientRaw, ByteArray(32) { 7 }, 100L, 1_700_000_000L)
        assertTrue(att.matches(terms, f.recipientRaw))

        val otherTerms = f.dealTerms(currency = "USD") // different dealId
        assertFalse(att.matches(otherTerms, f.recipientRaw))

        val otherRecipient = ByteArray(21) { (it * 23 + 7).toByte() }
        assertFalse(att.matches(terms, otherRecipient))

        val otherAmount = PaymentAttestation.build(
            terms.copy(amount = terms.amount + 1), f.recipientRaw, ByteArray(32) { 7 }, 100L, 1_700_000_000L,
        )
        assertFalse(otherAmount.matches(terms, f.recipientRaw))

        // Digest binds every byte: any field change changes the digest.
        assertNotEquals(Base16.encode(att.digest()), Base16.encode(otherAmount.digest()))
    }

    @Test
    fun `unassigned token wire id 0x00 is rejected`() {
        val att = fixedAttestation()
        assertFailsWith<IllegalArgumentException> {
            PaymentAttestation(
                dealId = att.dealId,
                srcChainId = att.srcChainId,
                tokenId = 0,
                recipient = att.recipient,
                amount = att.amount,
                srcTxId = att.srcTxId,
                srcBlockHeight = att.srcBlockHeight,
                srcBlockTime = att.srcBlockTime,
            )
        }
    }

    @Test
    fun `funding binding is the 31-byte R9 layout the contract slices`() {
        val terms = f.dealTerms()
        val padded = PaymentAttestation.padRecipient(f.recipientRaw, terms.srcChainId)
        val binding = PaymentAttestation.fundingBinding(terms.srcChainId, terms.asset, padded, terms.amount)
        assertEquals(31, binding.size)
        // Identical to the fixture's R9 (which mirrors VaultFixture.fundingBinding).
        assertTrue(binding.contentEquals(f.fundingBinding()))
        // The contract's slices: chain r9(0), token r9(1), recipient r9[2..23), amount r9[23..31).
        assertEquals(terms.srcChainId, binding[0].toInt() and 0xff)
        assertEquals(terms.asset, binding[1].toInt() and 0xff)
        assertTrue(binding.copyOfRange(2, 23).contentEquals(padded))
        assertEquals(terms.amount, PaymentAttestation.u64(binding, 23))
    }

    @Test
    fun `build derives the funding-set fields from the deal terms`() {
        val terms = f.dealTerms()
        val att = PaymentAttestation.build(terms, f.recipientRaw, ByteArray(32) { 9 }, 5L, 6L)
        assertTrue(att.dealId.contentEquals(terms.dealId))
        assertEquals(terms.srcChainId, att.srcChainId)
        assertEquals(terms.asset, att.tokenId)
        assertEquals(terms.amount, att.amount)
        assertTrue(att.recipient.contentEquals(f.recipientRaw))
        assertTrue(att.matches(terms, f.recipientRaw))
    }

    private fun DealTerms.copy(
        amount: Long = this.amount,
        currency: String = String(this.fiatCurrency),
    ): DealTerms = DealTerms(
        dealNonce = dealNonce.copyOf(),
        asset = asset,
        srcChainId = srcChainId,
        amount = amount,
        fiatAmount = fiatAmount,
        fiatCurrency = currency.toByteArray(),
        buyerPubKey = buyerPubKey.copyOf(),
        sellerPubKey = sellerPubKey.copyOf(),
        quoteExpiry = quoteExpiry,
    )
}
