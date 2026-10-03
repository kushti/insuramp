package p2pgate.tui.common

import kotlinx.coroutines.flow.Flow
import p2pgate.tui.common.wire.AmlCheckResponse
import p2pgate.tui.common.wire.AttestationDto
import p2pgate.tui.common.wire.ClaimGuideDto
import p2pgate.tui.common.wire.CreateDealRequest
import p2pgate.tui.common.wire.CreateDealResponse
import p2pgate.tui.common.wire.DealDto
import p2pgate.tui.common.wire.DisputeRowDto
import p2pgate.tui.common.wire.EventDto
import p2pgate.tui.common.wire.FundDealResponse
import p2pgate.tui.common.wire.HandoffSignResponse
import p2pgate.tui.common.wire.HandoffSubmitRequest
import p2pgate.tui.common.wire.InfraDto
import p2pgate.tui.common.wire.LaneDto
import p2pgate.tui.common.wire.MessageDto
import p2pgate.tui.common.wire.PoolDto
import p2pgate.tui.common.wire.PublishQuoteResponse
import p2pgate.tui.common.wire.PutQuoteRequest
import p2pgate.tui.common.wire.QuoteFeedDto
import p2pgate.tui.common.wire.ReclaimResponse

/**
 * The operator backend's `/v1` surface, as the two consoles need it
 * (`specs/operator-backend.md` §9). Split in two by credential:
 *
 *  - **buyer** endpoints (`/v1/quotes`, `/v1/deals/…`) authenticate with the
 *    per-deal token the backend mints at deal creation;
 *  - **operator** endpoints (`/v1/dashboard/…`, `/v1/lane`, `/v1/pool`,
 *    `/v1/disputes`, `/v1/infra`) authenticate with `P2P_OPERATOR_KEY`.
 *
 * An interface, not a concrete client, so screens and view models can be tested
 * against fakes without a server (the Ktor `MockEngine` covers the client itself).
 */
interface BackendClient {

    // ---------------------------------------------------------------- buyer-facing

    suspend fun quotes(): QuoteFeedDto

    suspend fun createDeal(request: CreateDealRequest): CreateDealResponse

    suspend fun deal(dealId: String, dealToken: String): DealDto

    suspend fun submitHandoff(dealId: String, dealToken: String, request: HandoffSubmitRequest)

    suspend fun attestation(dealId: String, dealToken: String): AttestationDto

    suspend fun claimGuide(dealId: String, dealToken: String): ClaimGuideDto

    /** Live quote feed (`/v1/quotes/stream`) — reconnecting. */
    fun quoteFeedStream(): Flow<QuoteFeedDto>

    /** Per-deal state-change stream (`/v1/deals/{id}/stream`) — reconnecting. */
    fun dealStream(dealId: String, dealToken: String): Flow<EventDto>

    // ---------------------------------------------------------------- operator

    suspend fun lane(): LaneDto

    suspend fun pool(): PoolDto

    suspend fun infra(): InfraDto

    suspend fun disputes(): List<DisputeRowDto>

    /** Dispute action: `contest` / `accept` / `investigate` (`/v1/disputes/{id}/{action}`). */
    suspend fun disputeAction(dealId: String, action: String)

    suspend fun acceptOffer(dealId: String): FundDealResponse

    suspend fun declineOffer(dealId: String): MessageDto

    /** Sign the meeting's handoff record — accepted from FUNDED only. */
    suspend fun signHandoff(dealId: String): HandoffSignResponse

    /** PNG bytes of the handoff QR (`/v1/dashboard/deals/{id}/handoff/qr.png`). */
    suspend fun handoffQrPng(dealId: String): ByteArray

    suspend fun reclaim(dealId: String): ReclaimResponse

    suspend fun publishQuote(request: PutQuoteRequest): PublishQuoteResponse

    suspend fun withdrawQuote(quoteId: String): MessageDto

    suspend fun amlCheck(address: String, chainId: Int): AmlCheckResponse

    /** Operator event stream (`/v1/events`) — reconnecting. */
    fun operatorEvents(): Flow<EventDto>
}
