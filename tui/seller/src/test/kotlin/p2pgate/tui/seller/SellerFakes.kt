package p2pgate.tui.seller

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import p2pgate.tui.common.BackendClient
import p2pgate.tui.common.wire.AmlCheckResponse
import p2pgate.tui.common.wire.AttestationDto
import p2pgate.tui.common.wire.ChainBoxDto
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
import p2pgate.tui.common.wire.InfraSignalDto
import p2pgate.tui.common.wire.LaneCardDto
import p2pgate.tui.common.wire.LaneDto
import p2pgate.tui.common.wire.MessageDto
import p2pgate.tui.common.wire.PoolDto
import p2pgate.tui.common.wire.PublishQuoteResponse
import p2pgate.tui.common.wire.PutQuoteRequest
import p2pgate.tui.common.wire.QuoteFeedDto
import p2pgate.tui.common.wire.ReclaimResponse

/**
 * The console's test doubles, shared by every seller-console spec.
 *
 * This lives in one file on purpose: [BackendClient] has 25 members, and copying
 * a fake implementation per spec is how they drift apart and start failing for
 * reasons unrelated to the thing under test. Add a method here once.
 *
 * [failWith] makes every endpoint throw, which is how the status line's failure
 * path is exercised without a server that is actually down.
 */
internal class FakeClient(
    private val lanes: Map<String, List<LaneCardDto>> = emptyMap(),
    private val disputeRows: List<DisputeRowDto> = emptyList(),
) : BackendClient {

    /** Every call the console made, in order — what the specs assert against. */
    val calls = mutableListOf<String>()

    /** When set, every endpoint throws this. Set to exercise the failure path. */
    var failWith: String? = null

    private fun <T> answer(value: T): T {
        failWith?.let { throw IllegalStateException(it) }
        return value
    }

    override suspend fun lane() = answer(LaneDto(lanes)).also { calls += "lane" }
    override suspend fun pool() = answer(PoolDto(10, 4, 6, 40.0, 2)).also { calls += "pool" }
    override suspend fun infra() =
        answer(InfraDto(false, listOf(InfraSignalDto("explorer", true, "ok")), emptyList())).also { calls += "infra" }

    override suspend fun disputes() = answer(disputeRows).also { calls += "disputes" }
    override suspend fun disputeAction(dealId: String, action: String) {
        calls += "dispute:$action"
    }

    override suspend fun acceptOffer(dealId: String) =
        answer(FundDealResponse(dealId, "FUNDED", "tx123")).also { calls += "accept" }

    override suspend fun declineOffer(dealId: String) =
        answer(MessageDto("declined")).also { calls += "decline" }

    override suspend fun signHandoff(dealId: String) =
        answer(HandoffSignResponse(dealId, "PAYMENT_PENDING", "aa", "bb", "cc", "dd", "p2pgate://handoff?m=x"))
            .also { calls += "sign" }

    override suspend fun handoffQrPng(dealId: String): ByteArray = byteArrayOf(1, 2, 3)
    override suspend fun reclaim(dealId: String) =
        answer(ReclaimResponse(true, "tx9")).also { calls += "reclaim" }

    override suspend fun publishQuote(request: PutQuoteRequest) = PublishQuoteResponse(true, null, null)
    override suspend fun withdrawQuote(quoteId: String) = MessageDto("ok")
    override suspend fun amlCheck(address: String, chainId: Int) = AmlCheckResponse("PASS", "s", 0)
    override suspend fun quotes() = answer(QuoteFeedDto(emptyList())).also { calls += "quotes" }

    // The buyer half of the surface: the operator console never calls these, but
    // the interface requires them, so they answer canned shapes.
    override fun operatorEvents(): Flow<EventDto> = flowOf()
    override suspend fun createDeal(request: CreateDealRequest) = CreateDealResponse("d", "t")
    override suspend fun deal(dealId: String, dealToken: String) =
        DealDto(dealId, "FUNDED", 1, 1, "USD", 1, "02", null, false, 0, null, null, null, false, false)
    override suspend fun submitHandoff(dealId: String, dealToken: String, request: HandoffSubmitRequest) = Unit
    override suspend fun attestation(dealId: String, dealToken: String) = AttestationDto("UNCONFIRMED")
    override suspend fun claimGuide(dealId: String, dealToken: String) =
        ClaimGuideDto(
            dealId, "FUNDED", "aa",
            ChainBoxDto("b", "t", 0, 1, 1, "00", "a", emptyList(), emptyList()),
            "02", emptyList(),
        )

    override fun quoteFeedStream(): Flow<QuoteFeedDto> = flowOf()
    override fun dealStream(dealId: String, dealToken: String): Flow<EventDto> = flowOf()
}

/** A lane card with plausible timings, so countdown rendering has something to show. */
internal fun laneCard(
    state: String,
    id: String = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
    hasRecord: Boolean = false,
    box: String? = "cd",
) = LaneCardDto(
    dealId = id,
    amount = 500_000_000,
    fiatCurrency = "INR",
    collateralTokenId = "ab".repeat(32),
    createdAtEpochMs = 0,
    fundedAtEpochMs = 1_700_000_000_000,
    reclaimDeadlineEpochMs = 1_700_000_000_000 + 86_400_000,
    hasHandoffRecord = hasRecord,
    vaultBoxId = box,
    exitPath = "release",
).let { it }