package p2pgate.dealprotocol

import java.util.Locale

/**
 * The two legs of a deal, and the rate between them (`specs/android-app.md`
 * §3.1, `onramp-ux.md` §2.1).
 *
 * A quote publishes a **rate** in micros of fiat per 1 USDT
 * (`fiatPerUsdtMicros`); USDT amounts are in base units (6 decimals, so 1 USDT =
 * 1_000_000) and cash amounts in whole basic units of the deal's fiat currency
 * (`DealTerms.fiatAmount`, no decimals — see `specs/deal-protocol.md` §3.1).
 *
 * The buyer types the **USDT** leg (the one the quote's min/max bounds and the
 * vault collateral are denominated in); the cash leg is derived. The app derives
 * it to *display* and the operator backend re-derives it to *validate* — so both
 * sides call these exact functions and cannot disagree by a unit. The seller's
 * margin is folded into the rate (owner decision, 2026-09-27): `spreadBps` stays
 * seller-side metadata for the cost-floor warning and is not applied here.
 */
object FiatAmounts {

    /** USDT base units per USDT (Tether's 6 decimals). */
    const val USDT_DECIMALS = 6
    const val USDT_SCALE: Long = 1_000_000L

    /** Micros per whole basic unit of fiat. */
    const val FIAT_MICROS: Long = 1_000_000L

    /**
     * Denominator of the cash derivation: USDT base units → whole USDT is a
     * divide by [USDT_SCALE], and whole USDT × micros-per-USDT → whole cash is
     * a divide by [FIAT_MICROS]. The two never cancel out — 1 USDT is 10^6 base
     * units *and* a rate is quoted in 10^6 micros.
     */
    private const val CASH_SCALE: Long = USDT_SCALE * FIAT_MICROS

    /**
     * The cash leg for [usdtBase] USDT base units at [fiatPerUsdtMicros],
     * rounded half-up to whole basic units. Both the buyer app and the operator
     * backend call this, so a submitted pair is always self-consistent.
     *
     * Overflow is rejected rather than silently wrapped: [usdtBase] and
     * [fiatPerUsdtMicros] are both operator/buyer-supplied, and a wrapped
     * product would be a wrong cash figure pinned in the deal terms.
     */
    fun cashFor(usdtBase: Long, fiatPerUsdtMicros: Long): Long {
        require(usdtBase >= 0) { "USDT amount must be non-negative, got $usdtBase" }
        require(fiatPerUsdtMicros > 0) { "rate must be positive, got $fiatPerUsdtMicros" }
        require(usdtBase <= Long.MAX_VALUE / fiatPerUsdtMicros) {
            "rate × amount overflows Long ($fiatPerUsdtMicros × $usdtBase)"
        }
        val product = usdtBase * fiatPerUsdtMicros
        val quotient = product / CASH_SCALE
        val remainder = product % CASH_SCALE
        // Round half-up on the remainder: rem >= scale/2 carries.
        return if (remainder >= CASH_SCALE / 2) quotient + 1 else quotient
    }

    /**
     * Zero-padded fixed-width rendering. `Locale.ROOT` throughout: the app ships
     * hi/sw/ar/ru, and a default-locale `%d` would emit Arabic-Indic digits into
     * a number that also has to be parsed back by the backend.
     */
    private fun fixed6(value: Long): String = String.format(Locale.ROOT, "%06d", value)

    /**
     * `micros` rendered as a plain decimal with trailing zeros trimmed —
     * `31_200_000` → `"31.2"`, `1_000_000` → `"1"`. Locale-independent: the
     * decimal separator is always `.` (the app formats with the string resource
     * and a localized currency label around it).
     */
    fun formatMicros(micros: Long): String {
        require(micros >= 0) { "micros must be non-negative, got $micros" }
        val whole = micros / FIAT_MICROS
        val fraction = micros % FIAT_MICROS
        if (fraction == 0L) return whole.toString()
        val digits = fixed6(fraction).trimEnd('0')
        return "$whole.$digits"
    }

    /** The quote-card rate, e.g. `formatRate(31_200_000)` → `"1 USDT = 31.2"`. */
    fun formatRate(fiatPerUsdtMicros: Long): String = "1 USDT = ${formatMicros(fiatPerUsdtMicros)}"

    /**
     * USDT base units as a trimmed decimal, e.g. `500_000_000` → `"500"`,
     * `1_234_500` → `"1.2345"`. The mirror of [formatMicros], for the other leg.
     */
    fun formatUsdt(usdtBase: Long): String {
        require(usdtBase >= 0) { "USDT amount must be non-negative, got $usdtBase" }
        val whole = usdtBase / USDT_SCALE
        val fraction = usdtBase % USDT_SCALE
        if (fraction == 0L) return whole.toString()
        val digits = fixed6(fraction).trimEnd('0')
        return "$whole.$digits"
    }
}
