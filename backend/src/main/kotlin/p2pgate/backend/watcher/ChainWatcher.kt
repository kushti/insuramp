package p2pgate.backend.watcher

import p2pgate.backend.engine.DealEngine
import p2pgate.backend.store.DealStore
import p2pgate.dealprotocol.DealEvent
import p2pgate.dealprotocol.DealState
import p2pgate.dealprotocol.ProtocolConstants
import p2pgate.ergo.ChainSource
import p2pgate.ergo.ErgoContracts
import p2pgate.ergo.VaultBoxTracker
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Chain watcher, `specs/operator-backend.md` §2: polls the operator's vault
 * boxes through [VaultBoxTracker] and feeds the resulting events into the deal
 * engine. It **never holds state** — the store row is the source of truth; its
 * only memory is a per-deal dedupe set so re-observing an already-applied
 * on-chain fact is not re-dispatched (and cannot spam the violation log).
 *
 * What comes from where, per the spec parenthetical: `CashCollected` comes
 * from the handoff-record upload and `PaymentConfirmed` from the oracle client — not
 * from here. This watcher covers the on-chain facts: `VaultFunded`,
 * `ClaimOpened`, `ReleaseObserved`, `ReclaimTimeoutElapsed`, `ClaimPaid`, plus
 * the off-chain-but-scheduled `ClaimMatured` countdown derived from the
 * claim's proof timestamp.
 */
class ChainWatcher(
    private val chain: ChainSource,
    private val trees: ErgoContracts.VaultTrees,
    private val engine: DealEngine,
    private val store: DealStore,
    private val reclaimTimeout: Duration = ProtocolConstants.RECLAIM_TIMEOUT,
    /**
     * Whether this watcher drives timeout transitions for *unspent* funded
     * boxes. `false` (the backend default): the reclaim scheduler owns that
     * transition, because it must build the reclaim tx in the same breath as
     * the state change — a watcher-driven `RECLAIMED` would strand the vault
     * with no spend. Spent-box facts (an observed path-A spend, release,
     * claim) are always mirrored.
     */
    private val observeTimeouts: Boolean = false,
) {
    /** Per-deal set of already-dispatched fact kinds. */
    private val applied = ConcurrentHashMap<String, MutableSet<String>>()

    /** One sweep over all open deals. Manual `tick()` in tests; a fixed-delay loop in the Ktor module. */
    fun tick(now: Instant = Instant.now()): List<DealEngine.Result> {
        val results = mutableListOf<DealEngine.Result>()
        for (deal in store.openDeals()) {
            // Claim maturation is a wall-clock countdown from the claim's proof
            // timestamp (the on-chain anchor is HEIGHT > proofHeight +
            // CLAIM_MATURATION; off-chain the deal machine uses instants).
            if (deal.state == DealState.CLAIM_OPENED && deal.proofTimestamp != null &&
                !Duration.between(deal.proofTimestamp, now).minus(ProtocolConstants.CLAIM_MATURATION).isNegative &&
                mark(deal.dealId, "ClaimMatured")
            ) {
                results += engine.apply(deal.dealId, DealEvent.ClaimMatured(now), now)
            }

            val watchedBoxId = deal.provenBoxId ?: deal.vaultBoxId ?: continue
            val fundedAt = deal.fundedAt ?: continue
            val tracker = VaultBoxTracker(chain, trees, fundedAt, reclaimTimeout)
            val box = chain.getBox(watchedBoxId) ?: continue
            val observed = if (box.spentTransactionId == null) {
                val events = tracker.classify(VaultBoxTracker.VaultBoxState.Unspent(box), now)
                if (observeTimeouts) events else events.filterNot { it is DealEvent.ReclaimTimeoutElapsed }
            } else {
                val spend = chain.getSpendingTransaction(watchedBoxId) ?: continue
                tracker.classify(VaultBoxTracker.VaultBoxState.Spent(box, spend), now)
            }
            for (event in observed) {
                if (!mark(deal.dealId, event::class.simpleName ?: continue)) continue
                val r = engine.apply(deal.dealId, event, now)
                results += r
                // Path B landed: repoint the watch at the PAYMENT_PROVEN box so
                // later C′/D spends classify against it.
                if (r is DealEngine.Result.Advanced && event is DealEvent.ClaimOpened) {
                    captureProvenBox(deal.dealId, watchedBoxId)
                }
            }
        }
        return results
    }

    private fun captureProvenBox(dealId: String, spentBoxId: String) {
        val spend = chain.getSpendingTransaction(spentBoxId) ?: return
        val deal = store.getDeal(dealId) ?: return
        val proven = spend.outputs.firstOrNull { out ->
            out.ergoTreeHex.equals(trees.provenPropositionHex, ignoreCase = true) &&
                out.registerBytes(4)?.let { p2pgate.backend.util.Hex.encode(it) == deal.dealId } == true
        } ?: return
        store.updateDeal(dealId) { it.copy(provenBoxId = proven.boxId) }
    }

    /** Records [kind] as dispatched for [dealId]; `false` when already recorded. */
    private fun mark(dealId: String, kind: String): Boolean =
        applied.getOrPut(dealId) { ConcurrentHashMap.newKeySet() }.add(kind)
}
