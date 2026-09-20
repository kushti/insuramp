package p2pgate.app.quotes

import org.junit.jupiter.api.Test
import p2pgate.app.net.QuoteDto
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The list view's quote ordering: best (lowest ETA) first. */
class QuoteListSpec {

    private fun quote(id: String, etaMinutes: Int, fiatCurrency: String = "INR") = QuoteDto(
        id = id,
        version = 1L,
        spreadBps = 50,
        etaMinutes = etaMinutes,
        fiatCurrency = fiatCurrency,
        minAmount = 100L,
        maxAmount = 1_000_000L,
        createdAtEpochMs = 1_000L,
        expiresAtEpochMs = 2_000L,
    )

    @Test
    fun `quotes render lowest ETA first`() {
        val ordered = bestFirst(
            listOf(
                quote(id = "slow", etaMinutes = 90),
                quote(id = "fast", etaMinutes = 15),
                quote(id = "mid", etaMinutes = 45),
            ),
        )
        assertEquals(listOf("fast", "mid", "slow"), ordered.map { it.id })
    }

    @Test
    fun `ties keep the feed order`() {
        val ordered = bestFirst(
            listOf(
                quote(id = "first", etaMinutes = 30),
                quote(id = "second", etaMinutes = 30),
                quote(id = "third", etaMinutes = 10),
            ),
        )
        assertEquals(listOf("third", "first", "second"), ordered.map { it.id })
    }

    @Test
    fun `amount range is inclusive at both bounds`() {
        assertTrue(amountInRange(100L, minAmount = 100L, maxAmount = 1_000_000L))
        assertTrue(amountInRange(500_000L, minAmount = 100L, maxAmount = 1_000_000L))
        assertTrue(amountInRange(1_000_000L, minAmount = 100L, maxAmount = 1_000_000L))
    }

    @Test
    fun `amounts outside the range are rejected before the backend call`() {
        assertFalse(amountInRange(99L, minAmount = 100L, maxAmount = 1_000_000L))
        assertFalse(amountInRange(1_000_001L, minAmount = 100L, maxAmount = 1_000_000L))
        assertFalse(amountInRange(0L, minAmount = 100L, maxAmount = 1_000_000L))
    }

    @Test
    fun `mixed-currency feed filters to the selected currency`() {
        val feed = listOf(
            quote(id = "inr-1", etaMinutes = 30, fiatCurrency = "INR"),
            quote(id = "kes-1", etaMinutes = 10, fiatCurrency = "KSH"),
            quote(id = "inr-2", etaMinutes = 20, fiatCurrency = "INR"),
            quote(id = "rub-1", etaMinutes = 5, fiatCurrency = "RUB"),
        )
        assertEquals(listOf("inr-1", "inr-2"), quotesForCurrency(feed, "INR").map { it.id })
        assertEquals(listOf("kes-1"), quotesForCurrency(feed, "KSH").map { it.id })
        assertEquals(listOf("rub-1"), quotesForCurrency(feed, "RUB").map { it.id })
    }

    @Test
    fun `filter is empty for a currency the feed does not serve`() {
        val feed = listOf(
            quote(id = "inr-1", etaMinutes = 30, fiatCurrency = "INR"),
            quote(id = "kes-1", etaMinutes = 10, fiatCurrency = "KSH"),
        )
        assertTrue(quotesForCurrency(feed, "USD").isEmpty())
        assertTrue(quotesForCurrency(feed, "RUB").isEmpty())
    }
}
