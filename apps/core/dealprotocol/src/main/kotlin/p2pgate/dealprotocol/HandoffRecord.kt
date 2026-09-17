package p2pgate.dealprotocol

/**
 * P2PH handoff record, `specs/deal-protocol.md` §3.2 — the single-signed
 * (deal-scoped courier key) cash-collection proof created at the meeting,
 * before the seller's USDT leg starts. One Schnorr signature over
 * `blake2b256(encode())`, verified in-script by vault path B. The buyer
 * obtains the signed record at the meeting as the dispute artifact.
 *
 * Layout (84 bytes): "P2PH"(4) version(1) dealId(32) amount(8)
 * fiatCurrency(3) timestamp(4) courierIdHash(32) — all integers big-endian,
 * amount/timestamp are the fiat amount and unix seconds. The timestamp sits
 * at bytes 48..51. Signatures are never part of the message bytes.
 */
class HandoffRecord(
    val dealId: ByteArray,
    val amount: Long,
    val fiatCurrency: ByteArray,
    val timestamp: Long,
    val courierIdHash: ByteArray,
) {

    fun encode(): ByteArray = encodeWire(MAGIC, dealId, amount, fiatCurrency, timestamp, courierIdHash)

    override fun equals(other: Any?): Boolean =
        other is HandoffRecord &&
            dealId.contentEquals(other.dealId) &&
            amount == other.amount &&
            fiatCurrency.contentEquals(other.fiatCurrency) &&
            timestamp == other.timestamp &&
            courierIdHash.contentEquals(other.courierIdHash)

    override fun hashCode(): Int {
        var result = dealId.contentHashCode()
        result = 31 * result + amount.hashCode()
        result = 31 * result + fiatCurrency.contentHashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + courierIdHash.contentHashCode()
        return result
    }

    internal class WireFields(
        val dealId: ByteArray,
        val amount: Long,
        val fiatCurrency: ByteArray,
        val timestamp: Long,
        val courierIdHash: ByteArray,
    )

    companion object {
        val MAGIC: ByteArray = byteArrayOf(0x50, 0x32, 0x50, 0x48) // "P2PH"

        const val VERSION: Int = DealTerms.VERSION
        const val ENCODED_SIZE: Int = 84

        /** Convenience: `blake2b256(courierCredentialId)`, the §3.2 field. */
        fun courierIdHash(courierCredentialId: ByteArray): ByteArray = Blake2b256.digest(courierCredentialId)

        fun decode(bytes: ByteArray): HandoffRecord {
            val fields = decodeWire(bytes, MAGIC, "P2PH handoff record")
            return HandoffRecord(fields.dealId, fields.amount, fields.fiatCurrency, fields.timestamp, fields.courierIdHash)
        }

        internal fun encodeWire(
            magic: ByteArray,
            dealId: ByteArray,
            amount: Long,
            fiatCurrency: ByteArray,
            timestamp: Long,
            courierIdHash: ByteArray,
        ): ByteArray {
            validate(dealId, amount, fiatCurrency, timestamp, courierIdHash)
            val out = ByteArray(ENCODED_SIZE)
            magic.copyInto(out, 0)
            out[4] = VERSION.toByte()
            dealId.copyInto(out, 5)
            DealTerms.putU64(out, 37, amount)
            fiatCurrency.copyInto(out, 45)
            DealTerms.putU32(out, 48, timestamp)
            courierIdHash.copyInto(out, 52)
            return out
        }

        internal fun decodeWire(bytes: ByteArray, expectedMagic: ByteArray, what: String): WireFields {
            require(bytes.size == ENCODED_SIZE) { "$what must be exactly $ENCODED_SIZE bytes, got ${bytes.size}" }
            for (i in expectedMagic.indices) {
                require(bytes[i] == expectedMagic[i]) {
                    "bad magic in $what (byte $i: 0x%02x, expected 0x%02x)"
                        .format(bytes[i], expectedMagic[i])
                }
            }
            require(bytes[4].toInt() and 0xff == VERSION) {
                "unsupported $what version 0x%02x (expected 0x%02x)".format(bytes[4], VERSION)
            }
            val dealId = bytes.copyOfRange(5, 37)
            val courierIdHash = bytes.copyOfRange(52, 84)
            return WireFields(
                dealId = dealId,
                amount = DealTerms.u64(bytes, 37),
                fiatCurrency = bytes.copyOfRange(45, 48),
                timestamp = DealTerms.u32(bytes, 48),
                courierIdHash = courierIdHash,
            ).also { validate(it.dealId, it.amount, it.fiatCurrency, it.timestamp, it.courierIdHash) }
        }

        private fun validate(
            dealId: ByteArray,
            amount: Long,
            fiatCurrency: ByteArray,
            timestamp: Long,
            courierIdHash: ByteArray,
        ) {
            require(dealId.size == 32) { "dealId must be 32 bytes, got ${dealId.size}" }
            require(dealId.any { it.toInt() != 0 }) { "dealId must be nonzero" }
            require(amount >= 0) { "amount must be a uint64 (non-negative), got $amount" }
            require(DealTerms.iso4217(fiatCurrency)) { "fiatCurrency must be 3 uppercase ASCII letters (ISO-4217)" }
            require(timestamp in 0..0xFFFF_FFFFL) { "timestamp must be a uint32 unix seconds, got $timestamp" }
            require(courierIdHash.size == 32) { "courierIdHash must be 32 bytes, got ${courierIdHash.size}" }
            require(courierIdHash.any { it.toInt() != 0 }) { "courierIdHash must be nonzero" }
        }
    }
}
