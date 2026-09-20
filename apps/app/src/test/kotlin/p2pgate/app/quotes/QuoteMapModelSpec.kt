package p2pgate.app.quotes

import org.junit.jupiter.api.Test
import p2pgate.app.net.QuoteDto
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuoteMapModelSpec {

    private fun quote(
        id: String = "q1",
        lat: Double? = null,
        lon: Double? = null,
        fiatCurrency: String = "INR",
    ) = QuoteDto(
        id = id,
        version = 1L,
        spreadBps = 50,
        etaMinutes = 30,
        fiatCurrency = fiatCurrency,
        minAmount = 100L,
        maxAmount = 1_000_000L,
        createdAtEpochMs = 1_000L,
        expiresAtEpochMs = 2_000L,
        lat = lat,
        lon = lon,
    )

    @Test
    fun `located quote becomes a marker`() {
        val model = quoteMapModel(listOf(quote(lat = 30.0444, lon = 31.2357)))
        assertEquals(1, model.markers.size)
        assertEquals("q1", model.markers[0].quoteId)
        assertEquals(30.0444, model.markers[0].lat)
        assertEquals(31.2357, model.markers[0].lon)
        assertEquals(0, model.unlocatedCount)
        assertFalse(model.hasUnlocated)
        assertEquals("q1", model.firstLocated?.quoteId)
    }

    @Test
    fun `quote without location has no marker and raises the note`() {
        val model = quoteMapModel(listOf(quote()))
        assertTrue(model.markers.isEmpty())
        assertEquals(1, model.unlocatedCount)
        assertTrue(model.hasUnlocated)
        assertEquals(null, model.firstLocated)
    }

    @Test
    fun `half-published location counts as unlocated`() {
        val model = quoteMapModel(listOf(quote(lat = 30.0, lon = null)))
        assertTrue(model.markers.isEmpty())
        assertEquals(1, model.unlocatedCount)
        assertTrue(model.hasUnlocated)
    }

    @Test
    fun `mixed feed keeps both markers and the note flag`() {
        val model = quoteMapModel(
            listOf(
                quote(id = "no-loc"),
                quote(id = "located", lat = 30.0, lon = 31.0),
            ),
        )
        assertEquals(listOf("located"), model.markers.map { it.quoteId })
        assertEquals(1, model.unlocatedCount)
        assertTrue(model.hasUnlocated)
        assertEquals("located", model.firstLocated?.quoteId)
    }

    @Test
    fun `empty feed is an empty model`() {
        val model = quoteMapModel(emptyList())
        assertTrue(model.markers.isEmpty())
        assertEquals(0, model.unlocatedCount)
        assertFalse(model.hasUnlocated)
    }

    @Test
    fun `multi-quote feed places one pin per located quote`() {
        val model = quoteMapModel(
            listOf(
                quote(id = "a", lat = -1.2921, lon = 36.8219),
                quote(id = "b"),
                quote(id = "c", lat = 19.0760, lon = 72.8777),
            ),
        )
        // The multi-pin path (zoomToBoundingBox) gets both located quotes.
        assertEquals(listOf("a", "c"), model.markers.map { it.quoteId })
        assertEquals(1, model.unlocatedCount)
        assertTrue(model.hasUnlocated)
        assertEquals("a", model.firstLocated?.quoteId)
    }

    @Test
    fun `map model over a currency-filtered feed pins only that currency`() {
        val feed = listOf(
            quote(id = "inr-loc", lat = 19.0760, lon = 72.8777, fiatCurrency = "INR"),
            quote(id = "kes-loc", lat = -1.2921, lon = 36.8219, fiatCurrency = "KSH"),
            quote(id = "inr-noloc", fiatCurrency = "INR"),
        )
        val model = quoteMapModel(quotesForCurrency(feed, "INR"))
        assertEquals(listOf("inr-loc"), model.markers.map { it.quoteId })
        assertEquals(1, model.unlocatedCount)
    }
}
