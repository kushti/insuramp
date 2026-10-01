package p2pgate.dealprotocol

import java.math.BigInteger
import java.security.MessageDigest

/**
 * Tron base58check address codec (`specs/deal-protocol.md` §3.3: deals are
 * Tron-only in phase 1, `srcChainId = 0x01`; the EIP-55/Ethereum encoding stays
 * defined for the Ethereum leg in `specs/oracle-integration.md`).
 *
 * A TRON address is the base58check encoding of **25** bytes —
 * `0x41 ‖ sha256(pubkey)[12..32] ‖ checksum` — where the checksum is the first
 * 4 bytes of `sha256(sha256(0x41 ‖ hash))`. It renders as 34 characters
 * starting with `T`. [PAYLOAD_SIZE] is the address *payload* the AML scorer and
 * the off-chain watch set identify an account by: the leading `0x41` version
 * byte plus the 20-byte account hash (21 bytes).
 *
 * This lives in `:core:dealprotocol` rather than in either consumer because the
 * buyer app validates what the buyer types and the operator backend validates
 * what the buyer sent — one codec, one set of vectors, no chance of the two
 * disagreeing about what a valid address is.
 */
object TronAddress {

    /** The base58 alphabet (Bitcoin's, which TRON shares): no `0`, `O`, `I`, `l`. */
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    private const val CHECKSUM_LEN = 4
    private const val DECODED_LEN = 25
    private const val VERSION_BYTE = 0x41.toByte()
    private const val RADIX = 58
    private val RADIX_BIG: BigInteger = BigInteger.valueOf(RADIX.toLong())

    /** Base58's zero digit — a leading zero byte renders as one of these. */
    private const val LEADING_ZERO_DIGIT = '1'

    /** `0x41 ‖ 20-byte account hash` — the stable account identifier. */
    const val PAYLOAD_SIZE = 21

    /** Rendered length of every TRON address. */
    const val ADDRESS_LEN = 34

    private val DECODE_MAP: IntArray = IntArray(128) { -1 }.also { map ->
        ALPHABET.forEachIndexed { index, c -> map[c.code] = index }
    }

    /** The 21-byte payload of [address], or `null` when it is not a valid TRON address. */
    fun decodeOrNull(address: String): ByteArray? {
        if (address.length != ADDRESS_LEN || address[0] != 'T') return null
        val raw = base58Decode(address) ?: return null
        if (raw.size != DECODED_LEN || raw[0] != VERSION_BYTE) return null
        if (!checksumOk(raw)) return null
        return raw.copyOfRange(0, PAYLOAD_SIZE)
    }

    /** True when [address] is a well-formed TRON address (prefix, length, checksum). */
    fun isValid(address: String): Boolean = decodeOrNull(address) != null

    /**
     * The base58check rendering of a 21-byte payload (`0x41 ‖ account hash`).
     * Inverse of [decodeOrNull]; the round-trip is the property under test.
     */
    fun encode(payload: ByteArray): String {
        require(payload.size == PAYLOAD_SIZE) { "TRON address payload must be $PAYLOAD_SIZE bytes, got ${payload.size}" }
        require(payload[0] == VERSION_BYTE) {
            "TRON address payload must start with the 0x41 version byte, got 0x%02x".format(payload[0])
        }
        val raw = ByteArray(DECODED_LEN)
        payload.copyInto(raw, 0)
        sha256(sha256(raw.copyOfRange(0, PAYLOAD_SIZE))).copyInto(raw, PAYLOAD_SIZE, 0, CHECKSUM_LEN)
        return base58Encode(raw)
    }

    // ---------- base58 ----------
    //
    // Big-integer base conversion. The operands are a 25-byte address and a
    // 34-character string, so the clarity of an exact conversion is worth more
    // than avoiding a BigInteger allocation.

    private fun base58Decode(input: String): ByteArray? {
        var value = BigInteger.ZERO
        for (ch in input) {
            if (ch.code >= 128) return null
            val digit = DECODE_MAP[ch.code]
            if (digit < 0) return null
            value = value * RADIX_BIG + BigInteger.valueOf(digit.toLong())
        }
        val leadingZeros = input.length - input.trimStart(LEADING_ZERO_DIGIT).length
        val body = if (value.signum() == 0) ByteArray(0) else value.toByteArray()
        // BigInteger.toByteArray is two's-complement and prepends a sign byte
        // when the top bit is set — drop it, then re-pad the leading zeros that
        // base58 encodes as '1' characters.
        val unsigned = if (body.size > 1 && body[0] == 0.toByte()) body.copyOfRange(1, body.size) else body
        val out = ByteArray(leadingZeros + unsigned.size)
        unsigned.copyInto(out, leadingZeros)
        return out
    }

    private fun base58Encode(input: ByteArray): String {
        var value = BigInteger(1, input)
        val out = StringBuilder()
        while (value.signum() > 0) {
            val (quotient, remainder) = value.divideAndRemainder(RADIX_BIG)
            out.append(ALPHABET[remainder.toInt()])
            value = quotient
        }
        // Only the *leading* zero bytes are unrepresentable in base58 digits; a
        // zero byte in the middle of the payload (an all-zero account hash) is
        // carried by the converted digits and must not become a '1'.
        val leadingZeros = input.indexOfFirst { it.toInt() != 0 }.let { if (it < 0) input.size else it }
        return buildString(leadingZeros + out.length) {
            repeat(leadingZeros) { append(LEADING_ZERO_DIGIT) }
            append(out.reverse())
        }
    }

    // ---------- checksum ----------

    private fun checksumOk(raw: ByteArray): Boolean {
        val body = raw.copyOfRange(0, raw.size - CHECKSUM_LEN)
        val expected = sha256(sha256(body))
        for (i in 0 until CHECKSUM_LEN) {
            if (raw[raw.size - CHECKSUM_LEN + i] != expected[i]) return false
        }
        return true
    }

    private fun sha256(input: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(input)
}
