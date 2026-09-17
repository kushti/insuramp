package p2pgate.ergo

import org.junit.jupiter.api.Test
import p2pgate.contracts.ContractParams
import p2pgate.dealprotocol.DealEvent
import p2pgate.dealprotocol.DealState
import p2pgate.dealprotocol.DealStateMachine
import p2pgate.dealprotocol.ProtocolConstants
import p2pgate.dealprotocol.TransitionOutcome
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Vault box monitoring, `specs/android-app.md` §4.2: every classification row
 * (unspent, claim successor, release spend, reclaim spend, claim-paid spend,
 * timeout detection) plus the contested-claim sequence through the real
 * [DealStateMachine], proving the v2 contested-flag semantics end to end.
 */
class VaultBoxTrackerSpec {

    private val f = ErgoTestFixtures
    private val terms = f.dealTerms()
    private val fundedBoxId = "aa".repeat(32)
    private val fundedAt = Instant.ofEpochSecond(1_700_000_000L)
    private val now = fundedAt.plusSeconds(300)

    private val sellerTreeHex = ErgoValues.treeHex(ErgoValues.p2pkTree(f.sellerKeys.pubKeyCompressed))
    private val userTreeHex = ErgoValues.treeHex(ErgoValues.p2pkTree(f.userKeys.pubKeyCompressed))
    private val oracleNftHex = Base16.encode(f.trees.oracleNftId)
    private val timeoutHeight = ErgoTestFixtures.CREATION_HEIGHT + ContractParams.RECLAIM_TIMEOUT_BLOCKS

    /** In-memory [ChainSource] — pollOnce's view of the chain. */
    private class FakeChain : ChainSource {
        val boxes = mutableMapOf<String, ChainBox>()
        val spends = mutableMapOf<String, ChainSpend>()

        override fun getBox(boxId: String): ChainBox? = boxes[boxId]
        override fun getSpendingTransaction(boxId: String): ChainSpend? =
            boxes[boxId]?.spentTransactionId?.let { spends[it] }

        override fun getCurrentHeight(): Int = 0
        override fun getUnspentBoxes(address: String): List<ChainBox> =
            boxes.values.filter { it.address == address && it.spentTransactionId == null }
    }

    private fun tracker(chain: FakeChain = FakeChain()) =
        VaultBoxTracker(chain, f.trees, fundedAt)

    private fun fundedBox(spentTxId: String? = null) = f.fundedChainBox(terms, boxId = fundedBoxId, spentTxId = spentTxId)

    private fun payoutBox(treeHex: String, boxId: String) = ChainBox(
        boxId = boxId,
        transactionId = "ee".repeat(32),
        index = 0,
        value = f.BOX_VALUE_NANO_ERG,
        creationHeight = timeoutHeight,
        ergoTreeHex = treeHex,
        address = "payout",
        tokens = listOf(ChainToken(f.useTokenIdHex, f.DEAL_AMOUNT)),
        registers = List(6) { null },
    )

    private fun provenSuccessorBox() = ChainBox(
        boxId = "dd".repeat(32),
        transactionId = "ef".repeat(32),
        index = 0,
        value = f.BOX_VALUE_NANO_ERG,
        creationHeight = 1500,
        ergoTreeHex = f.trees.provenPropositionHex,
        address = f.trees.provenAddress.toString(),
        tokens = listOf(ChainToken(f.useTokenIdHex, f.DEAL_AMOUNT)),
        registers = listOf(
            ChainRegister.CollBytes(terms.dealId),
            ChainRegister.CollBytes(f.sellerKeys.pubKeyCompressed),
            ChainRegister.CollBytes(f.userKeys.pubKeyCompressed),
            ChainRegister.Int64(f.packInts(1500, 0)),
            ChainRegister.CollBytes(ByteArray(32) { 7 }),
            ChainRegister.CollBytes(f.fundingBinding()),
        ),
    )

    private fun spendOf(
        tracked: ChainBox,
        height: Int,
        outputs: List<ChainBox>,
        inputTokens: List<String> = emptyList(),
    ): Pair<String, ChainSpend> {
        val txId = "b1".repeat(32)
        return txId to ChainSpend(txId, height, outputs, inputTokens)
    }

    // ---------------------------------------------------------------- §4.2 rows (classify)

