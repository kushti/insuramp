package p2pgate.app.quotes

import org.junit.jupiter.api.Test
import p2pgate.app.net.QuoteDto
import kotlin.test.assertEquals

/** The list view's quote ordering: best (lowest ETA) first. */
class QuoteListSpec {

    private fun quote(id: String, etaMinutes: Int) = QuoteDto(
        id = id,
        version = 1L,
        spreadBps = 50,
        etaMinutes = etaMinutes,
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
}
