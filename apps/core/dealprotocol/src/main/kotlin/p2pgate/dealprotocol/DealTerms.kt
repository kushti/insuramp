package p2pgate.dealprotocol

/**
 * Deal terms serialization, `specs/deal-protocol.md` §3.1 — the canonical
 * big-endian layout agreed at QUOTED. `dealId = blake2b256(encode())` binds
 * amount, asset, chain, and both keys; the vault's R4 stores it and
 * every proof references it, so no proof is replayable across deals.
 *
 * Layout (108 bytes): version(1) dealNonce(16) asset(1) srcChainId(1)
 * amount(8) fiatAmount(8) fiatCurrency(3) buyerPubKey(33) sellerPubKey(33)
 * quoteExpiry(4).
 *
 * `asset` / `srcChainId` are carried as raw wire ids: the known ids are
 * enumerated in [DealAsset] / [SourceChainId], unknown non-zero ids are
 * reserved and pass through (see the spec's "0x02 BTC, 0x03 XMR reserved"
 * note); 0x00 is unassigned and rejected as a validation hook.
 *
 * All multi-byte integers unsigned big-endian; u64/u32 fields are carried in
 * Kotlin `Long` and validated to be non-negative (and in range for u32).
 */
class DealTerms(
    val dealNonce: ByteArray,
    val asset: Int,
    val srcChainId: Int,
    val amount: Long,
    val fiatAmount: Long,
    val fiatCurrency: ByteArray,
    val buyerPubKey: ByteArray,
    val sellerPubKey: ByteArray,
    val quoteExpiry: Long,
) {

    val version: Int = VERSION

    /** Known-id view of [asset]; `null` when [asset] is a reserved wire id. */
    val assetEnum: DealAsset? get() = DealAsset.fromWireId(asset)

    /** Known-id view of [srcChainId]; `null` when [srcChainId] is a reserved wire id. */
    val srcChainEnum: SourceChainId? get() = SourceChainId.fromWireId(srcChainId)

    /** `blake2b256(encode())` — 32 bytes, the deal's identity everywhere (§3.1). */
    val dealId: ByteArray get() = Blake2b256.digest(encode())

    fun encode(): ByteArray {
        validate()
        val out = ByteArray(ENCODED_SIZE)
        out[0] = VERSION.toByte()
        dealNonce.copyInto(out, 1)
        out[17] = asset.toByte()
        out[18] = srcChainId.toByte()
        putU64(out, 19, amount)
        putU64(out, 27, fiatAmount)
        fiatCurrency.copyInto(out, 35)
        buyerPubKey.copyInto(out, 38)
        sellerPubKey.copyInto(out, 71)
        putU32(out, 104, quoteExpiry)
        return out
    }

    private fun validate() {
        require(dealNonce.size == NONCE_SIZE) { "dealNonce must be $NONCE_SIZE bytes, got ${dealNonce.size}" }
        requireAssignmentWireId(asset, "asset")
        requireAssignmentWireId(srcChainId, "srcChainId")
        require(amount >= 0) { "amount must be a uint64 (non-negative), got $amount" }
        require(fiatAmount >= 0) { "fiatAmount must be a uint64 (non-negative), got $fiatAmount" }
        require(iso4217(fiatCurrency)) { "fiatCurrency must be 3 uppercase ASCII letters (ISO-4217)" }
        requireCompressedKey(buyerPubKey, "buyerPubKey")
        requireCompressedKey(sellerPubKey, "sellerPubKey")
        require(quoteExpiry >= 0 && quoteExpiry <= 0xFFFF_FFFFL) {
            "quoteExpiry must be a uint32, got $quoteExpiry"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is DealTerms &&
            dealNonce.contentEquals(other.dealNonce) &&
            asset == other.asset &&
            srcChainId == other.srcChainId &&
            amount == other.amount &&
            fiatAmount == other.fiatAmount &&
            fiatCurrency.contentEquals(other.fiatCurrency) &&
            buyerPubKey.contentEquals(other.buyerPubKey) &&
            sellerPubKey.contentEquals(other.sellerPubKey) &&
            quoteExpiry == other.quoteExpiry

    override fun hashCode(): Int {
        var result = dealNonce.contentHashCode()
        result = 31 * result + asset
        result = 31 * result + srcChainId
        result = 31 * result + amount.hashCode()
        result = 31 * result + fiatAmount.hashCode()
        result = 31 * result + fiatCurrency.contentHashCode()
        result = 31 * result + buyerPubKey.contentHashCode()
        result = 31 * result + sellerPubKey.contentHashCode()
        result = 31 * result + quoteExpiry.hashCode()
        return result
    }

    override fun toString(): String =
        "DealTerms(version=$VERSION, asset=0x%02x, srcChainId=0x%02x, amount=$amount, fiatAmount=$fiatAmount, quoteExpiry=$quoteExpiry)"
            .format(asset, srcChainId)

    companion object {
        const val VERSION: Int = 1
        const val ENCODED_SIZE: Int = 108
        const val NONCE_SIZE: Int = 16
        const val PUBKEY_SIZE: Int = 33
        const val CURRENCY_SIZE: Int = 3

        fun decode(bytes: ByteArray): DealTerms {
            require(bytes.size == ENCODED_SIZE) {
                "deal terms must be exactly $ENCODED_SIZE bytes, got ${bytes.size}"
            }
            require(bytes[0].toInt() and 0xff == VERSION) {
                "unsupported deal terms version 0x%02x (expected 0x%02x)".format(bytes[0], VERSION)
            }
            return DealTerms(
                dealNonce = bytes.copyOfRange(1, 17),
                asset = bytes[17].toInt() and 0xff,
                srcChainId = bytes[18].toInt() and 0xff,
                amount = u64(bytes, 19),
                fiatAmount = u64(bytes, 27),
                fiatCurrency = bytes.copyOfRange(35, 38),
                buyerPubKey = bytes.copyOfRange(38, 71),
                sellerPubKey = bytes.copyOfRange(71, 104),
                quoteExpiry = u32(bytes, 104),
            ).also { it.validate() }
        }

        internal fun putU64(out: ByteArray, offset: Int, value: Long) {
            for (i in 0 until 8) out[offset + i] = (value shr (56 - 8 * i)).toByte()
        }

        internal fun putU32(out: ByteArray, offset: Int, value: Long) {
            for (i in 0 until 4) out[offset + i] = (value shr (24 - 8 * i)).toByte()
        }

        internal fun u64(bytes: ByteArray, offset: Int): Long {
            var value = 0L
            for (i in 0 until 8) value = (value shl 8) or (bytes[offset + i].toLong() and 0xff)
            return value
        }

        internal fun u32(bytes: ByteArray, offset: Int): Long {
            var value = 0L
            for (i in 0 until 4) value = (value shl 8) or (bytes[offset + i].toLong() and 0xff)
            return value
        }

        internal fun requireAssignmentWireId(id: Int, field: String) {
            require(id in 0x01..0xff) { "$field wire id 0x%02x is unassigned (0x00 is reserved)".format(id) }
        }

        internal fun requireCompressedKey(key: ByteArray, field: String) {
            require(key.size == PUBKEY_SIZE) { "$field must be $PUBKEY_SIZE bytes (compressed secp256k1), got ${key.size}" }
            val prefix = key[0].toInt() and 0xff
            require(prefix == 0x02 || prefix == 0x03) {
                "$field must be a compressed secp256k1 point (0x02/0x03 prefix), got 0x%02x".format(prefix)
            }
        }

        internal fun iso4217(currency: ByteArray): Boolean =
            currency.size == CURRENCY_SIZE && currency.all { it in 'A'.code.toByte()..'Z'.code.toByte() }
    }
}

/** Known `asset` wire ids (`specs/deal-protocol.md` §3.1). */
enum class DealAsset(val wireId: Int) {
    USDT(0x01),
    BTC(0x02),   // reserved
    XMR(0x03),   // reserved
    ;

    companion object {
        fun fromWireId(wireId: Int): DealAsset? = entries.firstOrNull { it.wireId == wireId }
    }
}

/** Known `srcChainId` wire ids (phase 1 observes both from launch). */
enum class SourceChainId(val wireId: Int) {
    TRON(0x01),
    ETHEREUM(0x02),
    ;

    companion object {
        fun fromWireId(wireId: Int): SourceChainId? = entries.firstOrNull { it.wireId == wireId }
    }
}
