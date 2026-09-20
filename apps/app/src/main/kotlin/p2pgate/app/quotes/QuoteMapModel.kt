package p2pgate.app.quotes

import p2pgate.app.net.QuoteDto

/** One located quote as a map pin. */
data class QuoteMarker(
    val quoteId: String,
    val lat: Double,
    val lon: Double,
    val minAmount: Long,
    val maxAmount: Long,
    val etaMinutes: Int,
)

/**
 * Projection of the quote feed onto the map view: located quotes become
 * markers, the rest stay list-only and raise the "no published location" note.
 */
data class QuoteMapModel(
    val markers: List<QuoteMarker>,
    /** Quotes published without a location — list-only, noted on the map. */
    val unlocatedCount: Int,
) {
    val hasUnlocated: Boolean get() = unlocatedCount > 0
    val firstLocated: QuoteMarker? get() = markers.firstOrNull()
}

/**
 * Pure quote→marker mapping (unit-tested on the JVM): a quote needs both
 * coordinates to place a pin; a half-published location counts as unlocated.
 */
fun quoteMapModel(quotes: List<QuoteDto>): QuoteMapModel {
    val markers = quotes.mapNotNull { q ->
        val lat = q.lat
        val lon = q.lon
        if (lat == null || lon == null) null
        else QuoteMarker(q.id, lat, lon, q.minAmount, q.maxAmount, q.etaMinutes)
    }
    return QuoteMapModel(markers, unlocatedCount = quotes.size - markers.size)
}
