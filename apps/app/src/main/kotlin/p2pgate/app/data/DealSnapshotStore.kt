package p2pgate.app.data

/**
 * Local persistence seam for deal snapshots. Room backs it on-device; the unit
 * tests swap in [InMemoryDealSnapshotStore]. Implementations persist the
 * [DealSnapshot] blob verbatim — no field-level mapping outside the codec.
 */
interface DealSnapshotStore {
    suspend fun upsert(snapshot: DealSnapshot)
    suspend fun get(dealId: String): DealSnapshot?
    suspend fun all(): List<DealSnapshot>
    suspend fun delete(dealId: String)

    /** Wipe every row — the "delete all deal data" path (§5). */
    suspend fun deleteAll()
}

/** JVM/Android in-memory store — the unit-test double for [DealSnapshotStore]. */
class InMemoryDealSnapshotStore : DealSnapshotStore {
    private val deals = LinkedHashMap<String, DealSnapshot>()

    override suspend fun upsert(snapshot: DealSnapshot) {
        deals[snapshot.dealId] = snapshot
    }

    override suspend fun get(dealId: String): DealSnapshot? = deals[dealId]

    override suspend fun all(): List<DealSnapshot> = deals.values.toList()

    override suspend fun delete(dealId: String) {
        deals.remove(dealId)
    }

    override suspend fun deleteAll() {
        deals.clear()
    }
}
