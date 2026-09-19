package p2pgate.ergo

import p2pgate.dealprotocol.DealTerms
import p2pgate.dealprotocol.SourceChainId

/**
 * The 112-byte payment-proof payload attested by the phase-1 oracle
 * (`specs/oracle-integration.md` §2.2 — the permanent digest format, unchanged
 * by the phase-2 guard-threshold upgrade). On-ramp semantics: the payload
 * describes the SELLER's USDT transfer to the buyer's receive address, and the
 * vault contract checks `dealId`/`srcChainId`/`tokenId`/`recipient`/`amount`
 * against its R4/R9 registers in the release spend (paths C/C′); `srcTxId`,
 * `srcBlockHeight`, `srcBlockTime` ride along for audit and are bound only by
 * the oracle's NFT custody (the payload rides in the oracle box's R4 as a
 * release data input; a data input's script never executes).
 *
 * Layout (all integers big-endian):
 *
 * ```
 * | 0    version        1 B   0x01
 * | 1    dealId         32 B   blake2b256(deal terms)
 * | 33   srcChainId     1 B    0x01 Tron, 0x02 Ethereum (deal-terms registry)
 * | 34   tokenId        1 B    0x01 USDT (mirrors the deal-terms asset byte)
 * | 35   recipient      21 B   left-padded: 21 B Tron, 20 B Ethereum (right-aligned)
 * | 56   amount         8 B    uint64, USDT base units (exact amount, no partials)
 * | 64   srcTxId        32 B   source-chain tx hash, opaque bytes
 * | 96   srcBlockHeight 8 B    uint64
 * | 104  srcBlockTime   8 B    uint64, unix seconds
 * ```
 *
 * `digest() = blake2b256(encode())`.
 */
