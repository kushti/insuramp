package p2pgate.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The locally persisted view of one deal — everything the app needs to render
 * timelines, run the meeting gate, and recover a deal on a fresh install
 * (`specs/android-app.md` §5/§6.3). The **deal token** (bearer credential from
 * `POST /v1/deals`) lives here; losing it means losing deal status access, so
 * it is stored in the local DB, never transmitted anywhere else.
 *
 * The snapshot serializes to a single JSON blob; Room persists the blob
 * opaquely (key-value shape), which keeps all mapping logic in this pure-Kotlin
 * type — unit-testable on the JVM without Robolectric.
 */
@Serializable
data class DealSnapshot(
    val dealId: String,
    val token: String,
    val state: String,
    val amount: Long,
    val fiatAmount: Long,
    val fiatCurrency: String,
    val insuredAmount: Long,
    val sellerPubKeyHex: String? = null,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val reclaimDeadlineEpochMs: Long? = null,
    val claimMaturesAtEpochMs: Long? = null,
    val contested: Boolean = false,
    /** Set once the meeting gate passed: the verified seller-signed record. */
    val verifiedRecordHex: String? = null,
    val verifiedRecordSigAHex: String? = null,
    val verifiedRecordSigZHex: String? = null,
    /** Optional recovery link shown once at deal creation. */
    val recoveryLink: String? = null,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun encode(snapshot: DealSnapshot): String = json.encodeToString(serializer(), snapshot)

        fun decode(blob: String): DealSnapshot = json.decodeFromString(serializer(), blob)
    }
}
