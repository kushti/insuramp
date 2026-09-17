package p2pgate.backend.vault

import p2pgate.backend.store.DealRecord
import p2pgate.backend.store.DealStore
import p2pgate.dealprotocol.DealState

/**
 * The operator's collateral position, `specs/operator-backend.md` §4: one
 * USE unit backs one vault at a 100% ratio, so the pool view is accounting
 * over deal rows, not address-level graphing (the privacy rule — aggregation
 * happens at the amount level).
 *
 * [mixReady] is the pre-mixed reserve balance (privacy-partition funding per
 * §4: vaults draw from the privacy partition, reclaims return to it; M3 has
 * no mixer, so this is a configured balance the operator maintains).
 */
class CollateralPool(
    @Volatile var mixReady: Long,
) {
    /** Collateral locked in open, on-chain vaults (FUNDED-family and claims). */
    fun locked(store: DealStore): Long = store.openDeals()
        .filter { it.state in LOCKING_STATES }
        .sumOf { it.amount }

    /** Free, quoteable collateral — the hard cap on max deal size. */
    fun free(store: DealStore): Long = mixReady - locked(store)

    fun utilizationPct(store: DealStore): Double =
        if (mixReady <= 0) 0.0 else locked(store).toDouble() / mixReady.toDouble() * 100.0

    companion object {
        /** States whose deal still holds a live vault box. */
        val LOCKING_STATES: Set<DealState> = setOf(
            DealState.FUNDED,
            DealState.PAYMENT_PENDING,
            DealState.PAYMENT_CONFIRMED,
            DealState.CLAIM_OPENED,
            DealState.CLAIMABLE,
        )
    }
}

/** Pool snapshot for the dashboard (§4). */
data class PoolView(
    val mixReady: Long,
    val locked: Long,
    val free: Long,
    val utilizationPct: Double,
    val openDeals: Int,
)
