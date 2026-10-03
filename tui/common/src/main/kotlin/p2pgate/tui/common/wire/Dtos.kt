package p2pgate.tui.common.wire

import kotlinx.serialization.Serializable

/**
 * Wire shapes for the operator backend's `/v1` APIs. Mirrors
 * `backend/src/main/kotlin/p2pgate/backend/api/Dtos.kt` field-for-field — the
 * backend module is the source of truth, and this file follows the same
 * convention as the Android app's `apps/app/.../net/Dtos.kt`. Unknown fields are
 * ignored on read and optional fields default, so a console tolerates DTO drift
 * in both directions.
 *
 * Only the buyer-facing (`/v1/deals`, `/v1/quotes`) and operator
 * (`/v1/dashboard`, `/v1/lane`, `/v1/pool`, `/v1/disputes`, `/v1/infra`)
 * surfaces the two consoles use are mirrored; an unused DTO is simply absent.
 */

@Serializable
data class ErrorDto(val error: String)

@Serializable
data class MessageDto(val message: String)

@Serializable
data class ReclaimResponse(val reclaimed: Boolean, val txId: String)

@Serializable
data class QuoteDto(
    val id: String,
    val version: Long,
    val spreadBps: Int,
    val etaMinutes: Int,
    /** Minimum deal size (base units) — the seller refuses smaller deals. */
    val minAmount: Long,
    val maxAmount: Long,
    /** The fiat leg's currency — 3-letter code, uppercase (e.g. INR, USD). */
    val fiatCurrency: String,
    /** Micros of [fiatCurrency] per 1 USDT, margin included. */
    val fiatPerUsdtMicros: Long,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    /** Optional seller meeting location (WGS-84); both set or neither. */
    val lat: Double? = null,
    val lon: Double? = null,
)

/** The quote feed: every active quote (several sellers may quote at once). */
@Serializable
data class QuoteFeedDto(val quotes: List<QuoteDto>)

@Serializable
data class CreateDealRequest(
    val quoteId: String,
    /** The USDT leg in base units (6 decimals) — the leg the quote's min/max bound. */
    val amount: Long,
    /** The buyer's USDT receive address, a TRON base58check string (`T…`). */
    val receiveAddress: String,
    val buyerPubKey: String,
    val fiatCurrency: String,
    /** Cash in whole basic units; re-derived from the quote's rate by the backend. */
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
    val insuredAmount: Long,
    /** The vault R5 key — what the handoff record's signature verifies against. */
    val sellerPubKey: String,
    val vaultBoxId: String? = null,
    val contested: Boolean = false,
    val createdAtEpochMs: Long,
    val fundedAtEpochMs: Long? = null,
    val reclaimDeadlineEpochMs: Long? = null,
    val claimMaturesAtEpochMs: Long? = null,
    val abandoned: Boolean = false,
    val terminal: Boolean = false,
)

@Serializable
data class AttestationDto(
    val status: String,   // CONFIRMED | UNCONFIRMED
    val dealId: String? = null,
)

@Serializable
data class ChainTokenDto(val tokenId: String, val amount: Long)

@Serializable
data class ChainBoxDto(
    val boxId: String,
    val transactionId: String,
    val index: Int,
    val value: Long,
    val creationHeight: Int,
    val ergoTreeHex: String,
    val address: String,
    val tokens: List<ChainTokenDto>,
    /** R4..R9 as raw-value hex; `null` for absent registers. */
    val registers: List<String?>,
    val spentTransactionId: String? = null,
)

/**
 * Claim guide (POST /v1/deals/{id}/claim): what the buyer needs to build and
 * broadcast the path-B tx itself. The backend never broadcasts a buyer claim.
 */
@Serializable
data class ClaimGuideDto(
    val dealId: String,
    val state: String,
    val handoffRecordHex: String,
    val fundedBox: ChainBoxDto,
    val buyerPubKey: String,
    val instructions: List<String>,
)

@Serializable
data class HandoffSubmitRequest(
    val recordHex: String,
    val gps: String? = null,
)

/**
 * Seller-meeting signing (POST /v1/dashboard/deals/{id}/handoff/sign): the
 * seller-signed P2PH record plus the `p2pgate://handoff?m=…` QR payload the
 * console renders for the buyer to scan. Accepted from FUNDED only.
 */
@Serializable
data class HandoffSignResponse(
    val dealId: String,
    val state: String,
    val recordHex: String,
    val signatureA: String,
    val signatureZ: String,
    val sellerPubKey: String,
    val qrPayload: String,
)

@Serializable
data class LaneCardDto(
    val dealId: String,
    val amount: Long,
    val fiatCurrency: String,
    val collateralTokenId: String,
    val createdAtEpochMs: Long,
    val fundedAtEpochMs: Long? = null,
    /** Offer TTL (QUOTED only): the originating quote's expiry. */
    val offerExpiresAtEpochMs: Long? = null,
    val reclaimDeadlineEpochMs: Long? = null,
    val claimMaturesAtEpochMs: Long? = null,
    val contested: Boolean = false,
    val hasHandoffRecord: Boolean = false,
    val vaultBoxId: String? = null,
    val exitPath: String,
)

/** Offer accept (`POST /v1/dashboard/deals/{id}/accept`): the vault was funded. */
@Serializable
data class FundDealResponse(
    val dealId: String,
    val state: String,
    val fundTxId: String,
    val vaultBoxId: String? = null,
)

@Serializable
data class LaneDto(val lanes: Map<String, List<LaneCardDto>>)

@Serializable
data class PoolDto(
    val mixReady: Long,
    val locked: Long,
    val free: Long,
    val utilizationPct: Double,
    val openDeals: Int,
)

@Serializable
data class PutQuoteRequest(
    val spreadBps: Int,
    val etaMinutes: Int,
    val minAmount: Long,
    val maxAmount: Long,
    val fiatCurrency: String,
    val fiatPerUsdtMicros: Long,
    val lat: Double? = null,
    val lon: Double? = null,
)

@Serializable
data class PublishQuoteResponse(
    val published: Boolean,
    val quote: QuoteDto? = null,
    val reason: String? = null,
)

@Serializable
data class AmlCheckRequest(val address: String, val chainId: Int)

@Serializable
data class AmlCheckResponse(val decision: String, val scorerId: String, val atEpochMs: Long)

@Serializable
data class DisputeRowDto(
    val dealId: String,
    val state: String,
    val openedAtEpochMs: Long,
    val maturesAtEpochMs: Long,
    val contested: Boolean,
    val handoffRecordRef: String? = null,
    val geoRef: String? = null,
    val oracleConfirmed: Boolean,
    val attestationDealId: String? = null,
    val actioned: Boolean,
    val action: String? = null,
)

@Serializable
data class InfraSignalDto(val signal: String, val healthy: Boolean, val detail: String)

@Serializable
data class PauseRecordDto(val cause: String, val startedAtEpochMs: Long, val endedAtEpochMs: Long? = null)

@Serializable
data class InfraDto(
    val paused: Boolean,
    val signals: List<InfraSignalDto>,
    val pauseHistory: List<PauseRecordDto>,
)

@Serializable
data class EventDto(val kind: String, val dealId: String? = null, val detail: String, val atEpochMs: Long)