    @Test
    fun `unspent FUNDED box within the deal window yields no events`() {
        val events = tracker().classify(VaultBoxTracker.VaultBoxState.Unspent(fundedBox()), now)
        assertEquals(emptyList(), events)
    }

    @Test
    fun `unspent FUNDED box past RECLAIM_TIMEOUT yields ReclaimTimeoutElapsed`() {
        val late = fundedAt.plus(ProtocolConstants.RECLAIM_TIMEOUT).plusSeconds(60)
        val events = tracker().classify(VaultBoxTracker.VaultBoxState.Unspent(fundedBox()), late)
        assertEquals(listOf(DealEvent.ReclaimTimeoutElapsed(late)), events)
    }

    @Test
    fun `unspent PAYMENT_PROVEN box yields ClaimOpened`() {
        val proven = f.provenChainBox(terms)
        val events = tracker().classify(VaultBoxTracker.VaultBoxState.Unspent(proven), now)
        assertEquals(listOf(DealEvent.ClaimOpened(now)), events)
    }

    @Test
    fun `spend into a PAYMENT_PROVEN successor yields ClaimOpened`() {
        val (txId, spend) = spendOf(fundedBox(), 1500, listOf(provenSuccessorBox()))
        val events = tracker().classify(VaultBoxTracker.VaultBoxState.Spent(fundedBox(txId), spend), now)
        assertEquals(listOf(DealEvent.ClaimOpened(now)), events)
    }

    @Test
    fun `spend paying the seller with the oracle box among inputs yields ReleaseObserved`() {
        val (txId, spend) = spendOf(
            fundedBox(), 1400, // before the reclaim timeout
            listOf(payoutBox(sellerTreeHex, "c1".repeat(32))),
            inputTokens = listOf(oracleNftHex),
        )
        val events = tracker().classify(VaultBoxTracker.VaultBoxState.Spent(fundedBox(txId), spend), now)
        assertEquals(listOf(DealEvent.ReleaseObserved), events)
    }

    @Test
    fun `spend paying the seller past the timeout without the oracle input yields ReclaimTimeoutElapsed`() {
        val (txId, spend) = spendOf(
            fundedBox(), timeoutHeight + 1,
            listOf(payoutBox(sellerTreeHex, "c2".repeat(32))),
        )
        val events = tracker().classify(VaultBoxTracker.VaultBoxState.Spent(fundedBox(txId), spend), now)
        assertEquals(listOf(DealEvent.ReclaimTimeoutElapsed(now)), events)
    }

    @Test
    fun `seller payout before the timeout without the oracle input still reads as release`() {
        // A pre-timeout seller payout without the oracle box cannot be a valid
        // reclaim — the height rule alone must not misclassify it.
        val (txId, spend) = spendOf(fundedBox(), 1400, listOf(payoutBox(sellerTreeHex, "c3".repeat(32))))
        val events = tracker().classify(VaultBoxTracker.VaultBoxState.Spent(fundedBox(txId), spend), now)
        assertEquals(listOf(DealEvent.ReleaseObserved), events)
    }

    @Test
    fun `C-prime counter past the timeout still reads as release when the oracle box is an input`() {
        val proven = f.provenChainBox(terms, proofHeight = timeoutHeight - 10)
        val (txId, spend) = spendOf(
            proven, timeoutHeight + 5, // late contest, but the oracle input marks it
            listOf(payoutBox(sellerTreeHex, "c4".repeat(32))),
            inputTokens = listOf(oracleNftHex),
        )
        val events = tracker().classify(VaultBoxTracker.VaultBoxState.Spent(proven.copySpent(txId), spend), now)
        assertEquals(listOf(DealEvent.ReleaseObserved), events)
    }

    @Test
    fun `spend paying the user yields ClaimPaid`() {
        val proven = f.provenChainBox(terms)
        val (txId, spend) = spendOf(proven, 2000, listOf(payoutBox(userTreeHex, "c5".repeat(32))))
        val events = tracker().classify(VaultBoxTracker.VaultBoxState.Spent(proven.copySpent(txId), spend), now)
        assertEquals(listOf(DealEvent.ClaimPaid), events)
    }

