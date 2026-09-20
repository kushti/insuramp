package p2pgate.backend.quotes

import p2pgate.backend.bus.BackendEvent
import p2pgate.backend.bus.EventBus
import p2pgate.backend.infra.InfraMonitor
import p2pgate.backend.store.DealStore
import p2pgate.backend.store.QuoteRecord
import p2pgate.backend.store.StoredEvent
import p2pgate.dealprotocol.ProtocolConstants
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * Quote publishing, `specs/operator-backend.md` §5. The feed holds **multiple
 * concurrent quotes** (e.g., one per seller meeting location); each quote is
 * (spread, ETA promise, min/max deal size) with the hard rules:
 *
 *  - **min ≤ max, both positive** — the min keeps uneconomically small deals
 *    out (the seller's fixed meeting cost), the max is a capacity cap;
 *  - **the fiat currency is a normalized 3-letter code** — lowercase input is
 *    uppercased, anything that is not exactly 3 letters A–Z is refused;
 *  - **max deal size = vault capacity is a hard constraint, per quote** — a
 *    publish whose max size exceeds free (mix-ready, unlocked) collateral
 *    *minus what the other active quotes already promise* is refused
 *    structurally, not by operator discipline: the insured badge is the vault,
 *    and a feed whose quotes outrun collateral ships a smaller badge than
 *    promised;
 *  - **no quotes while verification is degraded** — the infra monitor's
 *    auto-pause withdraws the whole feed (buyers see no quotes, not stale
 *    ones);
 *  - **quotes are versioned and expire individually** — the TTL is aligned
 *    with, and shorter than, `RECLAIM_TIMEOUT`, so a quote can never outlive
 *    the vault funded from it; expired quotes are pruned on read and on the
 *    scheduler tick;
 *  - a quote is withdrawn explicitly by id (operator action) or the whole
 *    feed at once (auto-pause);
 *  - spread below the cost floor publishes with a warning (it is a margin
 *    mistake, not a correctness violation);
 *  - a seller location, when given, is a complete in-range lat/lon pair —
 *    a half-pair or an out-of-range coordinate is refused (the buyer map
 *    renders a pin or nothing, never a broken one).
 */
class QuotePublisher(
    private val store: DealStore,
    private val infra: InfraMonitor,
    private val freeCollateral: () -> Long,
    private val bus: EventBus,
    private val costFloorBps: Int = 0,
    private val ttl: Duration = DEFAULT_TTL,
    private val clock: () -> Instant = Instant::now,
) {
    init {
        require(ttl < ProtocolConstants.RECLAIM_TIMEOUT) {
            "quote TTL $ttl must be shorter than RECLAIM_TIMEOUT ${ProtocolConstants.RECLAIM_TIMEOUT} " +
                "(a quote must never outlive the vault funded from it)"
        }
    }

    sealed interface PublishOutcome {
        data class Published(val quote: QuoteRecord) : PublishOutcome
        data class Rejected(val reason: String) : PublishOutcome
    }

    private val counter = AtomicLong(0)

    /** Adds a new quote to the feed; the existing quotes keep serving. */
    fun publish(
        spreadBps: Int,
        etaMinutes: Int,
        minAmount: Long,
        maxAmount: Long,
        fiatCurrency: String,
        at: Instant = clock(),
        lat: Double? = null,
        lon: Double? = null,
    ): PublishOutcome {
        if (!infra.healthy()) {
            return reject("infra paused — quotes withdrawn (${
                infra.pauseHistory.lastOrNull()?.cause ?: "unknown cause"
            })", at)
        }
        if (spreadBps < 0 || etaMinutes <= 0 || minAmount <= 0 || maxAmount <= 0) {
            return reject(
                "spread/eta/min/max must be positive (got $spreadBps/$etaMinutes/$minAmount/$maxAmount)",
                at,
            )
        }
        if (minAmount > maxAmount) {
            return reject("min deal size $minAmount exceeds max deal size $maxAmount", at)
        }
        val currency = p2pgate.backend.util.FiatCurrency.normalize(fiatCurrency)
            ?: return reject("fiatCurrency must be exactly 3 letters A-Z (got \"$fiatCurrency\")", at)
        if ((lat == null) != (lon == null)) {
            return reject("location must be a lat/lon pair (got lat=$lat lon=$lon)", at)
        }
        if (lat != null && (lat !in -90.0..90.0 || lon!! !in -180.0..180.0)) {
            return reject("location out of range (lat=$lat lon=$lon)", at)
        }
        pruneExpired(at)
        // Capacity honesty across the feed: the other active quotes have
        // already promised their maxAmount against the same pool.
        val reserved = store.quotes().sumOf { it.maxAmount }
        val free = freeCollateral()
        val available = free - reserved
        if (maxAmount > available) {
            return reject(
                "max deal size $maxAmount exceeds free collateral $available " +
                    "(free $free, reserved $reserved by ${store.quotes().size} other active quote(s))",
                at,
            )
        }
        if (spreadBps < costFloorBps) {
            bus.publish(
                BackendEvent.QuoteWarning(
                    "spread $spreadBps bps below cost floor $costFloorBps bps",
                    at,
                ),
            )
        }
        val quote = QuoteRecord(
            id = "quote-${counter.incrementAndGet()}",
            version = (store.quotes().maxOfOrNull { it.version } ?: 0L) + 1L,
            spreadBps = spreadBps,
            etaMinutes = etaMinutes,
            minAmount = minAmount,
            maxAmount = maxAmount,
            fiatCurrency = currency,
            createdAt = at,
            expiresAt = at.plus(ttl),
            lat = lat,
            lon = lon,
        )
        store.saveQuote(quote)
        bus.publish(BackendEvent.QuotePublished(quote, at))
        return PublishOutcome.Published(quote)
    }

    /** Withdraws the whole feed (auto-pause, or operator action). */
    fun withdraw(cause: String?, at: Instant = clock()) {
        if (store.quotes().isEmpty()) return
        store.clearQuotes()
        store.appendEvent(StoredEvent(null, "QUOTE_WITHDRAWN", cause ?: "manual", at))
        bus.publish(BackendEvent.QuoteWithdrawn(cause, at))
    }

    /**
     * Withdraws a single quote by id (operator action — the seller at that
     * location stopped taking meetings). `false` when the id is unknown.
     */
    fun withdraw(id: String, cause: String? = null, at: Instant = clock()): Boolean {
        val removed = store.removeQuote(id) ?: return false
        store.appendEvent(StoredEvent(null, "QUOTE_WITHDRAWN", "${removed.id}: ${cause ?: "manual"}", at))
        bus.publish(BackendEvent.QuoteWithdrawn(cause, at, quoteId = removed.id))
        return true
    }

    /** The quotes served to buyers right now — empty while paused. */
    fun active(at: Instant = clock()): List<QuoteRecord> {
        pruneExpired(at)
        if (!infra.healthy()) return emptyList()
        return store.quotes()
    }

    /** One active quote by id (deal creation pins the deal to its quote). */
    fun activeQuote(id: String, at: Instant = clock()): QuoteRecord? =
        active(at).firstOrNull { it.id == id }

    /** All unexpired quotes regardless of pause (the dashboard view). */
    fun all(at: Instant = clock()): List<QuoteRecord> {
        pruneExpired(at)
        return store.quotes()
    }

    /** Scheduled sweep: withdraws every quote whose TTL has lapsed. */
    fun tick(at: Instant = clock()) = pruneExpired(at)

    /** Removes expired quotes, eventing each withdrawal ("ttl expired"). */
    private fun pruneExpired(at: Instant) {
        for (quote in store.quotes()) {
            if (!at.isBefore(quote.expiresAt)) {
                store.removeQuote(quote.id)
                store.appendEvent(StoredEvent(null, "QUOTE_WITHDRAWN", "${quote.id}: ttl expired", at))
                bus.publish(BackendEvent.QuoteWithdrawn("ttl expired", at, quoteId = quote.id))
            }
        }
    }

    private fun reject(reason: String, at: Instant): PublishOutcome.Rejected {
        store.appendEvent(StoredEvent(null, "QUOTE_REJECTED", reason, at))
        bus.publish(BackendEvent.QuoteRejected(reason, at))
        return PublishOutcome.Rejected(reason)
    }

    companion object {
        /** Default TTL — well under the 24h reclaim timeout. */
        val DEFAULT_TTL: Duration = Duration.ofMinutes(30)
    }
}
