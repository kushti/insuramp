package p2pgate.dealprotocol

import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * Deal terms wire format, `specs/deal-protocol.md` §3.1: canonical 141-byte
 * big-endian layout, `dealId = blake2b256(encode())` binding every field,
 * strict decode validation. One tamper test per field asserts the dealId
 * changes on any single-field change (the vault relies on this for
 * replay protection across deals).
 */
class DealTermsSpec {

    private fun nonce() = ByteArray(16) { it.toByte() }
    private fun key(prefix: Int, fill: Int) = ByteArray(33) { i -> if (i == 0) prefix.toByte() else fill.toByte() }

    private fun sample(
        dealNonce: ByteArray = nonce(),
        asset: Int = 0x01,
        srcChainId: Int = 0x01,
        amount: Long = 500_000_000L,
        fiatAmount: Long = 1_560_000L,
        fiatCurrency: ByteArray = "EGP".encodeToByteArray(),
        userPubKey: ByteArray = key(0x02, 0x11),
        sellerPubKey: ByteArray = key(0x03, 0x22),
        courierPubKey: ByteArray = key(0x02, 0x33),
        quoteExpiry: Long = 1_800_000_000L,
    ) = DealTerms(
        dealNonce, asset, srcChainId, amount, fiatAmount, fiatCurrency,
        userPubKey, sellerPubKey, courierPubKey, quoteExpiry,
    )

    @Test
    fun `encode produces exactly 141 bytes`() {
        assertEquals(141, sample().encode().size)
        assertEquals(DealTerms.ENCODED_SIZE, sample().encode().size)
    }

    @Test
    fun `encode then decode round-trips every field`() {
        val terms = sample()
        val decoded = DealTerms.decode(terms.encode())
        assertEquals(terms, decoded)
        assertContentEquals(terms.dealNonce, decoded.dealNonce)
        assertEquals(terms.asset, decoded.asset)
        assertEquals(terms.srcChainId, decoded.srcChainId)
        assertEquals(terms.amount, decoded.amount)
        assertEquals(terms.fiatAmount, decoded.fiatAmount)
        assertContentEquals(terms.fiatCurrency, decoded.fiatCurrency)
        assertContentEquals(terms.userPubKey, decoded.userPubKey)
        assertContentEquals(terms.sellerPubKey, decoded.sellerPubKey)
        assertContentEquals(terms.courierPubKey, decoded.courierPubKey)
        assertEquals(terms.quoteExpiry, decoded.quoteExpiry)
        assertEquals(DealTerms.VERSION, decoded.version)
    }

    @Test
    fun `layout matches section 3 dot 1 field offsets`() {
        val terms = sample(
            dealNonce = ByteArray(16) { (0xa0 + it).toByte() },
            asset = 0x03, srcChainId = 0x02,
            amount = 0x0102030405060708L,
            fiatAmount = 0x1112131415161718L,
            fiatCurrency = "USD".encodeToByteArray(),
            quoteExpiry = 0x7f112233L,
        )
        val encoded = terms.encode()
        assertEquals(0x01, encoded[0].toInt())                       // version
        assertContentEquals(ByteArray(16) { (0xa0 + it).toByte() }, encoded.copyOfRange(1, 17))  // dealNonce
        assertEquals(0x03, encoded[17].toInt())                      // asset
        assertEquals(0x02, encoded[18].toInt())                      // srcChainId
        assertContentEquals("0102030405060708".hexToBytes(), encoded.copyOfRange(19, 27))         // amount BE
        assertContentEquals("1112131415161718".hexToBytes(), encoded.copyOfRange(27, 35))         // fiatAmount BE
        assertContentEquals("USD".encodeToByteArray(), encoded.copyOfRange(35, 38))               // fiatCurrency
        assertEquals(0x02, encoded[38].toInt())                      // userPubKey prefix
        assertEquals(0x03, encoded[71].toInt())                      // sellerPubKey prefix
        assertEquals(0x02, encoded[104].toInt())                     // courierPubKey prefix
        assertContentEquals("7f112233".hexToBytes(), encoded.copyOfRange(137, 141))               // quoteExpiry BE
    }

    @Test
    fun `dealId is 32 bytes and stable`() {
        val terms = sample()
        assertEquals(32, terms.dealId.size)
        assertContentEquals(terms.dealId, sample().dealId)
    }

    private fun rebuild(
        t: DealTerms,
        dealNonce: ByteArray = t.dealNonce,
        asset: Int = t.asset,
        srcChainId: Int = t.srcChainId,
        amount: Long = t.amount,
        fiatAmount: Long = t.fiatAmount,
        fiatCurrency: ByteArray = t.fiatCurrency,
        userPubKey: ByteArray = t.userPubKey,
        sellerPubKey: ByteArray = t.sellerPubKey,
        courierPubKey: ByteArray = t.courierPubKey,
        quoteExpiry: Long = t.quoteExpiry,
    ) = DealTerms(
        dealNonce, asset, srcChainId, amount, fiatAmount, fiatCurrency,
        userPubKey, sellerPubKey, courierPubKey, quoteExpiry,
    )

