package p2pgate.tui.seller

import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.terminal.Terminal
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The quote-ad panel (`q`): the operator's live quotes, `x` withdraw, and the
 * new-ad form (`n`) — field typing, local validation, wire-unit conversion,
 * and the publish/reject wiring against a fake client.
 */
class SellerQuotePanelSpec {

    private fun controller(client: FakeClient, scope: kotlinx.coroutines.CoroutineScope) =
        SellerController(client, scope, pollIntervalMs = 1_000_000)

    /** Types [text] into the focused field, one key at a time. */
    private fun SellerController.type(text: String) = text.forEach { formType(it.toString()) }

    @Test
    fun `q toggles the panel and the cursor stays inside the quote list`() = runTest {
        val client = FakeClient(quoteList = listOf(quoteDto(id = "q1"), quoteDto(id = "q2")))
        val c = controller(client, this)
        c.refresh() // quotes reach the state via refresh, like the live poll
        c.toggleQuotePanel()
        assertTrue(c.state.value.quotePanel != null)
        c.moveQuoteCursor(99)
        assertEquals(1, c.state.value.quotePanel!!.selected)
        c.moveQuoteCursor(-99)
        assertEquals(0, c.state.value.quotePanel!!.selected)
        c.toggleQuotePanel()
        assertNull(c.state.value.quotePanel)
        c.stop()
    }

    @Test
    fun `x withdraws the quote under the cursor`() = runTest {
        val client = FakeClient(quoteList = listOf(quoteDto(id = "q1"), quoteDto(id = "q2")))
        val c = controller(client, this)
        c.refresh()
        c.toggleQuotePanel()
        c.moveQuoteCursor(1)
        c.withdrawSelectedQuote()
        assertEquals(listOf("q2"), client.withdrawn)
        assertTrue(c.state.value.status.contains("withdrew"), c.state.value.status)
        c.stop()
    }

    @Test
    fun `x with an empty quote list complains instead of calling the backend`() = runTest {
        val client = FakeClient()
        val c = controller(client, this)
        c.toggleQuotePanel()
        c.withdrawSelectedQuote()
        assertTrue(client.withdrawn.isEmpty())
        assertTrue(c.state.value.status.contains("no quote selected"), c.state.value.status)
        c.stop()
    }

    @Test
    fun `the form types, backspaces, and moves focus`() = runTest {
        val client = FakeClient()
        val c = controller(client, this)
        c.toggleQuotePanel()
        c.openQuoteForm()
        // currency field: letters only, uppercased, capped at 3
        c.type("rubb")
        assertEquals("RUB", c.state.value.quotePanel!!.form!!.currency)
        c.formType("9")
        assertEquals("RUB", c.state.value.quotePanel!!.form!!.currency)
        c.formFocus(1)
        c.type("95..0")
        assertEquals("95.0", c.state.value.quotePanel!!.form!!.rate)
        c.formBackspace()
        c.formBackspace()
        c.formBackspace()
        c.formBackspace()
        assertEquals("", c.state.value.quotePanel!!.form!!.rate)
        c.stop()
    }

    @Test
    fun `a valid form publishes with wire-unit conversion`() = runTest {
        val client = FakeClient()
        val c = controller(client, this)
        c.toggleQuotePanel()
        c.openQuoteForm()
        fillValidForm(c)
        c.submitQuoteForm()
        assertEquals(1, client.published.size)
        val q = client.published.single()
        assertEquals("RUB", q.fiatCurrency)
        assertEquals(95_000_000L, q.fiatPerUsdtMicros) // 95.00 → micros
        assertEquals(3_000_000L, q.minAmount)          // 3 → base units
        assertEquals(15_000_000_000L, q.maxAmount)     // 15000 → base units
        assertEquals(45, q.etaMinutes)
        assertEquals(0, q.spreadBps)                   // blank spread → 0 (metadata)
        assertNull(q.lat)
        assertNull(q.lon)
        // The form closes and the board refreshes after a publish.
        assertNull(c.state.value.quotePanel!!.form)
        assertTrue(c.state.value.status.contains("quote published"), c.state.value.status)
        c.stop()
    }

    @Test
    fun `lat without lon is refused locally, no backend call`() = runTest {
        val client = FakeClient()
        val c = controller(client, this)
        c.toggleQuotePanel()
        c.openQuoteForm()
        fillValidForm(c)
        // focus lat (index 6), type a latitude, leave lon blank
        repeat(6) { c.formFocus(1) }
        c.type("55.75")
        c.submitQuoteForm()
        assertTrue(client.published.isEmpty())
        assertTrue(c.state.value.status.contains("lat and lon come as a pair"), c.state.value.status)
        c.stop()
    }

    @Test
    fun `min above max is refused locally`() = runTest {
        val client = FakeClient()
        val c = controller(client, this)
        c.toggleQuotePanel()
        c.openQuoteForm()
        fillValidForm(c, min = "20000", max = "15000")
        c.submitQuoteForm()
        assertTrue(client.published.isEmpty())
        assertTrue(c.state.value.status.contains("min amount is above max"), c.state.value.status)
        c.stop()
    }

    @Test
    fun `a backend rejection is shown, not swallowed`() = runTest {
        val client = FakeClient()
        client.publishRejection = "exceeds free collateral capacity"
        val c = controller(client, this)
        c.toggleQuotePanel()
        c.openQuoteForm()
        fillValidForm(c)
        c.submitQuoteForm()
        assertTrue(c.state.value.status.contains("exceeds free collateral capacity"), c.state.value.status)
        c.stop()
    }

    @Test
    fun `wholeToUnits is exact on decimal dust`() {
        assertEquals(95_000_000L, SellerController.wholeToUnits("95.00"))
        assertEquals(100_000L, SellerController.wholeToUnits("0.1"))
        assertEquals(3_000_000L, SellerController.wholeToUnits("3"))
        assertEquals(1L, SellerController.wholeToUnits("0.000001"))
    }

    @Test
    fun `the panel renders the live quotes and the form`() = runTest {
        val client = FakeClient(quoteList = listOf(quoteDto()))
        val c = controller(client, this)
        c.toggleQuotePanel()
        c.openQuoteForm()
        runMosaicTest {
            state.size.value = Terminal.Size(200, 40)
            c.refresh()
            val out = setContentAndSnapshot { SellerScreen(c) }
            assertTrue(out.contains("QUOTES"), out)
            assertTrue(out.contains("95.00 RUB/USDT"), out)
            assertTrue(out.contains("new ad:"), out)
            assertTrue(out.contains("enter publish"), out)
        }
        c.stop()
    }

    /** currency → rate → min → max → ETA, each followed by Tab-equivalent focus move. */
    private suspend fun fillValidForm(
        c: SellerController,
        min: String = "3",
        max: String = "15000",
    ) {
        c.type("RUB")
        c.formFocus(1)
        c.type("95.00")
        c.formFocus(1)
        c.type(min)
        c.formFocus(1)
        c.type(max)
        c.formFocus(1)
        // ETA defaults to 30; clear it and type 45.
        c.formBackspace()
        c.formBackspace()
        c.type("45")
        c.formFocus(1)
    }
}
