package p2pgate.backend.quotes

import p2pgate.backend.bus.BackendEvent
import p2pgate.backend.bus.EventBus
import p2pgate.backend.infra.InfraMonitor
import p2pgate.backend.store.DealStore
import p2pgate.backend.store.QuoteRecord
import p2pgate.contracts.ContractParams
import p2pgate.dealprotocol.ProtocolConstants
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * Quote publishing, `specs/operator-backend.md` §5. A quote is the triple
 * (spread, ETA promise, max deal size) with the hard rules:
 *
 *  - **max deal size = vault capacity is a hard constraint** — a publish whose
 *    max size exceeds free (mix-ready, unlocked) collateral is refused
 *    structurally, not by operator discipline: the insured badge is the vault,
 *    and a quote that outruns collateral ships a smaller badge than promised;
 *  - **no quotes while verification is degraded** — the infra monitor's
 *    auto-pause withdraws the feed (buyers see no quotes, not stale ones);
 *  - **quotes are versioned and expire** — TTL is aligned with, and shorter
 *    than, `RECLAIM_TIMEOUT`, so a quote can never outlive the vault funded
 *    from it;
 *  - spread below protocol fee + cost floor publishes with a warning (it is a
 *    margin mistake, not a correctness violation).
 */
class QuotePublisher(
    private val store: DealStore,
    private val infra: InfraMonitor,
    private val freeCollateral: () -> Long,
    private val bus: EventBus,
    /** The protocol fee quoted against the spread — the canonical compile-time
     *  contract fee (`ContractParams.PROTOCOL_FEE_BPS`); injectable for tests. */
    private val protocolFeeBps: Int = ContractParams.PROTOCOL_FEE_BPS,
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

    fun publish(spreadBps: Int, etaMinutes: Int, maxAmount: Long, at: Instant = clock()): PublishOutcome {
        if (!infra.healthy()) {
            return reject("infra paused — quotes withdrawn (${
                infra.pauseHistory.lastOrNull()?.cause ?: "unknown cause"
            })", at)
        }
        if (spreadBps < 0 || etaMinutes <= 0 || maxAmount <= 0) {
            return reject("spread/eta/maxAmount must be positive (got $spreadBps/$etaMinutes/$maxAmount)", at)
        }
        val free = freeCollateral()
        if (maxAmount > free) {
            return reject("max deal size $maxAmount exceeds free collateral $free", at)
        }
        if (spreadBps < protocolFeeBps + costFloorBps) {
            bus.publish(
                BackendEvent.QuoteWarning(
                    "spread $spreadBps bps below protocol fee $protocolFeeBps + cost floor $costFloorBps bps",
                    at,
                ),
            )
        }
        val previous = store.currentQuote()
        val quote = QuoteRecord(
            id = "quote-${counter.incrementAndGet()}",
            version = (previous?.version ?: 0L) + 1L,
            spreadBps = spreadBps,
            etaMinutes = etaMinutes,
            maxAmount = maxAmount,
            createdAt = at,
            expiresAt = at.plus(ttl),
        )
        store.saveQuote(quote)
        bus.publish(BackendEvent.QuotePublished(quote, at))
        return PublishOutcome.Published(quote)
    }

    /** Withdraws the feed (auto-pause, TTL expiry, or operator action). */
    fun withdraw(cause: String?, at: Instant = clock()) {
        if (store.currentQuote() == null) return
        store.clearQuote()
        store.appendEvent(p2pgate.backend.store.StoredEvent(null, "QUOTE_WITHDRAWN", cause ?: "manual", at))
        bus.publish(BackendEvent.QuoteWithdrawn(cause, at))
    }

    /** The quote served to buyers right now — `null` while paused or expired. */
    fun active(at: Instant = clock()): QuoteRecord? =
        store.currentQuote()?.takeIf { infra.healthy() && at.isBefore(it.expiresAt) }

    /** The latest published quote regardless of pause/expiry (dashboard view). */
    fun current(): QuoteRecord? = store.currentQuote()

    /** Scheduled sweep: withdraws the current quote once its TTL lapses. */
    fun tick(at: Instant = clock()) {
        val quote = store.currentQuote() ?: return
        if (!at.isBefore(quote.expiresAt)) withdraw("ttl expired", at)
    }

    private fun reject(reason: String, at: Instant): PublishOutcome.Rejected {
        store.appendEvent(p2pgate.backend.store.StoredEvent(null, "QUOTE_REJECTED", reason, at))
        bus.publish(BackendEvent.QuoteRejected(reason, at))
        return PublishOutcome.Rejected(reason)
    }

    companion object {
        /** Default TTL — well under the 24h reclaim timeout. */
        val DEFAULT_TTL: Duration = Duration.ofMinutes(30)
    }
}