    @Test
    fun `tampering dealNonce changes dealId`() = tamper { rebuild(it, dealNonce = ByteArray(16) { 0x7f }) }

    @Test
    fun `tampering asset changes dealId`() = tamper { rebuild(it, asset = 0x02) }

    @Test
    fun `tampering srcChainId changes dealId`() = tamper { rebuild(it, srcChainId = 0x02) }

    @Test
    fun `tampering amount changes dealId`() = tamper { rebuild(it, amount = 499_999_999L) }

    @Test
    fun `tampering fiatAmount changes dealId`() = tamper { rebuild(it, fiatAmount = 1_560_001L) }

    @Test
    fun `tampering fiatCurrency changes dealId`() = tamper { rebuild(it, fiatCurrency = "USD".encodeToByteArray()) }

    @Test
    fun `tampering userPubKey changes dealId`() = tamper { rebuild(it, userPubKey = key(0x02, 0x44)) }

    @Test
    fun `tampering sellerPubKey changes dealId`() = tamper { rebuild(it, sellerPubKey = key(0x03, 0x55)) }

    @Test
    fun `tampering courierPubKey changes dealId`() = tamper { rebuild(it, courierPubKey = key(0x02, 0x66)) }

    @Test
    fun `tampering quoteExpiry changes dealId`() = tamper { rebuild(it, quoteExpiry = 1_800_000_001L) }

    private fun tamper(modify: (DealTerms) -> DealTerms) {
        val original = sample()
        val tampered = modify(original)
        assertNotEquals(original.dealId.toHex(), tampered.dealId.toHex())
    }

    @Test
    fun `decode rejects wrong length`() {
        assertFailsWith<IllegalArgumentException> { DealTerms.decode(sample().encode().copyOfRange(0, 140)) }
        assertFailsWith<IllegalArgumentException> { DealTerms.decode(sample().encode() + 0x00) }
    }

    @Test
    fun `decode rejects bad version`() {
        val bytes = sample().encode()
        bytes[0] = 0x02
        assertFailsWith<IllegalArgumentException> { DealTerms.decode(bytes) }
    }

    @Test
    fun `decode rejects zero asset and chain ids`() {
        val badAsset = sample().encode().also { it[17] = 0 }
        assertFailsWith<IllegalArgumentException> { DealTerms.decode(badAsset) }
        val badChain = sample().encode().also { it[18] = 0 }
        assertFailsWith<IllegalArgumentException> { DealTerms.decode(badChain) }
    }

    @Test
    fun `reserved wire ids pass through with unknown enum views`() {
        // 0x7f is not a known id today but is a legal reserved assignment
        val terms = sample(asset = 0x7f, srcChainId = 0x7e)
        val decoded = DealTerms.decode(terms.encode())
        assertEquals(0x7f, decoded.asset)
        assertEquals(0x7e, decoded.srcChainId)
        assertNull(decoded.assetEnum)
        assertNull(decoded.srcChainEnum)
    }

    @Test
    fun `known ids map to enums`() {
        assertEquals(DealAsset.USDT, sample().assetEnum)
        assertEquals(SourceChainId.TRON, sample().srcChainEnum)
        assertEquals(DealAsset.BTC, sample(asset = 0x02).assetEnum)
        assertEquals(SourceChainId.ETHEREUM, sample(srcChainId = 0x02).srcChainEnum)
    }

    @Test
    fun `decode rejects negative amounts via encode validation`() {
        assertFailsWith<IllegalArgumentException> { sample(amount = -1).encode() }
        assertFailsWith<IllegalArgumentException> { sample(fiatAmount = -1).encode() }
    }

    @Test
    fun `decode rejects out of range quote expiry`() {
        assertFailsWith<IllegalArgumentException> { sample(quoteExpiry = -1).encode() }
        assertFailsWith<IllegalArgumentException> { sample(quoteExpiry = 0x1_0000_0000L).encode() }
    }

    @Test
    fun `rejects non ISO-4217 currency`() {
        assertFailsWith<IllegalArgumentException> { sample(fiatCurrency = "egp".encodeToByteArray()).encode() }
        assertFailsWith<IllegalArgumentException> { sample(fiatCurrency = "EG".encodeToByteArray()).encode() }
        assertFailsWith<IllegalArgumentException> { sample(fiatCurrency = "EGP1".encodeToByteArray()).encode() }
    }

    @Test
    fun `rejects uncompressed key prefixes`() {
        assertFailsWith<IllegalArgumentException> { sample(userPubKey = key(0x04, 0x11)).encode() }
        assertFailsWith<IllegalArgumentException> { sample(courierPubKey = ByteArray(33)).encode() }
        assertFailsWith<IllegalArgumentException> { sample(sellerPubKey = ByteArray(32) { 0x02 }).encode() }
    }
}
