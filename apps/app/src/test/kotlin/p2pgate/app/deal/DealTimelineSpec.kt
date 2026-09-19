package p2pgate.app.deal

import org.junit.jupiter.api.Test
import p2pgate.app.R
import p2pgate.dealprotocol.DealState
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The timeline projection (`onramp-ux.md` §2.2): canonical state names from
 * `:core:dealprotocol` render verbatim, happy path top-to-bottom, dispute
 * branch from CLAIM_OPENED, and the permanent dispute button's availability.
 * Display text is localized via string resources — these tests assert the
 * canonical-state → string-resource-id mapping, never the translations.
 */
class DealTimelineSpec {

    @Test
    fun `happy path rows carry the canonical names in order`() {
        val rows = DealTimeline.rowsFor(DealState.FUNDED)
        assertEquals(
            listOf("FUNDED", "PAYMENT_PENDING", "PAYMENT_CONFIRMED", "RELEASED"),
            rows.map { it.state.name },
        )
        assertEquals(RowStatus.ACTIVE, rows[0].status)
        assertEquals(RowStatus.PENDING, rows[1].status)
    }

    @Test
    fun `happy path rows carry the state label resources`() {
        val rows = DealTimeline.rowsFor(DealState.FUNDED)
        assertEquals(
            listOf(
                R.string.state_funded,
                R.string.state_payment_pending,
                R.string.state_payment_confirmed,
                R.string.state_released,
            ),
            rows.map { it.labelRes },
        )
    }

    @Test
    fun `progress marks earlier rows done`() {
        val rows = DealTimeline.rowsFor(DealState.PAYMENT_CONFIRMED)
        assertEquals(listOf(RowStatus.DONE, RowStatus.DONE, RowStatus.ACTIVE, RowStatus.PENDING), rows.map { it.status })
    }

    @Test
    fun `released is done`() {
        val rows = DealTimeline.rowsFor(DealState.RELEASED)
        assertTrue(rows.all { it.status == RowStatus.DONE })
    }

    @Test
    fun `claim branch replaces pending rows`() {
        val rows = DealTimeline.rowsFor(DealState.CLAIMABLE)
        assertEquals(
            listOf("FUNDED", "PAYMENT_PENDING", "CLAIM_OPENED", "CLAIMABLE", "CLAIMED"),
            rows.map { it.state.name },
        )
        assertEquals(
            listOf(
                R.string.state_funded,
                R.string.state_payment_pending,
                R.string.state_claim_opened,
                R.string.state_claimable,
                R.string.state_claimed,
            ),
            rows.map { it.labelRes },
        )
        assertEquals(RowStatus.ACTIVE, rows[3].status)
        assertEquals(RowStatus.DONE, rows[2].status)
    }

    @Test
    fun `claimed is done`() {
        val rows = DealTimeline.rowsFor(DealState.CLAIMED)
        assertTrue(rows.all { it.status == RowStatus.DONE })
    }

    @Test
    fun `quoted renders the pending happy path`() {
        val rows = DealTimeline.rowsFor(DealState.QUOTED)
        assertTrue(rows.all { it.status == RowStatus.PENDING })
    }

    @Test
    fun `every canonical state maps to its own label resource`() {
        val mapping = DealState.entries.associateWith(::stateLabelRes)
        assertEquals(DealState.entries.size, mapping.values.toSet().size)
        assertEquals(R.string.state_quoted, stateLabelRes(DealState.QUOTED))
        assertEquals(R.string.state_reclaimed, stateLabelRes(DealState.RECLAIMED))
    }

    @Test
    fun `dispute button availability matches the spec`() {
        assertFalse(DealTimeline.claimAvailable(DealState.QUOTED))
        assertFalse(DealTimeline.claimAvailable(DealState.FUNDED))
        assertTrue(DealTimeline.claimAvailable(DealState.PAYMENT_PENDING))
        assertTrue(DealTimeline.claimAvailable(DealState.PAYMENT_CONFIRMED))
        assertTrue(DealTimeline.claimAvailable(DealState.CLAIM_OPENED))
        assertTrue(DealTimeline.claimAvailable(DealState.CLAIMABLE))
        assertFalse(DealTimeline.claimAvailable(DealState.RELEASED))
        assertFalse(DealTimeline.claimAvailable(DealState.RECLAIMED))
    }
}