class PaymentAttestation(
    /** Format version — must be 1. */
    val version: Int = VERSION,
    /** `blake2b256(deal terms)` — binds the proof to one deal. */
    val dealId: ByteArray,
    /** Source-chain wire id (deal-terms registry). */
    val srcChainId: Int,
    /** Token wire id on that chain (mirrors [DealTerms.asset]). */
    val tokenId: Int,
    /** 21-byte left-padded source-chain address payload — the buyer's USDT address. */
    val recipient: ByteArray,
    /** uint64 in USDT base units; exact-amount deals only. */
    val amount: Long,
    /** Source-chain tx hash (opaque; not bound on-chain — see class doc). */
    val srcTxId: ByteArray,
    /** Source-chain block height (uint64). */
    val srcBlockHeight: Long,
    /** Source-chain block time, unix seconds (uint64). */
    val srcBlockTime: Long,
) {

    init {
        require(version == VERSION) { "unsupported payload version $version (expected $VERSION)" }
        require(dealId.size == 32) { "dealId must be 32 bytes, got ${dealId.size}" }
        require(srcChainId in 0x00..0xff) { "srcChainId must be a wire byte, got $srcChainId" }
        require(tokenId in 0x01..0xff) { "tokenId wire byte must be assigned (0x00 reserved), got $tokenId" }
        require(recipient.size == RECIPIENT_SIZE) { "recipient must be $RECIPIENT_SIZE bytes, got ${recipient.size}" }
        require(amount >= 0) { "amount must be a uint64 (non-negative), got $amount" }
        require(srcTxId.size == 32) { "srcTxId must be 32 bytes, got ${srcTxId.size}" }
        require(srcBlockHeight >= 0) { "srcBlockHeight must be a uint64 (non-negative), got $srcBlockHeight" }
        require(srcBlockTime >= 0) { "srcBlockTime must be a uint64 (non-negative), got $srcBlockTime" }
        if (srcChainId == SourceChainId.ETHEREUM.wireId) {
            require(recipient[0] == 0.toByte()) {
                "Ethereum recipient must be a 20-byte address right-aligned (left-padded with 0x00) in the 21-byte field"
            }
        }
    }

    /** The exact 112-byte wire form carried in the oracle data input's R4 of the release tx. */
    fun encode(): ByteArray {
        val out = ByteArray(SIZE)
        out[0] = version.toByte()
        dealId.copyInto(out, 1)
        out[33] = srcChainId.toByte()
        out[34] = tokenId.toByte()
        recipient.copyInto(out, 35)
        putU64(out, 56, amount)
        srcTxId.copyInto(out, 64)
        putU64(out, 96, srcBlockHeight)
        putU64(out, 104, srcBlockTime)
        return out
    }

    /** `blake2b256(encode())` — the digest the phase-2 guards will threshold-sign. */
    fun digest(): ByteArray = SchnorrVerifier.blake2b256(encode())

    /**
     * Field-vs-deal-terms cross-check (`specs/oracle-integration.md` §2.2,
     * "set at funding" column): the funding-set fields must equal what the deal
     * terms hash binds, with [recipientAddr] the buyer's raw source-chain address
     * payload (padded per chain, [padRecipient]).
     */
    fun matches(dealTerms: DealTerms, recipientAddr: ByteArray): Boolean =
        dealId.contentEquals(dealTerms.dealId) &&
            srcChainId == dealTerms.srcChainId &&
            tokenId == dealTerms.asset &&
            amount == dealTerms.amount &&
            recipient.contentEquals(padRecipient(recipientAddr, dealTerms.srcChainId))

    companion object {
        /** Payload format version (bump on layout change). */
        const val VERSION: Int = 1

        /** Total payload size in bytes. */
        const val SIZE: Int = 112

        /** Recipient field size (padded form). */
        const val RECIPIENT_SIZE: Int = 21

        /**
         * Left-pads a raw source-chain address payload into the 21-byte field:
         * Tron payloads are 21 bytes (no padding); Ethereum payloads are 20
         * bytes, right-aligned (one leading 0x00). Reserved chain ids carry the
         * raw 21-byte form. Mirrors `specs/oracle-integration.md` §2.2.
         */
        fun padRecipient(raw: ByteArray, srcChainId: Int): ByteArray = when (srcChainId) {
            SourceChainId.TRON.wireId -> {
                require(raw.size == RECIPIENT_SIZE) {
                    "Tron address payload must be $RECIPIENT_SIZE bytes, got ${raw.size}"
                }
                raw.copyOf()
            }
            SourceChainId.ETHEREUM.wireId -> {
                require(raw.size == RECIPIENT_SIZE - 1) {
                    "Ethereum address payload must be ${RECIPIENT_SIZE - 1} bytes, got ${raw.size}"
                }
                byteArrayOf(0) + raw
            }
            else -> {
                require(raw.size == RECIPIENT_SIZE) {
                    "reserved srcChainId 0x%02x expects a $RECIPIENT_SIZE-byte payload, got ${raw.size}".format(srcChainId)
                }
                raw.copyOf()
            }
        }

        /**
         * The R9 funding binding the vault pins at funding
         * (`specs/vault-contract.md` §3.1, 31 B): `srcChainId(1) | tokenId(1) |
         * recipientAddr(21, padded) | expectedAmount(8, big-endian)`. The
         * release-path digest fields are checked against exactly these bytes.
         */
        fun fundingBinding(srcChainId: Int, tokenId: Int, recipient21: ByteArray, amount: Long): ByteArray {
            require(recipient21.size == RECIPIENT_SIZE) { "recipient must be the padded $RECIPIENT_SIZE-byte form" }
            require(amount >= 0) { "amount must be a uint64 (non-negative), got $amount" }
            return byteArrayOf(srcChainId.toByte(), tokenId.toByte()) + recipient21 + u64(amount)
        }

        /** Decodes and validates the 112-byte wire form (throws on any defect). */
        fun decode(bytes: ByteArray): PaymentAttestation {
            require(bytes.size == SIZE) { "payment-proof payload must be exactly $SIZE bytes, got ${bytes.size}" }
            return PaymentAttestation(
                version = bytes[0].toInt() and 0xff,
                dealId = bytes.copyOfRange(1, 33),
                srcChainId = bytes[33].toInt() and 0xff,
                tokenId = bytes[34].toInt() and 0xff,
                recipient = bytes.copyOfRange(35, 56),
                amount = u64(bytes, 56),
                srcTxId = bytes.copyOfRange(64, 96),
                srcBlockHeight = u64(bytes, 96),
                srcBlockTime = u64(bytes, 104),
            )
        }

        /**
         * Builds an attestation for a deal from its terms and the observed
         * source-chain event (the attestation-time fields). The funding-set
         * fields are derived from [dealTerms]; [recipientAddr] is the buyer's
         * raw USDT address payload.
         */
        fun build(
            dealTerms: DealTerms,
            recipientAddr: ByteArray,
            srcTxId: ByteArray,
            srcBlockHeight: Long,
            srcBlockTime: Long,
        ): PaymentAttestation = PaymentAttestation(
            dealId = dealTerms.dealId,
            srcChainId = dealTerms.srcChainId,
            tokenId = dealTerms.asset,
            recipient = padRecipient(recipientAddr, dealTerms.srcChainId),
            amount = dealTerms.amount,
            srcTxId = srcTxId,
            srcBlockHeight = srcBlockHeight,
            srcBlockTime = srcBlockTime,
        )

        internal fun putU64(out: ByteArray, offset: Int, value: Long) {
            for (i in 0 until 8) out[offset + i] = (value shr (56 - 8 * i)).toByte()
        }

        internal fun u64(bytes: ByteArray, offset: Int): Long {
            var value = 0L
            for (i in 0 until 8) value = (value shl 8) or (bytes[offset + i].toLong() and 0xff)
            return value
        }

        internal fun u64(value: Long): ByteArray = ByteArray(8) { i -> (value shr (56 - 8 * i)).toByte() }
    }
}
