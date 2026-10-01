package p2pgate.app.quotes

import org.junit.jupiter.api.Test
import p2pgate.app.net.QuoteDto
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The composed offer (`onramp-ux.md` §2.1: "you get N USDT, you hand M cash,
 * to this address"). The regression this file exists for: the app used to send
 * the buyer's single input as *both* legs, so 500 cash bought 500 USDT at 1:1
 * with no rate anywhere in the system.
 */
class QuoteOfferSpec {

    private fun quote(
        id: String = "q1",
        fiatCurrency: String = "INR",
        rateMicros: Long = 92_000_000L,
        minAmount: Long = 1_000_000L,
        maxAmount: Long = 100_000_000L,
    ) = QuoteDto(
        id = id,
        version = 1,
        spreadBps = 120,
        etaMinutes = 30,
        fiatCurrency = fiatCurrency,
        minAmount = minAmount,
        maxAmount = maxAmount,
        fiatPerUsdtMicros = rateMicros,
        createdAtEpochMs = 0,
        expiresAtEpochMs = 1_800_000,
    )

    // A checksum-valid TRON address (Tether's TRC-20 contract).
    private val validAddress = "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t"

    // The problem accessors return null when there is no problem; these unwrap
    // that so each assertion reads as one line.
    private fun addressProblem(address: String): QuoteOffer.Problem =
        assertNotNull(QuoteOffer.addressProblem(address))

    private fun problem(amount: String, address: String, quote: QuoteDto): QuoteOffer.Problem =
        assertNotNull(QuoteOffer.problem(amount, address, quote))

    private fun parseUsdtBase(input: String) = QuoteOffer.parseUsdtBase(input)
    private fun cashFor(usdtBase: Long, quote: QuoteDto) = QuoteOffer.cashFor(usdtBase, quote)
    private fun cashLabel(usdtBase: Long?, quote: QuoteDto) = QuoteOffer.cashLabel(usdtBase, quote)
    private fun rateLabel(quote: QuoteDto) = QuoteOffer.rateLabel(quote)

    // ---------- parsing ----------

    @Test
    fun `whole USDT input parses to base units`() {
        assertEquals(500_000_000L, parseUsdtBase("500"))
        assertEquals(1_000_000L, parseUsdtBase("1"))
        assertEquals(500_000_000L, parseUsdtBase(" 500 "))
    }

    @Test
    fun `unusable amounts parse to null`() {
        for (input in listOf("", "  ", "0", "-5", "abc", "1.5", "1e3", "99999999999999999999")) {
            assertNull(parseUsdtBase(input), input)
        }
    }

    // ---------- the derived cash leg ----------

    @Test
    fun `the cash leg is the rate applied to the typed USDT amount`() {
        val q = quote(rateMicros = 92_000_000L)
        // 500 USDT at 92 INR = 46,000 INR — not 500, the bug this replaces.
        assertEquals(46_000L, cashFor(500_000_000L, q))
        assertEquals("46000", cashLabel(500_000_000L, q))
    }

    @Test
    fun `a one-to-one rate leaves the figure numerically equal`() {
        val q = quote(fiatCurrency = "USD", rateMicros = 1_000_000L)
        assertEquals(500L, cashFor(500_000_000L, q))
    }

    @Test
    fun `an unusable rate yields null rather than a wrong cash figure`() {
        val q = quote(rateMicros = 0L)
        assertNull(QuoteOffer.cashFor(500_000_000L, q))
    }

    @Test
    fun `the rate label renders the trimmed decimal`() {
        assertEquals("92", rateLabel(quote(rateMicros = 92_000_000L)))
        assertEquals("31.2", rateLabel(quote(rateMicros = 31_200_000L)))
        assertEquals("1", rateLabel(quote(rateMicros = 1_000_000L)))
    }

    // ---------- address ----------

    @Test
    fun `a TRON address is accepted and other shapes are not`() {
        assertNull(QuoteOffer.addressProblem(validAddress))
        assertEquals(
            QuoteOffer.Kind.NOT_TRON,
            addressProblem("0x1234567890abcdef").kind,
        )
        // Same address, one character changed — the checksum fails.
        assertEquals(
            QuoteOffer.Kind.NOT_TRON,
            addressProblem("TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6u").kind,
        )
        // Surrounding whitespace is a paste artefact, not a wrong address.
        assertNull(QuoteOffer.addressProblem(" $validAddress "))
    }

    @Test
    fun `an empty address is a distinct problem from an invalid one`() {
        assertEquals(QuoteOffer.Kind.EMPTY, addressProblem("").kind)
        assertEquals(QuoteOffer.Field.ADDRESS, addressProblem("").field)
    }

    // ---------- the whole offer ----------

    @Test
    fun `a complete offer has no problem`() {
        val q = quote(minAmount = 1_000_000L, maxAmount = 100_000_000L)
        // 50 USDT = 50_000_000 base units, inside [1, 100] USDT.
        assertNull(QuoteOffer.problem("50", validAddress, q))
    }

    @Test
    fun `an amount outside the seller's bounds is refused`() {
        val q = quote(minAmount = 10_000_000L, maxAmount = 50_000_000L) // 10 – 50 USDT
        assertEquals(
            QuoteOffer.Kind.OUT_OF_RANGE,
            problem("5", validAddress, q).kind,
        )
        assertEquals(
            QuoteOffer.Kind.OUT_OF_RANGE,
            problem("51", validAddress, q).kind,
        )
        // The bounds are inclusive at both ends.
        assertNull(QuoteOffer.problem("10", validAddress, q))
        assertNull(QuoteOffer.problem("50", validAddress, q))
    }

    @Test
    fun `a zero amount is distinguished from a malformed one`() {
        val q = quote()
        assertEquals(QuoteOffer.Kind.ZERO, problem("0", validAddress, q).kind)
        assertEquals(QuoteOffer.Kind.UNPARSEABLE, problem("abc", validAddress, q).kind)
        assertEquals(QuoteOffer.Kind.EMPTY, problem("", validAddress, q).kind)
    }

    @Test
    fun `a bad address is reported before the amount is considered`() {
        val q = quote()
        // Both inputs are wrong; the address is the one on screen at QUOTED.
        assertEquals(
            QuoteOffer.Field.ADDRESS,
            problem("0", "nonsense", q).field,
        )
    }

    @Test
    fun `an unusable rate is reported rather than silently pricing 1 to 1`() {
        val q = quote(rateMicros = -1L)
        val problem = problem("50", validAddress, q)
        assertEquals(QuoteOffer.Field.AMOUNT, problem?.field)
        assertEquals(QuoteOffer.Kind.RATE_UNUSABLE, problem?.kind)
    }

    @Test
    fun `the derived cash figure is null until the amount is usable`() {
        val q = quote()
        assertNull(QuoteOffer.cashLabel(null, q))
        assertNull(QuoteOffer.cashLabel(parseUsdtBase("0"), q))
        assertEquals("92", cashLabel(parseUsdtBase("1"), q))
    }
}
