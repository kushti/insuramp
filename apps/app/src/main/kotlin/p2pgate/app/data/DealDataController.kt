package p2pgate.app.data

import p2pgate.app.keys.DealKeyStore

/**
 * The one place that deletes deal data (`specs/android-app.md` §5: "delete all
 * deal data" destroys the Keystore keys *and* wipes the local DB).
 *
 * Both halves are here on purpose: a deletion that left the keys behind would
 * keep signing material on a device the buyer believes is clean, and one that
 * left the rows behind would keep the deal tokens — the bearer credentials for
 * every deal on the device.
 */
class DealDataController(
    /** Exposed read-only: the deal list needs to enumerate without deleting. */
    val repository: DealRepository,
    private val keys: DealKeyStore,
) {

    /** Delete one deal: its snapshot and its deal key. */
    suspend fun delete(dealId: String) {
        repository.delete(dealId)
        keys.delete(dealId)
    }

    /** Delete every deal on the device, keys included. Irreversible. */
    suspend fun deleteAll() {
        repository.all().forEach { keys.delete(it.dealId) }
        repository.deleteAll()
    }
}
