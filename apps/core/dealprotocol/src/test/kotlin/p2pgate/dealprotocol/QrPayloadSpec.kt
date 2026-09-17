package p2pgate.dealprotocol

import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * QR payload codecs, `specs/deal-protocol.md` §3.3: strict round-trips for
 * the `p2pgate://handoff` URI (base64url, padding absent), scheme rejection,
 * and the lenient address share.
 */
class QrPayloadSpec {

    private val dealId = ByteArray(32) { (it + 3).toByte() }
    private val hash = Blake2b256.digest("courier-credential-9".encodeToByteArray())

    private fun handoff() = HandoffRecord(dealId, 2_500_000L, "USD".encodeToByteArray(), 1_700_000_100L, hash)

    @Test
    fun `handoff QR round-trips`() {
        val payload = QrPayload.encodeHandoff(handoff())
        assertEquals(handoff(), QrPayload.decodeHandoff(payload))
        assertContentEquals(handoff().encode(), QrPayload.decodeHandoff(payload).encode())
    }

    @Test
    fun `handoff payload uses its scheme`() {
        assertTrue(QrPayload.encodeHandoff(handoff()).startsWith("p2pgate://handoff?m="))
    }

    @Test
    fun `wrong scheme is rejected`() {
        assertFailsWith<IllegalArgumentException> { QrPayload.decodeHandoff("https://handoff?m=AAAA") }
        assertFailsWith<IllegalArgumentException> { QrPayload.decodeHandoff("p2pgate://sign?m=AAAA") }
        assertFailsWith<IllegalArgumentException> { QrPayload.decodeHandoff("p2pgate://handoff") }
    }

    @Test
    fun `missing m parameter is rejected`() {
        assertFailsWith<IllegalArgumentException> { QrPayload.decodeHandoff("p2pgate://handoff?m=") }
    }

    @Test
    fun `base64url alphabet has no plus slash or padding`() {
        val payload = QrPayload.encodeHandoff(handoff())
        val encoded = payload.removePrefix("p2pgate://handoff?m=")
        assertTrue('+' !in encoded && '/' !in encoded && '=' !in encoded,
            "base64url output must not contain +, / or = : $encoded")
        assertTrue(encoded.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test
    fun `base64url uses dash and underscore where standard base64 uses plus and slash`() {
        // record bytes 6..8 (a full 3-byte group) are 0xfb 0xff 0xfe, whose
        // 6-bit groups are 62 63 63 62: "+//+" in standard base64, "-__-" in base64url
        val record = HandoffRecord(
            dealId = byteArrayOf(0x00, 0xfb.toByte(), 0xff.toByte(), 0xfe.toByte()) + ByteArray(28) { 0x01 },
            amount = 1, fiatCurrency = "EGP".encodeToByteArray(), timestamp = 1, courierIdHash = hash,
        )
        val encoded = QrPayload.encodeHandoff(record).removePrefix("p2pgate://handoff?m=")
        assertTrue("-__-" in encoded, "expected base64url -__- in place of standard +//+: $encoded")
        assertTrue('+' !in encoded && '/' !in encoded)
        assertEquals(record, QrPayload.decodeHandoff(QrPayload.encodeHandoff(record)))
    }

    @Test
    fun `padding is rejected on decode`() {
        val payload = QrPayload.encodeHandoff(handoff()) + "="
        assertFailsWith<IllegalArgumentException> { QrPayload.decodeHandoff(payload) }
    }

    @Test
    fun `address share without amount`() {
        assertEquals("TXtkqTwRb1iS5wF2VYbQv9nGpM7uEHzdKc", QrPayload.formatAddress("TXtkqTwRb1iS5wF2VYbQv9nGpM7uEHzdKc"))
        val share = QrPayload.parseAddress("TXtkqTwRb1iS5wF2VYbQv9nGpM7uEHzdKc")
        assertEquals("TXtkqTwRb1iS5wF2VYbQv9nGpM7uEHzdKc", share.address)
        assertNull(share.amount)
    }

    @Test
    fun `address share with amount round-trips`() {
        val formatted = QrPayload.formatAddress("0x71C7656EC7ab88b098defB751B7401B5f6d8976F", 500_000_000L)
        assertEquals("0x71C7656EC7ab88b098defB751B7401B5f6d8976F?amount=500000000", formatted)
        assertEquals(
            AddressShare("0x71C7656EC7ab88b098defB751B7401B5f6d8976F", 500_000_000L),
            QrPayload.parseAddress(formatted),
        )
    }

    @Test
    fun `address share parses leniently`() {
        // unknown params ignored, unparseable amount dropped, EIP-55 / base58 both fine
        assertEquals(AddressShare("TAddr", 100L), QrPayload.parseAddress("TAddr?memo=xyz&amount=100"))
        assertEquals(AddressShare("TAddr", null), QrPayload.parseAddress("TAddr?amount=oops"))
        assertEquals(AddressShare("TAddr", null), QrPayload.parseAddress("TAddr?amount=-5"))
        assertEquals(AddressShare("TAddr", null), QrPayload.parseAddress("TAddr?"))
    }

    @Test
    fun `address share rejects empty address and bad amount on format`() {
        assertFailsWith<IllegalArgumentException> { QrPayload.formatAddress("") }
        assertFailsWith<IllegalArgumentException> { QrPayload.formatAddress("TAddr", -1) }
        assertFailsWith<IllegalArgumentException> { QrPayload.formatAddress("TAddr?amount=1") }
    }
}
