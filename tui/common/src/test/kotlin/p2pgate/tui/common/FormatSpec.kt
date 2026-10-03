package p2pgate.tui.common

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Formatting is pure, so it is asserted directly — no terminal involved. */
class FormatSpec {

    private val now = Instant.parse("2026-10-02T12:00:00Z")

    @Test
    fun `USDT base units render in whole units`() {
        assertEquals("500", Format.usdt(500_000_000))
        assertEquals("1", Format.usdt(1_000_000))
        assertEquals("0", Format.usdt(0))
    }

    @Test
    fun `USDT amounts keep meaningful decimals and drop trailing zeros`() {
        assertEquals("12.5", Format.usdt(12_500_000))
        assertEquals("0.000001", Format.usdt(1))
    }

    @Test
    fun `fiat amounts are whole basic units with their currency`() {
        assertEquals("9200 INR", Format.fiat(9_200, "INR"))
    }

    @Test
    fun `rate renders micros as fiat per USDT`() {
        assertEquals("92.00 INR/USDT", Format.rate(92_000_000, "INR"))
        assertEquals("1.00 USD/USDT", Format.rate(1_000_000, "USD"))
    }

    @Test
    fun `countdown picks the coarsest unit that fits`() {
        assertEquals("3h 12m", Format.countdown(now.plus(Duration.ofHours(3).plusMinutes(12)), now))
        assertEquals("12m 5s", Format.countdown(now.plus(Duration.ofMinutes(12).plusSeconds(5)), now))
        assertEquals("40s", Format.countdown(now.plusSeconds(40), now))
    }

    @Test
    fun `countdown marks a passed deadline instead of counting down`() {
        assertEquals("passed", Format.countdown(now.minusSeconds(1), now))
        assertEquals("—", Format.countdown(null, now))
    }

    @Test
    fun `elapsed reads as age since an event`() {
        assertEquals("2h 5m", Format.elapsed(now.minus(Duration.ofHours(2).plusMinutes(5)), now))
        assertEquals("—", Format.elapsed(null, now))
        assertEquals("—", Format.elapsed(now.plusSeconds(30), now), "a future instant is not an age")
    }

    @Test
    fun `long identifiers are shortened for a narrow board`() {
        assertEquals("abcd1234…", Format.shortId("abcd1234ef567890"))
        assertTrue(Format.shortHex("0a1b2c3d4e5f").startsWith("0a1b2c"))
    }
}
