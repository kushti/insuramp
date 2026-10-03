package p2pgate.tui.seller

import p2pgate.dealprotocol.DealState
import p2pgate.tui.common.wire.LaneCardDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Lane folding and per-column wording. Both are pure, so the operator board's
 * shape and its wording are asserted here rather than eyeballed in a terminal.
 */
class LanesSpec {

    private fun card(dealId: String, amount: Long = 500_000_000, fiat: String = "INR") = LaneCardDto(
        dealId = dealId,
        amount = amount,
        fiatCurrency = fiat,
        collateralTokenId = "ab".repeat(32),
        createdAtEpochMs = 0,
        // Most cards are funded already; the "no-box" flag is the exception.
        vaultBoxId = "cd".repeat(32),
        exitPath = "release",
    )

    @Test
    fun `columns appear in the order an operator works them`() {
        val columns = Lanes.columns(emptyMap())
        assertEquals(
            listOf(
                DealState.QUOTED, DealState.FUNDED, DealState.PAYMENT_PENDING,
                DealState.PAYMENT_CONFIRMED, DealState.CLAIM_OPENED, DealState.CLAIMABLE,
                DealState.RELEASED, DealState.RECLAIMED, DealState.CLAIMED,
            ),
            columns.map { it.state },
        )
    }

    @Test
    fun `empty columns are kept so the board does not jump as deals move`() {
        val columns = Lanes.columns(mapOf("FUNDED" to listOf(card("d1"))))
        assertEquals(9, columns.size)
        assertEquals(1, columns.single { it.state == DealState.FUNDED }.cards.size)
        assertTrue(columns.single { it.state == DealState.QUOTED }.cards.isEmpty())
    }

    @Test
    fun `an unknown state name is dropped rather than rendered`() {
        val columns = Lanes.columns(mapOf("NOT_A_STATE" to listOf(card("d1"))))
        assertTrue(columns.none { it.cards.isNotEmpty() })
    }

    @Test
    fun `keys match the per-column actions`() {
        assertEquals(
            listOf(SellerController.Action.ACCEPT, SellerController.Action.DECLINE),
            SellerController.actionsFor(DealState.QUOTED),
        )
        assertEquals(
            listOf(SellerController.Action.SIGN, SellerController.Action.RECLAIM),
            SellerController.actionsFor(DealState.FUNDED),
        )
        assertEquals(
            listOf(
                SellerController.Action.CONTEST,
                SellerController.Action.INVESTIGATE,
                SellerController.Action.ACCEPT_LOSS,
            ),
            SellerController.actionsFor(DealState.CLAIM_OPENED),
        )
    }

    @Test
    fun `states with nothing to do offer no keys`() {
        // Waiting on the buyer or the oracle is not an operator action — a key
        // here would suggest the console can move a deal it cannot move.
        assertTrue(SellerController.actionsFor(DealState.PAYMENT_PENDING).isEmpty())
        assertTrue(SellerController.actionsFor(DealState.PAYMENT_CONFIRMED).isEmpty())
        assertTrue(SellerController.actionsFor(DealState.RELEASED).isEmpty())
        assertTrue(SellerController.actionsFor(null).isEmpty())
    }

    @Test
    fun `action keys are single characters and unique`() {
        val keys = SellerController.Action.entries.map { it.key }
        assertEquals(keys.size, keys.toSet().size, "a duplicated key would shadow another action")
        assertTrue(keys.all { it.length == 1 })
    }

    @Test
    fun `flags mark contested, cash-in and a missing vault box`() {
        val plain = card("d1")
        assertEquals("", flags(plain))
        assertEquals(" [cash-in]", flags(plain.copy(hasHandoffRecord = true)))
        assertEquals(" [no-box]", flags(plain.copy(vaultBoxId = null)))
        assertEquals(
            " [contested,cash-in]",
            flags(plain.copy(contested = true, hasHandoffRecord = true)),
        )
    }
}
