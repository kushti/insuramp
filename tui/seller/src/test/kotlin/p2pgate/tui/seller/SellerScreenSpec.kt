package p2pgate.tui.seller

import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.terminal.Terminal
import kotlinx.coroutines.test.runTest
import p2pgate.tui.common.wire.LaneCardDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The console rendered headlessly through Mosaic's test harness: a fake client
 * feeds the board, and the assertions are on the *text* an operator would read.
 * No TTY, no server — the same code path `Main.kt` runs.
 */
class SellerScreenSpec {

    private val dealId = "abcdef0123456789deadbeefcafebabe00000000000000000000000000000000"

    @Test
    fun `the board draws every lane with its deals`() = runTest {
        val client = FakeClient(
            mapOf(
                "QUOTED" to listOf(laneCard("QUOTED")),
                "FUNDED" to listOf(laneCard("FUNDED", hasRecord = false)),
            ),
        )
        val controller = SellerController(client, this, pollIntervalMs = 1_000_000)
        runMosaicTest {
            state.size.value = Terminal.Size(200, 40)
            controller.refresh()
            val out = setContentAndSnapshot { SellerScreen(controller) }
            assertTrue(out.contains("OFFERED (1)"), out)
            assertTrue(out.contains("FUNDED (1)"), out)
            assertTrue(out.contains("500 INR"), out)
            assertTrue(out.contains("abcdef01…"), out)
            // Empty lanes are drawn too, so the board's shape is stable.
            assertTrue(out.contains("RELEASED (0)"), out)
        }
        controller.stop()
    }

    @Test
    fun `the cursor line shows the reclaim countdown for a funded deal`() = runTest {
        val client = FakeClient(mapOf("FUNDED" to listOf(laneCard("FUNDED"))))
        val controller = SellerController(client, this, pollIntervalMs = 1_000_000)
        runMosaicTest {
            state.size.value = Terminal.Size(200, 40)
            controller.refresh()
            val out = setContentAndSnapshot { SellerScreen(controller) }
            assertTrue(out.contains("reclaim in"), out)
        }
        controller.stop()
    }

    @Test
    fun `an operator key runs the action the column allows`() = runTest {
        val client = FakeClient(mapOf("QUOTED" to listOf(laneCard("QUOTED"))))
        val controller = SellerController(client, this, pollIntervalMs = 1_000_000)
        controller.refresh()
        client.calls.clear()
        controller.run(SellerController.Action.ACCEPT)
        // The action runs first, then the refresh that follows it touches every
        // panel (a refresh is five endpoints, not one).
        assertEquals("accept", client.calls.first(), "the action must run before the refresh")
        assertEquals(
            listOf("lane", "pool", "infra", "disputes", "quotes"),
            client.calls.drop(1),
            "the refresh after an action should repaint every panel",
        )
        controller.stop()
    }

    @Test
    fun `an action the column does not allow never reaches the backend`() = runTest {
        val client = FakeClient(mapOf("PAYMENT_PENDING" to listOf(laneCard("PAYMENT_PENDING", hasRecord = true))))
        val controller = SellerController(client, this, pollIntervalMs = 1_000_000)
        controller.refresh()
        client.calls.clear()
        // Waiting on the buyer's USDT is not an operator action.
        controller.run(SellerController.Action.SIGN)
        assertTrue(client.calls.isEmpty(), "signing on a cash-in card must not call the backend: ${client.calls}")
        assertTrue(controller.state.value.status.contains("not available"), controller.state.value.status)
        controller.stop()
    }

    @Test
    fun `signing without an observed vault box is refused locally`() = runTest {
        val client = FakeClient(mapOf("FUNDED" to listOf(laneCard("FUNDED", box = null))))
        val controller = SellerController(client, this, pollIntervalMs = 1_000_000)
        controller.refresh()
        client.calls.clear()
        controller.run(SellerController.Action.SIGN)
        assertTrue(client.calls.isEmpty(), "no vault box means nothing to sign against: ${client.calls}")
        assertTrue(controller.state.value.status.contains("no vault box"), controller.state.value.status)
        controller.stop()
    }

    @Test
    fun `moving the cursor past the ends stays inside the board`() = runTest {
        val client = FakeClient(mapOf("FUNDED" to listOf(laneCard("FUNDED"))))
        val controller = SellerController(client, this, pollIntervalMs = 1_000_000)
        controller.refresh()
        controller.moveColumn(-1)
        assertEquals(0, controller.state.value.selectedColumn)
        controller.moveColumn(99)
        assertEquals(controller.state.value.columns.lastIndex, controller.state.value.selectedColumn)
        controller.moveRow(-1)
        assertEquals(0, controller.state.value.selectedRow)
        controller.stop()
    }
}