    @Test
    fun `unrecognized spend yields no events`() {
        val foreign = ChainBox(
            boxId = "c6".repeat(32),
            transactionId = "e1".repeat(32),
            index = 0,
            value = f.BOX_VALUE_NANO_ERG,
            creationHeight = 1500,
            ergoTreeHex = ErgoValues.treeHex(ErgoValues.p2pkTree(TestKeys.of(0x4242).pubKeyCompressed)),
            address = "foreign",
            tokens = listOf(ChainToken(f.useTokenIdHex, f.DEAL_AMOUNT)),
            registers = List(6) { null },
        )
        val (txId, spend) = spendOf(fundedBox(), 1500, listOf(foreign))
        val events = tracker().classify(VaultBoxTracker.VaultBoxState.Spent(fundedBox(txId), spend), now)
        assertEquals(emptyList(), events)
    }

    private fun ChainBox.copySpent(txId: String) = ChainBox(
        boxId, transactionId, index, value, creationHeight, ergoTreeHex, address, tokens, registers, txId,
    )

    // ---------------------------------------------------------------- pollOnce

    @Test
    fun `pollOnce maps an unspent box to no events and an unknown box to no events`() {
        val chain = FakeChain()
        val t = tracker(chain)
        assertEquals(emptyList(), t.pollOnce(fundedBoxId, now)) // unknown box
        chain.boxes[fundedBoxId] = fundedBox()
        assertEquals(emptyList(), t.pollOnce(fundedBoxId, now)) // unspent, in window
    }

    @Test
    fun `pollOnce follows the spend to the spending transaction`() {
        val chain = FakeChain()
        val (txId, spend) = spendOf(fundedBox(), 1500, listOf(provenSuccessorBox()))
        chain.boxes[fundedBoxId] = fundedBox(txId)
        chain.spends[txId] = spend
        val events = tracker(chain).pollOnce(fundedBoxId, now)
        assertEquals(listOf(DealEvent.ClaimOpened(now)), events)
    }

    // ---------------------------------------------------------------- end-to-end through the state machine

    private fun advanced(machine: DealStateMachine, event: DealEvent): DealStateMachine {
        val outcome = machine.transition(event)
        assertIs<TransitionOutcome.Advanced>(outcome, "expected advance on $event from ${machine.state}")
        return outcome.machine
    }

    @Test
    fun `dispute lifecycle pays the user end to end`() {
        val chain = FakeChain()
        val t = tracker(chain)
        chain.boxes[fundedBoxId] = fundedBox()

        var machine = DealStateMachine.initial()
        machine = advanced(machine, DealEvent.VaultFunded(fundedAt))
        machine = advanced(machine, DealEvent.CashCollected(fundedAt.plusSeconds(60), fundedAt.plusSeconds(60)))
        assertEquals(DealState.PAYMENT_PENDING, machine.state)

        // User opens the claim: FUNDED box is spent into the PAYMENT_PROVEN box.
        val (openTxId, openSpend) = spendOf(fundedBox(), 1500, listOf(provenSuccessorBox()))
        chain.boxes[fundedBoxId] = fundedBox(openTxId)
        chain.spends[openTxId] = openSpend
        machine = advanced(machine, t.pollOnce(fundedBoxId, now).single())
        assertEquals(DealState.CLAIM_OPENED, machine.state)
        val claimObservedAt = now

        // Maturation passes (12h after the claim landed).
        machine = advanced(
            machine,
            DealEvent.ClaimMatured(claimObservedAt.plus(ProtocolConstants.CLAIM_MATURATION).plusSeconds(1)),
        )
        assertEquals(DealState.CLAIMABLE, machine.state)

        // Path D spend pays the user.
        val proven = f.provenChainBox(terms, boxId = "dd".repeat(32), spentTxId = "b9".repeat(32))
        chain.boxes["dd".repeat(32)] = proven
        chain.spends["b9".repeat(32)] = ChainSpend(
            "b9".repeat(32), 2000, listOf(payoutBox(userTreeHex, "c7".repeat(32))),
        )
        val events = t.pollOnce("dd".repeat(32), now)
        machine = advanced(machine, events.single())
        assertEquals(DealState.CLAIMED, machine.state)
        assertTrue(machine.state.isTerminal)
    }

