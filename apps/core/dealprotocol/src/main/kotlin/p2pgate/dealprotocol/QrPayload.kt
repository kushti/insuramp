package p2pgate.dealprotocol

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * QR payload codecs, `specs/deal-protocol.md` §3.3 — `p2pgate://` URIs carrying
 * base64url message bytes, plus the lenient address share.
 *
 * - `p2pgate://handoff?m=<base64url(handoff record)>` — seller → buyer at the
 *   meeting (the unsigned record; the seller signs once the buyer has validated
 *   it against the deal terms).
 * - Address share: `<address>` or `<address>?amount=<decimal>` — the buyer's
 *   USDT payout address, shared at QUOTED so R9's `recipientAddr` pins it.
 *
 * Base64 is the stdlib URL-safe variant **without padding** (no `+`/`/`/`=`
 * characters — QR-safe alphabet); decode is strict about the same. The exact
 * per-chain URI formats of the address share are an implementation detail of
 * `specs/android-app.md`; here it is parsed leniently and formatted exactly.
 */
@OptIn(ExperimentalEncodingApi::class)
object QrPayload {

    private const val HANDOFF_PREFIX = "p2pgate://handoff?m="

    /** URL-safe base64, padding absent both ways (`kotlin.io.encoding.Base64`). */
    private val base64url: Base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    // ---------- handoff (seller → buyer) ----------

    fun encodeHandoff(record: HandoffRecord): String = HANDOFF_PREFIX + base64url.encode(record.encode())

    fun decodeHandoff(payload: String): HandoffRecord = HandoffRecord.decode(extractM(payload, HANDOFF_PREFIX, "handoff"))

    // ---------- address share (buyer → seller, at QUOTED) ----------

    /** Formats exactly: `address`, or `address?amount=<decimal>` when [amount] is present. */
    fun formatAddress(address: String, amount: Long? = null): String {
        require(address.isNotEmpty()) { "address must not be empty" }
        require(address.indexOf('?') < 0 && address.indexOf('&') < 0 && address.indexOf('=') < 0) {
            "address must be the bare address (no query syntax)"
        }
        require(amount == null || amount >= 0) { "amount must be a non-negative integer, got $amount" }
        return if (amount == null) address else "$address?amount=$amount"
    }

    /** Lenient inverse of [formatAddress]: unknown query params are ignored,
     *  an unparseable amount is dropped (address still returned). */
    fun parseAddress(payload: String): AddressShare {
        val queryStart = payload.indexOf('?')
        if (queryStart < 0) return AddressShare(address = payload, amount = null)
        val address = payload.substring(0, queryStart)
        val amount = payload.substring(queryStart + 1)
            .split('&')
            .firstOrNull { it.startsWith("amount=") }
            ?.removePrefix("amount=")
            ?.toLongOrNull()
            ?.takeIf { it >= 0 }
        return AddressShare(address = address, amount = amount)
    }

    private fun extractM(payload: String, prefix: String, kind: String): ByteArray {
        require(payload.startsWith(prefix)) {
            "not a p2pgate://$kind QR payload (expected prefix \"$prefix\")"
        }
        val encoded = payload.removePrefix(prefix)
        require(encoded.isNotEmpty()) { "p2pgate://$kind QR payload is missing the m parameter" }
        return base64url.decode(encoded)
    }
}

/** A parsed address share ([QrPayload.formatAddress] output). */
data class AddressShare(val address: String, val amount: Long? = null)
