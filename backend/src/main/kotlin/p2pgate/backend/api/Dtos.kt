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
    /** Minimum deal size (base units) — the seller refuses smaller deals. */
    val minAmount: Long,
    val maxAmount: Long,
    /** The fiat leg's currency — 3-letter code, uppercase (e.g. INR, USD). */
    val fiatCurrency: String,
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
    val amount: Long,
    val receiveAddress: String,
    val buyerPubKey: String,
    /** The fiat leg's currency — must match the quote's currency. */
    val fiatCurrency: String,
    /** Cash amount in whole basic units (no decimals) — the deal terms pin it. */
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
    /** The insured badge reads the actual vault collateral (§5) — 1:1 ratio. */
    val insuredAmount: Long,
    /**
     * The deal's seller key, compressed secp256k1 hex — the vault R5 key the
     * buyer app verifies the handoff record's signature against (path B).
     */
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
 * Claim guide (POST /v1/deals/{id}/claim): everything the buyer app needs to
 * build and broadcast the on-chain path-B tx itself (`specs/android-app.md`
 * §4.3 — the backend never broadcasts the buyer's claim). The record's signer
 * is identified by the seller key already pinned in the vault (R5).
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

/**
 * Handoff-record upload (POST /v1/deals/{id}/handoff): the buyer submits the
 * seller-signed P2PH record obtained at the meeting; the signer is identified
 * by the seller key pinned in the vault, so no extra credential is needed.
 */
@Serializable
data class HandoffSubmitRequest(
    val recordHex: String,
    val gps: String? = null,
)

/**
 * Seller-meeting signing (POST /v1/dashboard/deals/{id}/handoff/sign): the
 * 52-byte P2PH record signed under the vault's R5 seller key (t/3407 Schnorr),
 * plus the `p2pgate://handoff?m=...` QR payload the dashboard renders for the
 * buyer to scan. Accepted from FUNDED only: the signature is the
 * cash-collection witness and drives the deal to PAYMENT_PENDING; the response
 * [state] is always PAYMENT_PENDING on success.
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
    /** Minimum deal size (base units) — required, positive, ≤ maxAmount. */
    val minAmount: Long,
    val maxAmount: Long,
    /** The fiat leg's currency — exactly 3 letters (normalized to uppercase). */
    val fiatCurrency: String,
    /** Optional seller meeting location (WGS-84); both set or neither. */
    val lat: Double? = null,
    val lon: Double? = null,
)

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
