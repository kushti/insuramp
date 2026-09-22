package p2pgate.app.net

import kotlinx.serialization.Serializable

/**
 * Wire shapes for the operator backend's `/v1` APIs. Mirrors
 * `backend/src/main/kotlin/p2pgate/backend/api/Dtos.kt` field-for-field (the
 * backend module is the source of truth); unknown fields are ignored on read
 * and optional fields default, so the app tolerates DTO drift in both
 * directions.
 */
@Serializable
data class ErrorDto(val error: String)

@Serializable
data class QuoteDto(
    val id: String,
    val version: Long,
    val spreadBps: Int,
    val etaMinutes: Int,
    /** The cash currency this quote serves (3-letter uppercase code, e.g. INR). */
    val fiatCurrency: String,
    /** The seller's per-deal limits: deals must be within [minAmount, maxAmount]. */
    val minAmount: Long,
    val maxAmount: Long,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    /**
     * Seller-published meeting location (WGS84). Nullable: old backends omit
     * the fields entirely and sellers may keep a quote list-only — the buyer
     * app's map view skips those quotes and notes the missing location.
     */
    val lat: Double? = null,
    val lon: Double? = null,
)

@Serializable
data class QuoteFeedDto(val quotes: List<QuoteDto>)

@Serializable
data class CreateDealRequest(
    val quoteId: String,
    val amount: Long,
    val receiveAddress: String,
    val buyerPubKey: String,
    val fiatCurrency: String,
    /** Cash amount in whole basic units (no decimals) — pinned in the deal terms. */
    val fiatAmount: Long,
)

@Serializable
data class CreateDealResponse(val dealId: String, val dealToken: String)

@Serializable
data class DealDto(
    val dealId: String,
    val state: String,
    val amount: Long,
    val fiatAmount: Long,
    val fiatCurrency: String,
    /** The locked-collateral line reads the actual vault collateral — 1:1 ratio. */
    val insuredAmount: Long,
    val vaultBoxId: String? = null,
    val contested: Boolean = false,
    val createdAtEpochMs: Long,
    val fundedAtEpochMs: Long? = null,
    val reclaimDeadlineEpochMs: Long? = null,
    val claimMaturesAtEpochMs: Long? = null,
    val abandoned: Boolean = false,
    val terminal: Boolean = false,
    /**
     * The seller key (vault R5) the meeting gate verifies the handoff record
     * against. Optional because today's backend DTO does not expose it yet —
     * the meeting screen stays fail-closed ("cannot confirm — do not leave")
     * until the key is present.
     */
    val sellerPubKey: String? = null,
)

@Serializable
data class AttestationDto(
    val status: String,   // CONFIRMED | UNCONFIRMED
    /** The attested deal id hex — must match the backend's JSON field name. */
    val dealId: String? = null,
)

@Serializable
data class HandoffSubmitRequest(
    val recordHex: String,
    val gps: String? = null,
)

@Serializable
data class ClaimGuideDto(
    val dealId: String,
    val state: String,
    val handoffRecordHex: String,
    val buyerPubKey: String,
    val instructions: List<String>,
)

@Serializable
data class InfraSignalDto(val signal: String, val healthy: Boolean, val detail: String)

@Serializable
data class InfraDto(
    val paused: Boolean,
    val signals: List<InfraSignalDto> = emptyList(),
)

@Serializable
data class EventDto(val kind: String, val dealId: String? = null, val detail: String, val atEpochMs: Long)
