package p2pgate.dealprotocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TRON base58check codec (`specs/deal-protocol.md` §3.3). The vectors are
 * reference addresses: a checksum-valid string decodes to a 21-byte payload
 * beginning `0x41`, and every one-byte mutation of it fails.
 */
class TronAddressSpec {

    /** Tether (TRC-20 contract) — payload `0x41 ‖ a614…3c`. */
    private val tether = "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t"

    /** A well-formed address whose account hash is all zeros. */
    private val zeroHash = "T9yD14Nj9j7xAB4dbGeiX9h8unkKHxuWwb"

    private val other = listOf(
        tether,
        zeroHash,
        "TWd4WrZ9wn84f5x1hZhL4DHvk738ns5jwb",
        "TLa2f6VPqDgRE67v1736s7bJ8Ray5wYjU7",
    )

    @Test
    fun `reference addresses are valid and 34 chars`() {
        for (address in other) {
            assertEquals(TronAddress.ADDRESS_LEN, address.length, address)
            assertTrue(TronAddress.isValid(address), address)
        }
    }

    @Test
    fun `decodes the 21-byte payload with the 0x41 version byte`() {
        val payload = TronAddress.decodeOrNull(tether)!!
        assertEquals(TronAddress.PAYLOAD_SIZE, payload.size)
        assertEquals(0x41.toByte(), payload[0])
        // Reference payload, cross-checked against an independent base58check decode.
        assertContentEquals(
            hexToBytes("41a614f803b6fd780986a42c78ec9c7f77e6ded13c"),
            payload,
        )
    }

    @Test
    fun `encode is the inverse of decode for every reference`() {
        for (address in other) {
            assertEquals(address, TronAddress.encode(TronAddress.decodeOrNull(address)!!))
        }
    }

    @Test
    fun `round-trips generated payloads`() {
        val payload = ByteArray(TronAddress.PAYLOAD_SIZE).also { it[0] = 0x41 }
        for (i in 1 until payload.size) payload[i] = (i * 37).toByte()
        val encoded = TronAddress.encode(payload)
        assertTrue(encoded.startsWith("T"), encoded)
        assertEquals(TronAddress.ADDRESS_LEN, encoded.length, encoded)
        assertContentEquals(payload, TronAddress.decodeOrNull(encoded)!!)
    }

    @Test
    fun `every single-character mutation of a valid address is rejected`() {
        // A last-character flip is the cheapest checksum break; try each position
        // across the alphabet so prefix and checksum changes are both covered.
        for (address in other) {
            for (position in address.indices) {
                for (replacement in "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".toSet()) {
                    if (replacement == address[position]) continue
                    val mutated = address.replaceRange(position, position + 1, replacement.toString())
                    assertFalse(TronAddress.isValid(mutated), "mutation at $position: $mutated")
                }
            }
        }
    }

    @Test
    fun `rejects the wrong version byte`() {
        val payload = TronAddress.decodeOrNull(tether)!!.copyOf()
        payload[0] = 0x42.toByte()
        assertFailsWith<IllegalArgumentException> { TronAddress.encode(payload) }
            .message?.let { assertTrue(it.contains("0x41"), it) }
    }

    @Test
    fun `rejects malformed input`() {
        val bad = listOf(
            "",
            "T",
            "tether",
            tether.dropLast(1),
            tether + "T",
            "R${tether.drop(1)}",   // wrong prefix letter
            "0${tether.drop(1)}",   // '0' is not in the base58 alphabet
            "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6I", // 'I' is not in the base58 alphabet
        )
        for (address in bad) {
            assertFalse(TronAddress.isValid(address), address)
            assertNull(TronAddress.decodeOrNull(address), address)
        }
    }

    @Test
    fun `rejects a payload of the wrong size`() {
        for (size in listOf(0, 20, 22, 25)) {
            assertFailsWith<IllegalArgumentException> { TronAddress.encode(ByteArray(size)) }
        }
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { ((hex[it * 2].digitToInt(16) shl 4) or hex[it * 2 + 1].digitToInt(16)).toByte() }
}
