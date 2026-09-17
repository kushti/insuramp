package p2pgate.backend.store

import p2pgate.backend.aml.AmlDecision
import p2pgate.backend.util.Hex
import p2pgate.dealprotocol.DealState
import p2pgate.dealprotocol.DealTerms
import java.time.Instant

/**
 * One AML decision on file for a deal — decision + scorer id + timestamp only,
 * per the recording rule (`specs/operator-backend.md` §6). [checkedAddressHex]
 * is the address the decision was scored against; when it differs from the
 * deal's current receive address, funding re-checks (address-swap defense).
 */
data class AmlRecord(
    val decision: AmlDecision,
    val scorerId: String,
    val checkedAddressHex: String,
    val at: Instant,
)

/** Append-only event log entry; [dealId] is null for operator-level events. */
data class StoredEvent(val dealId: String?, val kind: String, val detail: String, val at: Instant)

/**
 * The published quote triple (`specs/operator-backend.md` §5): spread (bps
 * over reference), ETA promise (minutes from FUNDED to the meeting), max deal
 * size (a hard capacity cap — never publish above free collateral).
 * [version] increments on every publish; [expiresAt] bounds the TTL.
 */
data class QuoteRecord(
    val id: String,
    val version: Long,
    val spreadBps: Int,
    val etaMinutes: Int,
    val maxAmount: Long,
    val createdAt: Instant,
    val expiresAt: Instant,
)

/**
 * One deal row — the store's unit of record. Holds everything the modules
 * need without re-deriving it: the encoded terms fields (the full
 * [p2pgate.dealprotocol.DealTerms] rebuilds via [terms]), chain references
 * (vault box id, proven box id after path B), evidence references
 * (handoff record + GPS), the contested flag, and the AML decision trail.
 *
 * A deal is [isOpen] while it is neither terminal nor abandoned
 * (`QuoteExpired` abandons a QUOTED deal — no canonical EXPIRED state).
 */
data class DealRecord(
    val dealId: String,
    val quoteId: String,
    val dealNonceHex: String,
    val quoteExpiry: Long,
    val asset: Int,
    val srcChainId: Int,
    val amount: Long,
    val fiatAmount: Long,
    val fiatCurrency: String,
    val buyerPubKeyHex: String,
    val sellerPubKeyHex: String,
    val recipientAddrHex: String,
    val collateralTokenIdHex: String,
    val createdAt: Instant,
    val state: DealState = DealState.QUOTED,
    val fundedAt: Instant? = null,
    val proofTimestamp: Instant? = null,
    val contested: Boolean = false,
    val vaultBoxId: String? = null,
    val provenBoxId: String? = null,
    val handoffRecordHex: String? = null,
    val handoffGpsRef: String? = null,
    val abandoned: Boolean = false,
    val lossRecorded: Boolean = false,
    val claimAction: String? = null,
    val escalated: Boolean = false,
    val amlRecords: List<AmlRecord> = emptyList(),
) {
    val isOpen: Boolean get() = !abandoned && !state.isTerminal

    /** Rebuilds the canonical deal terms; [DealTerms.dealId] equals [dealId]. */
    fun terms(): DealTerms = DealTerms(
        dealNonce = Hex.decode(dealNonceHex),
        asset = asset,
        srcChainId = srcChainId,
        amount = amount,
        fiatAmount = fiatAmount,
        fiatCurrency = fiatCurrency.toByteArray(Charsets.US_ASCII),
        buyerPubKey = Hex.decode(buyerPubKeyHex),
        sellerPubKey = Hex.decode(sellerPubKeyHex),
        quoteExpiry = quoteExpiry,
    )

    /** The most recent AML decision, if any. */
    fun latestAml(): AmlRecord? = amlRecords.lastOrNull()
}

/**
 * Deal persistence seam, `specs/operator-backend.md` §2 "deal engine"
 * ("State is persisted transactionally; DB row = source of truth").
 *
 * M3 ships the interface plus a thread-safe in-memory implementation —
 * a restart loses state (documented deviation; the spec names PostgreSQL).
 * The interface is shaped so a JDBC implementation drops in behind it:
 * single-row atomic updates, an append-only event log, and one current-quote row.
 */
interface DealStore {
    fun createDeal(record: DealRecord)
    fun getDeal(dealId: String): DealRecord?

    /**
     * Atomically replaces the deal row with [transform] applied; `null` when
     * the deal does not exist. This is the only mutation primitive — the
     * engine persists before dispatching any event.
     */
    fun updateDeal(dealId: String, transform: (DealRecord) -> DealRecord): DealRecord?

    /** All non-terminal, non-abandoned deals (the watcher/scheduler sweep set). */
    fun openDeals(): List<DealRecord>
    fun allDeals(): List<DealRecord>

    /** The append-only event log (transitions, invariant violations, tx refs). */
    fun appendEvent(event: StoredEvent)
    fun events(dealId: String? = null): List<StoredEvent>

    /** The currently published quote; `null` when withdrawn or never published. */
    fun saveQuote(quote: QuoteRecord)
    fun clearQuote()
    fun currentQuote(): QuoteRecord?
}

/** Thread-safe in-memory [DealStore] (M3 default; see the interface doc). */
class InMemoryDealStore : DealStore {
    private val lock = java.util.concurrent.locks.ReentrantLock()
    private val deals = LinkedHashMap<String, DealRecord>()
    private val eventLog = mutableListOf<StoredEvent>()
    private var quote: QuoteRecord? = null

    override fun createDeal(record: DealRecord) = locked {
        require(!deals.containsKey(record.dealId)) { "deal ${record.dealId} already exists" }
        deals[record.dealId] = record
    }

    override fun getDeal(dealId: String): DealRecord? = locked { deals[dealId] }

    override fun updateDeal(dealId: String, transform: (DealRecord) -> DealRecord): DealRecord? = locked {
        val existing = deals[dealId] ?: return@locked null
        val updated = transform(existing)
        deals[dealId] = updated
        updated
    }

    override fun openDeals(): List<DealRecord> = locked { deals.values.filter { it.isOpen } }

    override fun allDeals(): List<DealRecord> = locked { deals.values.toList() }

    override fun appendEvent(event: StoredEvent) = locked { eventLog += event }

    override fun events(dealId: String?): List<StoredEvent> = locked {
        if (dealId == null) eventLog.toList() else eventLog.filter { it.dealId == dealId }
    }

    override fun saveQuote(quote: QuoteRecord) = locked { this.quote = quote }

    override fun clearQuote() = locked { quote = null }

    override fun currentQuote(): QuoteRecord? = locked { quote }

    private fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}
