package p2pgate.backend.util

/**
 * Fiat currency code validation (quotes and deals each carry one): exactly
 * three ASCII letters, normalized to uppercase — `usd` and `USD` are the
 * same code. Digits and symbols are not currencies here.
 */
object FiatCurrency {
    /** The normalized code, or `null` when [raw] is not exactly 3 letters A–Z. */
    fun normalize(raw: String): String? {
        val upper = raw.uppercase()
        return upper.takeIf { it.length == 3 && it.all { c -> c in 'A'..'Z' } }
    }
}