    @Test
    fun `contested claim the seller counters with the oracle digest alone resolves to RELEASED`() {
        val chain = FakeChain()
        val t = tracker(chain)
        chain.boxes[fundedBoxId] = fundedBox()

        var machine = DealStateMachine.initial()
        machine = advanced(machine, DealEvent.VaultFunded(fundedAt))
        machine = advanced(machine, DealEvent.CashCollected(fundedAt.plusSeconds(60), fundedAt.plusSeconds(60)))
        machine = advanced(machine, DealEvent.PaymentConfirmed)
        assertEquals(DealState.PAYMENT_CONFIRMED, machine.state)

        // Without-cause claim from PAYMENT_CONFIRMED.
        val (openTxId, openSpend) = spendOf(fundedBox(), 1500, listOf(provenSuccessorBox()))
        chain.boxes[fundedBoxId] = fundedBox(openTxId)
        chain.spends[openTxId] = openSpend
        machine = advanced(machine, t.pollOnce(fundedBoxId, now).single())
        assertEquals(DealState.CLAIM_OPENED, machine.state)

        // The oracle signal lands while the claim is open: contested, not rejected.
        val contested = machine.transition(DealEvent.PaymentConfirmed)
        assertIs<TransitionOutcome.Advanced>(contested)
        machine = contested.machine
        assertEquals(DealState.CLAIM_OPENED, machine.state)
        assertTrue(machine.claimContested, "the claim must be marked contested (v2 semantics)")

        // The seller's path C′ counter-spend (oracle box as input) releases the vault.
        val (releaseTxId, releaseSpend) = spendOf(
            f.provenChainBox(terms), 1600,
            listOf(payoutBox(sellerTreeHex, "c8".repeat(32))),
            inputTokens = listOf(oracleNftHex),
        )
        chain.boxes["dd".repeat(32)] = f.provenChainBox(terms, boxId = "dd".repeat(32), spentTxId = releaseTxId)
        chain.spends[releaseTxId] = releaseSpend
        machine = advanced(machine, t.pollOnce("dd".repeat(32), now).single())
        assertEquals(DealState.RELEASED, machine.state)
        assertTrue(machine.state.isTerminal)
    }

    @Test
    fun `no-show reclaim is detected from the timeout even before the spend`() {
        val chain = FakeChain()
        val t = tracker(chain)
        chain.boxes[fundedBoxId] = fundedBox()

        var machine = DealStateMachine.initial()
        machine = advanced(machine, DealEvent.VaultFunded(fundedAt))
        val late = fundedAt.plus(ProtocolConstants.RECLAIM_TIMEOUT).plusSeconds(120)
        machine = advanced(machine, t.pollOnce(fundedBoxId, late).single())
        assertEquals(DealState.RECLAIMED, machine.state)
    }

    @Test
    fun `seller reclaim spend maps through the machine`() {
        val chain = FakeChain()
        val t = tracker(chain)
        val (txId, spend) = spendOf(
            fundedBox(), timeoutHeight + 1,
            listOf(payoutBox(sellerTreeHex, "ca".repeat(32))),
        )
        chain.boxes[fundedBoxId] = fundedBox(txId)
        chain.spends[txId] = spend

        var machine = DealStateMachine.initial()
        machine = advanced(machine, DealEvent.VaultFunded(fundedAt))
        // The machine's guard needs a wall-clock instant past the 24h window
        // (the spend height already proves reclaim on-chain; the event instant
        // must still satisfy fundedAt + RECLAIM_TIMEOUT).
        val late = fundedAt.plus(ProtocolConstants.RECLAIM_TIMEOUT).plusSeconds(120)
        machine = advanced(machine, t.pollOnce(fundedBoxId, late).single())
        assertEquals(DealState.RECLAIMED, machine.state)
    }

    @Test
    fun `routine release from FUNDED maps through the machine`() {
        val chain = FakeChain()
        val t = tracker(chain)
        val (txId, spend) = spendOf(
            fundedBox(), 1400,
            listOf(payoutBox(sellerTreeHex, "cb".repeat(32))),
            inputTokens = listOf(oracleNftHex),
        )
        chain.boxes[fundedBoxId] = fundedBox(txId)
        chain.spends[txId] = spend

        var machine = DealStateMachine.initial()
        machine = advanced(machine, DealEvent.VaultFunded(fundedAt))
        machine = advanced(machine, DealEvent.CashCollected(fundedAt.plusSeconds(60), fundedAt.plusSeconds(60)))
        machine = advanced(machine, t.pollOnce(fundedBoxId, now).single())
        assertEquals(DealState.RELEASED, machine.state)
    }
}
