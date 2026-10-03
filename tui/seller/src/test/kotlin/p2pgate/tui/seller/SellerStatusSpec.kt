package p2pgate.tui.seller

import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import p2pgate.dealprotocol.DealState

/**
 * The status line.
 *
 * Every test in this file exists because of something that went wrong when the
 * console ran against a live backend: the status line sat reading "connecting…"
 * forever, because `refresh()` wrote to it only on the *failure* path. The
 * console's one feedback channel was dead on the happy path, and no headless test
 * caught it — the rendered snapshot asserted on the board, not on this line.
 *
 * The second bug it found: a successful refresh wrote to the same line as an
 * action's outcome, so "signed the handoff record — scan below" could be wiped by
 * the next 5s poll before the operator turned around and read it. Hence
 * [SellerState.statusPinned].
 */
class SellerStatusSpec {

    private val at = Instant.parse("2026-10-02T12:00:00Z")

    /** The scope is the `runTest` receiver, so it has to be passed in. */
    private fun controller(scope: CoroutineScope, client: FakeClient, clock: Instant = at) =
        SellerController(client, scope, pollIntervalMs = 1_000_000, now = { clock })

    @Test
    fun `a successful refresh reports the live count instead of staying on connecting`() = runTest {
        val client = FakeClient(
            mapOf(
                DealState.QUOTED.name to listOf(laneCard(DealState.QUOTED.name)),
                DealState.FUNDED.name to listOf(laneCard(DealState.FUNDED.name)),
            ),
        )
        val c = controller(this, client)
        assertEquals("connecting…", c.state.value.status, "precondition")

        c.refresh()
        val status = c.state.value.status
        assertFalse(status.contains("connecting"), "status never left its initial value: $status")
        assertTrue(status.contains("2 live"), "did not report the live count: $status")
        assertTrue(status.contains("0 disputes"), "did not report the dispute count: $status")
    }

    @Test
    fun `a failed refresh says so, and records no sync time`() = runTest {
        val c = controller(this, FakeClient().apply { failWith = "connection refused" })
        c.refresh()
        val state = c.state.value
        assertTrue(state.status.startsWith("refresh failed"), state.status)
        assertTrue(state.status.contains("connection refused"), state.status)
        assertEquals(0L, state.lastSyncedAt, "a failed refresh recorded a sync time")
    }

    @Test
    fun `an action's message survives the poll that follows it`() = runTest {
        val c = controller(
            this,
            FakeClient(mapOf(DealState.FUNDED.name to listOf(laneCard(DealState.FUNDED.name)))),
        )
        c.refresh()
        val afterRefresh = c.state.value.status

        c.run(SellerController.Action.SIGN) // no vault box observed -> refused, but it speaks
        val spoken = c.state.value.status
        assertTrue(spoken.isNotBlank() && spoken != afterRefresh, "the action said nothing: '$spoken'")

        c.refresh()
        assertEquals(spoken, c.state.value.status, "the poll clobbered the action's message")
    }

    @Test
    fun `the pin expires, so a stale action message cannot sit on the line forever`() = runTest {
        val start = Instant.parse("2026-10-02T12:00:00Z")
        val client = FakeClient(mapOf(DealState.FUNDED.name to listOf(laneCard(DealState.FUNDED.name))))
        var clock = start
        val c = SellerController(client, this, pollIntervalMs = 1_000_000, now = { clock })
        c.refresh()
        c.run(SellerController.Action.SIGN)
        val spoken = c.state.value.status

        // Just inside the window: still pinned.
        clock = start.plusMillis(SellerController.ACTION_STATUS_MS - 1)
        c.refresh()
        assertEquals(spoken, c.state.value.status, "the pin expired early")

        // Past the window: the poll takes the line back.
        clock = start.plusMillis(SellerController.ACTION_STATUS_MS + 1)
        c.refresh()
        assertFalse(c.state.value.status == spoken, "the pin never expired")
    }

    @Test
    fun `the board records when it was last synced`() = runTest {
        val c = controller(this, FakeClient())
        assertEquals(0L, c.state.value.lastSyncedAt, "precondition: synced before any refresh")
        c.refresh()
        assertEquals(at.toEpochMilli(), c.state.value.lastSyncedAt)
    }

    @Test
    fun `a failure takes the line back immediately, without waiting for the pin`() = runTest {
        // A refresh that fails is news: it should not be hidden behind a pinned
        // action message, because "the board is stale" outranks "you signed it".
        val client = FakeClient(mapOf(DealState.FUNDED.name to listOf(laneCard(DealState.FUNDED.name))))
        val c = controller(this, client)
        c.refresh()
        c.run(SellerController.Action.SIGN)
        val spoken = c.state.value.status

        client.failWith = "boom"
        c.refresh()
        assertTrue(c.state.value.status.startsWith("refresh failed"), c.state.value.status)
        assertFalse(c.state.value.status == spoken, "the failure was suppressed by the pin")
    }
}