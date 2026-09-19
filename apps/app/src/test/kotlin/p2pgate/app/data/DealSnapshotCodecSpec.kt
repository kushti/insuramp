package p2pgate.app.data

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Deal-token storage round trip: the bearer token from deal creation must
 * survive the snapshot codec and the store unchanged (Room persists the blob
 * verbatim; the JVM suite exercises the codec + the same store contract via
 * the in-memory double — Room itself needs a device).
 */
class DealSnapshotCodecSpec {

    private fun snapshot(token: String = "tok-abc-123") = DealSnapshot(
        dealId = "aa".repeat(32),
        token = token,
        state = "PAYMENT_PENDING",
        amount = 500_000_000L,
        fiatAmount = 15_600_00L,
        fiatCurrency = "EGP",
        insuredAmount = 500_000_000L,
        sellerPubKeyHex = "02" + "11".repeat(32),
        createdAtEpochMs = 1_700_000_000_000L,
        updatedAtEpochMs = 1_700_000_100_000L,
        reclaimDeadlineEpochMs = 1_700_086_400_000L,
        verifiedRecordHex = "50325048" + "00".repeat(48),
        verifiedRecordSigAHex = "02" + "22".repeat(32),
        verifiedRecordSigZHex = "33".repeat(32),
        recoveryLink = "https://p2pgate.link/deal/" + "aa".repeat(32) + "#tok-abc-123",
    )

    @Test
    fun `codec round trip preserves the token and all fields`() {
        val original = snapshot()
        val restored = DealSnapshot.decode(DealSnapshot.encode(original))
        assertEquals(original, restored)
        assertEquals("tok-abc-123", restored.token)
    }

    @Test
    fun `store round trip preserves the token`() = runTest {
        val store = InMemoryDealSnapshotStore()
        store.upsert(snapshot())
        assertEquals("tok-abc-123", store.get("aa".repeat(32))?.token)
        assertEquals(listOf("aa".repeat(32)), store.all().map { it.dealId })
        store.delete("aa".repeat(32))
        assertNull(store.get("aa".repeat(32)))
    }

    @Test
    fun `codec ignores unknown fields from a newer app version`() {
        val blob = DealSnapshot.encode(snapshot()).dropLast(1) + ""","futureField":"x"}"""
        val restored = DealSnapshot.decode(blob)
        assertEquals("tok-abc-123", restored.token)
    }
}
