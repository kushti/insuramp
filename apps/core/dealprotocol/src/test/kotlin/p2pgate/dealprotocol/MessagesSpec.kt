package p2pgate.dealprotocol

import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * P2PH handoff record (§3.2) wire format — the single-signed (courier key)
 * cash-collection proof. Expected bytes are built independently in each test
 * from the raw fields — never by calling `encode()` — and decoded back; the
 * timestamp is asserted to sit at bytes 48..51.
 */
class MessagesSpec {

    private val dealId = ByteArray(32) { (it + 1).toByte() }          // 0x01..0x20
    private val amount = 1_560_000L                                    // 15,600 EGP in piasters
    private val currency = "EGP".encodeToByteArray()
    private val timestamp = 1_700_000_000L
    private val credential = "courier-credential-7".encodeToByteArray()
    private val hash = Blake2b256.digest(credential)

    /** Hand-built §3.2 layout — the single source of truth for the fixture. */
    private fun expectedBytes(magic: String): ByteArray {
        val out = ByteArray(84)
        magic.encodeToByteArray().copyInto(out, 0)   // 0..3   magic
        out[4] = 0x01                                // 4      version
        dealId.copyInto(out, 5)                      // 5..36  dealId
        for (i in 0 until 8) out[37 + i] = (amount shr (56 - 8 * i)).toByte()   // 37..44 amount BE
        currency.copyInto(out, 45)                   // 45..47 fiatCurrency
        for (i in 0 until 4) out[48 + i] = (timestamp shr (24 - 8 * i)).toByte() // 48..51 timestamp BE
        hash.copyInto(out, 52)                       // 52..83 courierIdHash
        return out
    }

    private fun timestampFrom(bytes: ByteArray): Long {
        require(bytes.size == 84)
        var value = 0L
        for (i in 48 until 52) value = (value shl 8) or (bytes[i].toLong() and 0xff)
        return value
    }

    // ---------- P2PH handoff record ----------

    @Test
    fun `handoff record encodes to the hand-built 84 byte fixture`() {
        val record = HandoffRecord(dealId, amount, currency, timestamp, hash)
        assertContentEquals(expectedBytes("P2PH"), record.encode())
        assertEquals(84, record.encode().size)
    }

    @Test
    fun `handoff record decodes the hand-built fixture field by field`() {
        val decoded = HandoffRecord.decode(expectedBytes("P2PH"))
        assertContentEquals(dealId, decoded.dealId)
        assertEquals(amount, decoded.amount)
        assertContentEquals(currency, decoded.fiatCurrency)
        assertEquals(timestamp, decoded.timestamp)
        assertContentEquals(hash, decoded.courierIdHash)
    }

    @Test
    fun `handoff record encode-decode round trip`() {
        val record = HandoffRecord(dealId, amount, currency, timestamp, hash)
        assertEquals(record, HandoffRecord.decode(record.encode()))
    }

    @Test
    fun `handoff timestamp sits at bytes 48 to 51`() {
        assertEquals(timestamp, timestampFrom(expectedBytes("P2PH")))
        assertEquals(timestamp, timestampFrom(HandoffRecord(dealId, amount, currency, timestamp, hash).encode()))
    }

    // ---------- strict decode ----------

    @Test
    fun `decode rejects truncated input`() {
        assertFailsWith<IllegalArgumentException> { HandoffRecord.decode(expectedBytes("P2PH").copyOfRange(0, 83)) }
    }

    @Test
    fun `decode rejects wrong version`() {
        val bytes = expectedBytes("P2PH")
        bytes[4] = 0x02
        assertFailsWith<IllegalArgumentException> { HandoffRecord.decode(bytes) }
    }

    @Test
    fun `decode rejects corrupt magic`() {
        val bytes = expectedBytes("P2PH")
        bytes[3] = 'X'.code.toByte()
        assertFailsWith<IllegalArgumentException> { HandoffRecord.decode(bytes) }
    }

    @Test
    fun `decode rejects zero dealId`() {
        val bytes = expectedBytes("P2PH")
        bytes.fill(0, 5, 37)
        assertFailsWith<IllegalArgumentException> { HandoffRecord.decode(bytes) }
    }

    @Test
    fun `decode rejects zero courierIdHash`() {
        val bytes = expectedBytes("P2PH")
        bytes.fill(0, 52, 84)
        assertFailsWith<IllegalArgumentException> { HandoffRecord.decode(bytes) }
    }

    @Test
    fun `decode rejects non ISO currency`() {
        val badCurrency = expectedBytes("P2PH").also { it[45] = 'e'.code.toByte() }
        assertFailsWith<IllegalArgumentException> { HandoffRecord.decode(badCurrency) }
    }

    // ---------- courierIdHash helper ----------

    @Test
    fun `courierIdHash helper is blake2b256 of the credential id`() {
        assertContentEquals(Blake2b256.digest(credential), HandoffRecord.courierIdHash(credential))
        assertEquals(32, HandoffRecord.courierIdHash(credential).size)
    }

    @Test
    fun `construction rejects negative amounts and out of range timestamps`() {
        assertFailsWith<IllegalArgumentException> {
            HandoffRecord(dealId, -1, currency, timestamp, hash).encode()
        }
        assertFailsWith<IllegalArgumentException> {
            HandoffRecord(dealId, amount, currency, 0x1_0000_0000L, hash).encode()
        }
        assertFailsWith<IllegalArgumentException> {
            HandoffRecord(dealId, amount, "EG".encodeToByteArray(), timestamp, hash).encode()
        }
    }
}
