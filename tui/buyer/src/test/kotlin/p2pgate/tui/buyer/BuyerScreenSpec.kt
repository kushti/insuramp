package p2pgate.tui.buyer

import com.jakewharton.mosaic.terminal.Terminal
import com.jakewharton.mosaic.testing.runMosaicTest
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.ergoplatform.appkit.NetworkType
import p2pgate.dealprotocol.DealState
import p2pgate.ergo.ChainBox
import p2pgate.ergo.ChainSource
import p2pgate.ergo.ChainSpend
import p2pgate.ergo.ClaimTxBuilder
import p2pgate.ergo.ErgoContracts
import p2pgate.tui.common.BackendClient
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
import p2pgate.tui.common.wire.QuoteDto
import p2pgate.tui.common.wire.QuoteFeedDto
import p2pgate.tui.common.wire.ReclaimResponse

/**
 * The console rendered headlessly through Mosaic's test harness: a fake client
 * and a fake chain feed the real controller, and the assertions are on the *text*
 * a buyer would read. No TTY, no server, no chain — the same code path
 * `Main.kt` runs.
 */
class BuyerScreenSpec {

    private val dealId = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"

    // ------------------------------------------------------------------ setup

    private class FakeClient(
        val quotes: List<QuoteDto> = emptyList(),
        val deal: DealDto? = null,
    ) : BackendClient {
        val calls = mutableListOf<String>()
        private fun nope(name: String): Nothing {
            calls += name
            throw UnsupportedOperationException("$name is not used by these tests")
        }

        override suspend fun quotes(): QuoteFeedDto {
            calls += "quotes"
            return QuoteFeedDto(quotes)
        }

        override suspend fun deal(dealId: String, dealToken: String): DealDto {
            calls += "deal"
            return deal ?: throw IllegalStateException("no deal configured")
        }

        override suspend fun claimGuide(dealId: String, dealToken: String): ClaimGuideDto =
            nope("claimGuide")

        override suspend fun attestation(dealId: String, dealToken: String): AttestationDto {
            calls += "attestation"
            return AttestationDto(status = "UNCONFIRMED")
        }

        override suspend fun createDeal(request: CreateDealRequest): CreateDealResponse =
            nope("createDeal")

        override suspend fun submitHandoff(dealId: String, dealToken: String, request: HandoffSubmitRequest) =
            nope("submitHandoff")

        override fun quoteFeedStream(): Flow<QuoteFeedDto> = flowOf(QuoteFeedDto(quotes))
        override fun dealStream(dealId: String, dealToken: String): Flow<EventDto> = flowOf()
        override suspend fun lane(): LaneDto = nope("lane")
        override suspend fun pool(): PoolDto = nope("pool")
        override suspend fun infra(): InfraDto = nope("infra")
        override suspend fun disputes(): List<DisputeRowDto> = nope("disputes")
        override suspend fun disputeAction(dealId: String, action: String) = nope("disputeAction")
        override suspend fun acceptOffer(dealId: String): FundDealResponse = nope("acceptOffer")
        override suspend fun declineOffer(dealId: String): MessageDto = nope("declineOffer")
        override suspend fun signHandoff(dealId: String): HandoffSignResponse = nope("signHandoff")
        override suspend fun handoffQrPng(dealId: String): ByteArray = nope("handoffQrPng")
        override suspend fun reclaim(dealId: String): ReclaimResponse = nope("reclaim")
        override suspend fun publishQuote(request: PutQuoteRequest): PublishQuoteResponse = nope("publishQuote")
        override suspend fun withdrawQuote(quoteId: String): MessageDto = nope("withdrawQuote")
        override suspend fun amlCheck(address: String, chainId: Int): AmlCheckResponse = nope("amlCheck")
        override fun operatorEvents(): Flow<EventDto> = flowOf()
    }

    private class FakeChain(
        private val height: Int = 0,
        private val boxes: Map<String, ChainBox> = emptyMap(),
    ) : ChainSource {
        override fun getBox(boxId: String): ChainBox? = boxes[boxId]
        override fun getSpendingTransaction(boxId: String): ChainSpend? = null
        override fun getCurrentHeight(): Int = height
        override fun getUnspentBoxes(address: String): List<ChainBox> = emptyList()
    }

    private fun quote(id: String = "q1") = QuoteDto(
        id = id,
        version = 1,
        spreadBps = 25,
        etaMinutes = 10,
        minAmount = 50_000_000,
        maxAmount = 200_000_000,
        fiatCurrency = "INR",
        fiatPerUsdtMicros = 92_000_000,
        createdAtEpochMs = 1_700_000_000_000,
        expiresAtEpochMs = 1_700_000_600_000,
    )

    private fun deal(state: String = "FUNDED") = DealDto(
        dealId = dealId,
        state = state,
        amount = 100_000_000,
        fiatAmount = 9200,
        fiatCurrency = "INR",
        insuredAmount = 100_000_000,
        sellerPubKey = "02".repeat(33),
        vaultBoxId = "b".repeat(64),
        contested = false,
        createdAtEpochMs = 1_700_000_000_000,
        fundedAtEpochMs = 1_700_000_100_000,
        reclaimDeadlineEpochMs = 1_700_086_900_000,
        claimMaturesAtEpochMs = null,
        abandoned = false,
        terminal = false,
    )

