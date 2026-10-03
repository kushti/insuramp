package p2pgate.tui.common

import java.time.Duration
import java.time.Instant
import java.util.Locale

/**
 * Formatting helpers shared by both consoles.
 *
 * Deliberately pure — every function is a plain string transform so the screens
 * that call them can be snapshot-tested without a terminal, and so a wording
 * change is a one-line diff rather than a hunt through composables.
 *
 * Two rules the whole repo follows: amounts are shown in the unit the deal is
 * denominated in (USDT has 6 decimals, fiat has none), and the deal state names
 * are the canonical ones from `specs/deal-protocol.md` §1 — never a synonym.
 */
object Format {

    /** USDT amounts are 6-decimal base units (`deal-protocol.md` §3.1). */
    const val USDT_DECIMALS = 6

    /** Compact base-unit amount: 500_000_000 → `500`. */
    fun usdt(baseUnits: Long): String =
        trimZeros(baseUnits.toDouble() / Math.pow(10.0, USDT_DECIMALS.toDouble()))

    /** Fiat amounts are whole basic units — no decimals exist in the format. */
    fun fiat(amount: Long, currency: String): String = "$amount $currency"

    /** The quote's rate: micros of fiat per 1 USDT → `92.00 INR/USDT`. */
    fun rate(fiatPerUsdtMicros: Long, currency: String): String {
        val value = fiatPerUsdtMicros / 1_000_000.0
        return String.format(Locale.ROOT, "%.2f %s/USDT", value, currency)
    }

    /** Countdown in the coarse form an operator scans: `3h 12m`, `12m`, `40s`. */
    fun countdown(until: Instant?, now: Instant = Instant.now()): String {
        if (until == null) return "—"
        val d = Duration.between(now, until)
        if (d.isNegative) return "passed"
        val totalSeconds = d.seconds
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }

    /** Elapsed wall-clock since a deal was created or funded. */
    fun elapsed(since: Instant?, now: Instant = Instant.now()): String {
        if (since == null) return "—"
        val d = Duration.between(since, now)
        if (d.isNegative) return "—"
        val totalMinutes = d.toMinutes()
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            totalMinutes > 0 -> "${totalMinutes}m"
            else -> "${d.seconds}s"
        }
    }

    /** Truncated ids read better than full 64-char hashes on a narrow board. */
    fun shortId(id: String, head: Int = 8): String =
        if (id.length <= head + 1) id else id.take(head) + "…"

    /** Token ids in a vault card: the leading `ab…` is enough to recognise it. */
    fun shortHex(hex: String, head: Int = 6): String =
        if (hex.length <= head + 1) hex else hex.take(head) + "…"

    private fun trimZeros(value: Double): String {
        val rounded = Math.round(value * 1e6) / 1e6
        return if (rounded == Math.floor(rounded) && !rounded.isInfinite()) {
            rounded.toLong().toString()
        } else {
            String.format(Locale.ROOT, "%.6f", rounded).trimEnd('0').trimEnd('.')
        }
    }
}
