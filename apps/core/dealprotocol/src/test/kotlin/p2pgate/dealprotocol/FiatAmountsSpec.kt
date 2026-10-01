package p2pgate.dealprotocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The cash leg derived from a quote's rate (`specs/android-app.md` §3.1). The
 * contract these tests hold: the app displays `cashFor(...)` and the backend
 * re-derives the same number to validate what the app sent, so the two must
 * agree exactly — including on the rounding boundary.
 */
class FiatAmountsSpec {

    private fun usdt(units: Long) = units * FiatAmounts.USDT_SCALE

    @Test
    fun `whole USDT at a whole rate`() {
        // 500 USDT at 31.2 EGP = 15,600 EGP.
        assertEquals(15_600L, FiatAmounts.cashFor(usdt(500), 31_200_000L))
        // 500 USDT at 1.00 USD = 500 USD.
        assertEquals(500L, FiatAmounts.cashFor(usdt(500), 1_000_000L))
    }

    @Test
    fun `sub-unit USDT amounts are honoured`() {
        // 1.5 USDT at 92 INR = 138 INR.
        assertEquals(138L, FiatAmounts.cashFor(1_500_000L, 92_000_000L))
    }

    @Test
    fun `rounds half-up at the boundary`() {
        // Half a USDT at 1.00 USD/USDT is 0.5 cash → 1 whole unit; just under → 0.
        assertEquals(1L, FiatAmounts.cashFor(500_000L, 1_000_000L))
        assertEquals(0L, FiatAmounts.cashFor(499_999L, 1_000_000L))
        // 1 USDT at 0.5 micros per USDT = 0.0000005 → 0.
        assertEquals(0L, FiatAmounts.cashFor(usdt(1), 500L))
    }

    @Test
    fun `tiny amounts can round to zero cash`() {
        // 1 base unit at 1 micros per USDT = 0.000001 → 0 whole units.
        assertEquals(0L, FiatAmounts.cashFor(1L, 1L))
    }

    @Test
    fun `rejects an overflow rather than wrapping`() {
        // Long.MAX_VALUE × 1_000_001 wraps negative; the guard must fire.
        assertFailsWith<IllegalArgumentException> {
            FiatAmounts.cashFor(Long.MAX_VALUE, FiatAmounts.USDT_SCALE + 1)
        }.message?.let { assertEquals(true, it.contains("overflow"), it) }
    }

    @Test
    fun `rejects a non-positive rate`() {
        for (rate in listOf(0L, -1L)) {
            assertFailsWith<IllegalArgumentException> { FiatAmounts.cashFor(usdt(1), rate) }
        }
    }

    @Test
    fun `rejects a negative amount`() {
        assertFailsWith<IllegalArgumentException> { FiatAmounts.cashFor(-1L, 1_000_000L) }
    }

    @Test
    fun `formats micros with trailing zeros trimmed`() {
        assertEquals("1", FiatAmounts.formatMicros(1_000_000L))
        assertEquals("31.2", FiatAmounts.formatMicros(31_200_000L))
        assertEquals("0.000001", FiatAmounts.formatMicros(1L))
        assertEquals("92.123456", FiatAmounts.formatMicros(92_123_456L))
    }

    @Test
    fun `formats the rate line`() {
        assertEquals("1 USDT = 31.2", FiatAmounts.formatRate(31_200_000L))
    }

    @Test
    fun `formats USDT base units`() {
        assertEquals("500", FiatAmounts.formatUsdt(500_000_000L))
        assertEquals("1.2345", FiatAmounts.formatUsdt(1_234_500L))
        assertEquals("0", FiatAmounts.formatUsdt(0L))
    }

    @Test
    fun `formatting is locale-independent`() {
        // A locale with non-ASCII digits must not leak into the rendered number —
        // the app ships hi/sw/ar/ru and the backend parses the value back.
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-EG-u-nu-arab"))
            assertEquals("31.2", FiatAmounts.formatMicros(31_200_000L))
            assertEquals("1.2345", FiatAmounts.formatUsdt(1_234_500L))
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }
}
