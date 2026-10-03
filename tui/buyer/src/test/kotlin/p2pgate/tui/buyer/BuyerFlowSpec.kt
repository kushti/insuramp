package p2pgate.tui.buyer

import p2pgate.dealprotocol.DealState
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The buyer's decision layer. These assertions are the protocol's promises in
 * `specs/tui-apps.md` §5 stated as tests: which key each path needs, when a
 * claim may be opened, when the payout unlocks, and what the console must not
 * offer.
 */
class BuyerFlowSpec {

    private fun facts(
        state: DealState?,
        record: Boolean = false,
        key: Boolean = false,
        funded: Boolean = state != null && state != DealState.QUOTED,
        remaining: Int? = null,
    ) = BuyerFlow.Facts(state, hasRecord = record, hasKey = key, funded = funded, claimBlocksRemaining = remaining)

    private fun actions(state: DealState?, record: Boolean = false, key: Boolean = false) =
        BuyerFlow.actionsFor(facts(state, record, key)).map { it.key }

    // ------------------------------------------------------------ which key

    @Test
    fun `opening a claim never needs the key`() {
        // Path B is sigmaProp-only: the evidence is the seller's signature.
        val available = actions(DealState.PAYMENT_PENDING, record = true, key = false)
        assertContains(available, "o")
        assertFalse(available.contains("p"))
        assertFalse(BuyerFlow.actionsFor(facts(DealState.PAYMENT_PENDING, record = true, key = false))
            .any { it.needsKey }, "a keyless deal offered a key-needing action")
    }

    @Test
    fun `taking the payout needs the key and is not offered without one`() {
        assertContains(actions(DealState.CLAIMABLE, record = true, key = true), "p")
        assertFalse(actions(DealState.CLAIMABLE, record = true, key = false).contains("p"))
        assertEquals(
            "the payout needs the deal key (path D) — write the key file with `k`",
            BuyerFlow.blockedReason(BuyerFlow.Action.TAKE_PAYOUT, facts(DealState.CLAIMABLE, key = false)),
        )
    }

    @Test
    fun `a buyer who never writes a key can still open a claim`() {
        val keyless = BuyerFlow.actionsFor(facts(DealState.PAYMENT_PENDING, record = true, key = false))
        assertTrue(keyless.any { !it.needsKey }, "a keyless buyer had no keyless action")
        assertFalse(keyless.any { it.needsKey })
    }

    // ---------------------------------------------------------- when claiming

    @Test
    fun `a claim is offered from exactly the states the state machine accepts one from`() {
        // DealStateMachine takes ClaimOpened from PAYMENT_PENDING and
        // PAYMENT_CONFIRMED -- those two are where cash is collected and reclaim
        // is refused, which is what makes the claim the honest answer.
        for (state in listOf(DealState.PAYMENT_PENDING, DealState.PAYMENT_CONFIRMED)) {
            assertContains(actions(state, record = true), "o", "no claim offered in $state")
        }
        // Nowhere else: no cash collected yet, or nothing left to claim.
        for (state in listOf(
            DealState.QUOTED, DealState.FUNDED,
            DealState.RELEASED, DealState.RECLAIMED, DealState.CLAIMED,
        )) {
            assertFalse(actions(state, record = true).contains("o"), "claim offered in $state")
        }
    }

    @Test
    fun `a claim without the seller's record is refused, and the reason says so`() {
        val action = BuyerFlow.Action.OPEN_CLAIM
        assertFalse(actions(DealState.PAYMENT_PENDING, record = false).contains("o"))
        assertEquals(
            "no handoff record — the claim needs the seller-signed record as evidence",
            BuyerFlow.blockedReason(action, facts(DealState.PAYMENT_PENDING, record = false)),
        )
    }

    @Test
    fun `the handoff upload is offered from FUNDED only`() {
        assertContains(actions(DealState.FUNDED, record = false), "u")
        assertFalse(actions(DealState.PAYMENT_PENDING, record = false).contains("u"))
        assertFalse(actions(DealState.QUOTED, record = false).contains("u"))
    }

    // --------------------------------------------------------- key lifecycle

    @Test
    fun `a key can be written before funding and not after`() {
        // The vault pins the buyer key in R6 at funding, so a key written after
        // the fact could not spend the box. The console stops offering it.
        assertContains(actions(DealState.QUOTED), "k")
        assertFalse(actions(DealState.FUNDED).contains("k"))
        assertEquals(
            "the deal is already funded — the key is fixed",
            BuyerFlow.blockedReason(BuyerFlow.Action.CREATE_KEY, facts(DealState.FUNDED, funded = true)),
        )
    }

    @Test
    fun `refresh and forget are always on offer`() {
        for (state in DealState.entries) {
            val available = actions(state, record = true, key = true)
            assertContains(available, "r", "no refresh in $state")
            assertContains(available, "f", "no forget in $state")
        }
    }

    @Test
    fun `a key can be written before there is any deal at all`() {
        // Generating the key first and dealing afterwards is the normal order:
        // the vault pins R6 at funding, so the key must already exist by then.
        assertEquals(listOf("k", "r", "f"), actions(null))
    }

    // ------------------------------------------------------------ maturation

    @Test
    fun `the payout unlocks one block after maturation elapses`() {
        // The contract needs HEIGHT > proofHeight + CLAIM_MATURATION, so the
        // proof block plus the delay block is still locked.
        val maturation = 100
        assertEquals(101, BuyerFlow.blocksRemaining(0, 0, maturation))
        assertEquals(1, BuyerFlow.blocksRemaining(0, 100, maturation))
        assertEquals(0, BuyerFlow.blocksRemaining(0, 101, maturation))
        assertTrue(BuyerFlow.payoutReady(0, 101, maturation))
        assertFalse(BuyerFlow.payoutReady(0, 100, maturation))
    }

    @Test
    fun `no claim means no countdown, not a countdown of zero`() {
        // "0 blocks left" would read as "go ahead" when the truth is "there is
        // nothing to take yet".
        assertNull(BuyerFlow.blocksRemaining(null, 500, 100))
        assertFalse(BuyerFlow.payoutReady(null, 500, 100))
    }

    @Test
    fun `the countdown never goes negative`() {
        assertEquals(0, BuyerFlow.blocksRemaining(10, 9_999, 100))
    }

    // ------------------------------------------------------------- the gate

    @Test
    fun `the buyer may only leave the meeting with a verified record`() {
        assertTrue(BuyerFlow.safeToLeave(facts(DealState.FUNDED, record = true)))
        assertFalse(BuyerFlow.safeToLeave(facts(DealState.FUNDED, record = false)))
    }

    @Test
    fun `an unknown state is named, not rendered blank`() {
        assertEquals("no deal", BuyerFlow.stateLabel(null))
        assertEquals("CLAIMABLE", BuyerFlow.stateLabel(DealState.CLAIMABLE))
    }

    @Test
    fun `every key is distinct`() {
        val keys = BuyerFlow.Action.entries.map { it.key }
        assertEquals(keys.size, keys.toSet().size, "duplicate key: $keys")
    }
}