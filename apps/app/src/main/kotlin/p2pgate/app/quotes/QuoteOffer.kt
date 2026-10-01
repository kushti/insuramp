package p2pgate.app.quotes

import p2pgate.app.net.QuoteDto
import p2pgate.dealprotocol.FiatAmounts
import p2pgate.dealprotocol.TronAddress

/**
 * The offer the buyer composes before choosing a quote (`onramp-ux.md` §2.1):
 * "you get N USDT, you hand M <fiat>, to this address".
 *
 * The buyer types the **USDT** leg — it is the leg the quote's min/max bounds
 * and the vault collateral are denominated in — and the cash leg is derived
 * from the chosen quote's rate. The backend re-derives the same number with the
 * same `FiatAmounts.cashFor` and rejects a mismatch, so what the buyer sees is
 * what gets pinned in the deal terms.
 *
 * Pure functions only: this is the unit under test in `QuoteOfferSpec`, so the
 * screen above it holds no arithmetic.
 */
object QuoteOffer {

    /** Which input a [Problem] is about. */
    enum class Field { AMOUNT, ADDRESS }

    /** Why one input is not usable yet. */
    enum class Kind { EMPTY, UNPARSEABLE, ZERO, OUT_OF_RANGE, RATE_UNUSABLE, NOT_TRON }

    /** A problem with the composed offer, or `null` when it is ready to send. */
    data class Problem(val field: Field, val kind: Kind)

    /**
     * The USDT base-unit amount for whole-unit [input] (`"500"` → 500_000_000),
     * or `null` when the input is not a positive whole number. Whole units only:
     * a buyer typing a 6-decimal base amount is a mistake, not a use case.
     */
    fun parseUsdtBase(input: String): Long? {
        val units = input.trim().toLongOrNull() ?: return null
        if (units <= 0) return null
        if (units > Long.MAX_VALUE / FiatAmounts.USDT_SCALE) return null
        return units * FiatAmounts.USDT_SCALE
    }

    /** The cash leg in whole fiat units for [usdtBase] at [quote]'s rate. */
    fun cashFor(usdtBase: Long, quote: QuoteDto): Long? = try {
        FiatAmounts.cashFor(usdtBase, quote.fiatPerUsdtMicros)
    } catch (e: IllegalArgumentException) {
        null
    }

    /** The first reason the offer cannot be sent, or `null` when it is complete. */
    fun problem(
        amountInput: String,
        receiveAddress: String,
        quote: QuoteDto,
    ): Problem? {
        addressProblem(receiveAddress)?.let { return it }
        if (amountInput.isBlank()) return Problem(Field.AMOUNT, Kind.EMPTY)
        val usdtBase = parseUsdtBase(amountInput) ?: return when {
            amountInput.trim().all { it.isDigit() } && amountInput.trim().toLongOrNull() == 0L ->
                Problem(Field.AMOUNT, Kind.ZERO)
            else -> Problem(Field.AMOUNT, Kind.UNPARSEABLE)
        }
        if (usdtBase < quote.minAmount || usdtBase > quote.maxAmount) {
            return Problem(Field.AMOUNT, Kind.OUT_OF_RANGE)
        }
        if (cashFor(usdtBase, quote) == null) return Problem(Field.AMOUNT, Kind.RATE_UNUSABLE)
        return null
    }

    /** The receive-address problem alone (the field is on screen before a quote is chosen). */
    fun addressProblem(receiveAddress: String): Problem? = when {
        receiveAddress.isBlank() -> Problem(Field.ADDRESS, Kind.EMPTY)
        !TronAddress.isValid(receiveAddress.trim()) -> Problem(Field.ADDRESS, Kind.NOT_TRON)
        else -> null
    }

    /** "1 USDT = 92" — the rate line on the quote card. */
    fun rateLabel(quote: QuoteDto): String =
        FiatAmounts.formatMicros(quote.fiatPerUsdtMicros)

    /**
     * The derived cash leg as a plain integer string, or `null` when the amount
     * is not usable yet. Ungrouped on purpose: the deal timeline renders the
     * same figure ungrouped, and the two screens must not disagree.
     */
    fun cashLabel(usdtBase: Long?, quote: QuoteDto): String? {
        val cash = usdtBase?.let { cashFor(it, quote) } ?: return null
        return cash.toString()
    }
}