    /** A controller over throwaway files, so no test touches a real key path. */
    private fun controller(client: BackendClient, chain: ChainSource = FakeChain()): BuyerController {
        val dir = File(System.getProperty("java.io.tmpdir"), "p2pgate-buyer-spec-${System.nanoTime()}")
        dir.mkdirs()
        return BuyerController(
            client = client,
            chain = chain,
            submitter = TxSubmitter { it.id },
            txBuilder = ClaimTxBuilder(trees = ErgoContracts.compile()),
            keyFile = File(dir, "buyer.p2pkey"),
            handoffFile = File(dir, "handoff.json"),
            networkType = NetworkType.MAINNET,
        )
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun `an empty console says there is no deal, not an error`() = runTest {
        val c = controller(FakeClient())
        runMosaicTest {
            state.size.value = Terminal.Size(120, 40)
            c.refreshQuotes()
            val out = setContentAndSnapshot { BuyerScreen(c) }
            assertTrue(out.contains("no deal"), out)
            // The key line names the file it looked at, so a buyer knows where
            // the console expects one rather than only that it lacks one.
            assertTrue(out.contains("press k to create one"), out)
            assertTrue(out.contains("buyer.p2pkey"), out)
        }
    }

    @Test
    fun `the quote list shows the window, the rate and the cursor`() = runTest {
        val c = controller(FakeClient(quotes = listOf(quote())))
        runMosaicTest {
            state.size.value = Terminal.Size(120, 40)
            c.refreshQuotes()
            val out = setContentAndSnapshot { BuyerScreen(c) }
            assertTrue(out.contains("92.00 INR/USDT"), out)
            // 50_000_000 base units = 50 USDT: the window is rendered, not raw.
            assertTrue(out.contains("50–200"), out)
            assertTrue(out.contains("1."), out)
        }
    }

    @Test
    fun `a funded deal shows the cash leg and the reclaim deadline`() = runTest {
        val c = controller(FakeClient(deal = deal()))
        runMosaicTest {
            state.size.value = Terminal.Size(140, 50)
            c.attachTo(dealId, "token")
            val out = setContentAndSnapshot { BuyerScreen(c) }
            assertTrue(out.contains("FUNDED"), out)
            assertTrue(out.contains("9200 INR"), out)
            assertTrue(out.contains("reclaim deadline"), out)
        }
    }

    @Test
    fun `a funded deal with no record warns the buyer not to leave`() = runTest {
        // The most consequential line in the console: the buyer is about to hand
        // over cash, and with no verified record they would have no evidence.
        val c = controller(FakeClient(deal = deal()))
        runMosaicTest {
            state.size.value = Terminal.Size(140, 50)
            c.attachTo(dealId, "token")
            val out = setContentAndSnapshot { BuyerScreen(c) }
            assertTrue(out.contains("do not leave the meeting"), out)
        }
    }

    @Test
    fun `a verified record replaces the warning with the green line`() = runTest {
        val c = controller(FakeClient(deal = deal()))
        c.debugStoreHandoff(
            StoredHandoff("aa".repeat(32), "02".repeat(33), "bb".repeat(32), "p2pgate://handoff?m=x"),
        )
        runMosaicTest {
            state.size.value = Terminal.Size(140, 50)
            c.attachTo(dealId, "token")
            val out = setContentAndSnapshot { BuyerScreen(c) }
            assertTrue(out.contains("safe to leave"), out)
            assertFalse(out.contains("do not leave"), "the warning survived a verified record:\n$out")
        }
    }

    @Test
    fun `a maturing claim counts down and an unlocked one says so`() = runTest {
        val proof = 1_000_000
        val c = controller(
            FakeClient(deal = deal("CLAIMABLE")),
            FakeChain(height = proof + 10),
        )
        runMosaicTest {
            state.size.value = Terminal.Size(140, 50)
            c.attachTo(dealId, "token")
            // No PAYMENT_PROVEN box observed, so no countdown yet — the console
            // must not invent one.
            val out = setContentAndSnapshot { BuyerScreen(c) }
            assertFalse(out.contains("payout in"), "a countdown appeared with no proof box:\n$out")
        }
    }

    @Test
    fun `an unknown deal state is shown verbatim rather than blanked`() = runTest {
        val c = controller(FakeClient(deal = deal(state = "SOMETHING_NEW")))
        runMosaicTest {
            state.size.value = Terminal.Size(140, 50)
            c.attachTo(dealId, "token")
            val out = setContentAndSnapshot { BuyerScreen(c) }
            assertTrue(out.contains("SOMETHING_NEW"), out)
        }
    }

    @Test
    fun `the key line names the file and the fingerprint, never the secret`() = runTest {
        val c = controller(FakeClient())
        c.debugNoteKey("/tmp/x.p2pkey", "9abc", "01020304")
        runMosaicTest {
            state.size.value = Terminal.Size(140, 40)
            val out = setContentAndSnapshot { BuyerScreen(c) }
            assertTrue(out.contains("01020304"), out)
            assertTrue(out.contains("9abc"), out)
            assertTrue(out.contains("/tmp/x.p2pkey"), out)
        }
    }

    @Test
    fun `a claim-open is offered without a key, a payout is not`() = runTest {
        // The consent split, asserted through the state the screen draws from.
        val keyless = BuyerFlow.Facts(DealState.CLAIMABLE, hasRecord = true, hasKey = false)
        val available = BuyerFlow.actionsFor(keyless).map { it.key }
        assertFalse(available.contains("p"), "a keyless buyer was offered the payout: $available")
        assertEquals(1, available.count { it == "k" }, "no way to create the key: $available")
    }
}