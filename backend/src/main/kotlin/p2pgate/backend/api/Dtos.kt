package p2pgate.backend.api

import kotlinx.serialization.Serializable

/** Wire shapes for the `/v1` APIs (`specs/operator-backend.md` §9). JSON only. */

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
    val maxAmount: Long,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long,
)

@Serializable
data class QuoteFeedDto(val quote: QuoteDto?)

@Serializable
data class CreateDealRequest(
    val quoteId: String,
    val amount: Long,
    val receiveAddress: String,
    val userPubKey: String,
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
    /** The insured badge reads the actual vault collateral (§5) — 1:1 ratio. */
    val insuredAmount: Long,
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
    val digest: String? = null,
    val srcTxId: String? = null,
    val srcBlockHeight: Long? = null,
    val srcBlockTime: Long? = null,
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
 * Claim guide (POST /v1/deals/{id}/claim): everything the user app needs to
 * build and broadcast the on-chain path-B tx itself (`specs/android-app.md`
 * §4.3 — the backend never broadcasts the user's claim).
 */
@Serializable
data class ClaimGuideDto(
    val dealId: String,
    val state: String,
    val handoffRecordHex: String,
    val fundedBox: ChainBoxDto,
    val courierPubKey: String,
    val userPubKey: String,
    val instructions: List<String>,
)

@Serializable
data class CourierJobDto(
    val dealId: String,
    val state: String,
    val amount: Long,
    val fiatAmount: Long,
    val fiatCurrency: String,
    val neighborhood: String,
)

@Serializable
data class CourierKeyDto(val dealId: String, val courierId: String, val courierPubKey: String)

@Serializable
data class HandoffTemplateDto(
    val dealId: String,
    val magic: String,
    val amount: Long,
    val fiatCurrency: String,
    val timestamp: Long,
    val courierIdHash: String,
    val qrPayloadPreview: String,
)

@Serializable
data class HandoffSubmitRequest(
    val recordHex: String,
    val gps: String? = null,
    val overrideReason: String? = null,
)

@Serializable
data class PanicRequest(val reason: String)

@Serializable
data class GeoConfirmRequest(val lat: Double, val lon: Double, val overrideReason: String? = null)

@Serializable
data class SyncItemDto(val dealId: String, val recordHex: String, val gps: String? = null)

@Serializable
data class CourierSyncRequest(val items: List<SyncItemDto>)

@Serializable
data class SyncResponse(val accepted: Int, val duplicates: Int, val rejected: Int)

@Serializable
data class LaneCardDto(
    val dealId: String,
    val amount: Long,
    val collateralTokenId: String,
    val createdAtEpochMs: Long,
    val fundedAtEpochMs: Long? = null,
    val reclaimDeadlineEpochMs: Long? = null,
    val claimMaturesAtEpochMs: Long? = null,
    val contested: Boolean = false,
    val hasHandoffRecord: Boolean = false,
    val vaultBoxId: String? = null,
    val exitPath: String,
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
data class PutQuoteRequest(val spreadBps: Int, val etaMinutes: Int, val maxAmount: Long)

@Serializable
data class PublishQuoteResponse(val published: Boolean, val quote: QuoteDto? = null, val reason: String? = null)

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
    val attestationDigest: String? = null,
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
