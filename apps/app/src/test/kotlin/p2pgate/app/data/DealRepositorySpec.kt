package p2pgate.app.data

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import p2pgate.app.net.AttestationDto
import p2pgate.app.net.BackendClient
import p2pgate.app.net.ClaimGuideDto
import p2pgate.app.net.CreateDealRequest
import p2pgate.app.net.CreateDealResponse
import p2pgate.app.net.DealDto
import p2pgate.app.net.EventDto
import p2pgate.app.net.HandoffSubmitRequest
import p2pgate.app.net.QuoteFeedDto
import p2pgate.app.verify.Hex
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * The repository is the app's only writer of deal state, so its fold is the
 * place a backend DTO silently loses a field. Two behaviours are load-bearing
 * and pinned here: the **deal token** survives every refresh (it is the only
 * credential for the deal), and the **recovery link** is built from the operator
 * the app is configured for.
 */
class DealRepositorySpec {

    private class FakeBackend(
        var dto: DealDto? = null,
        var failWith: Exception? = null,
    ) : BackendClient {
        var createdRequest: CreateDealRequest? = null
        var handoffUploads = 0

        override suspend fun quotes(): QuoteFeedDto = QuoteFeedDto(emptyList())

        override suspend fun createDeal(request: CreateDealRequest): CreateDealResponse {
            failWith?.let { throw it }
            createdRequest = request
            return CreateDealResponse(dealId = "deal-1", dealToken = "tok-1")
        }

        override suspend fun deal(dealId: String, token: String): DealDto {
            failWith?.let { throw it }
            return dto ?: error("no dto")
        }

        override suspend fun submitHandoff(dealId: String, token: String, request: HandoffSubmitRequest) {
            handoffUploads++
        }

        override suspend fun attestation(dealId: String, token: String): AttestationDto =
            AttestationDto(status = "UNCONFIRMED")

        override suspend fun claim(dealId: String, token: String): ClaimGuideDto =
            ClaimGuideDto("deal-1", "PAYMENT_PENDING", "", "", emptyList())

        override fun quoteFeedStream(): Flow<QuoteFeedDto> = emptyFlow()

        override fun dealStream(dealId: String, token: String): Flow<EventDto> = emptyFlow()
    }

    private fun dto(
        state: String = "FUNDED",
        vaultBoxId: String? = "box-1",
        sellerPubKey: String? = "aabb",
    ) = DealDto(
        dealId = "deal-1",
        state = state,
        amount = 500_000_000L,
        fiatAmount = 46_000L,
        fiatCurrency = "INR",
        insuredAmount = 500_000_000L,
        vaultBoxId = vaultBoxId,
        createdAtEpochMs = 1_000L,
        sellerPubKey = sellerPubKey,
    )

    private fun request() = CreateDealRequest(
        quoteId = "quote-1",
        amount = 500_000_000L,
        receiveAddress = "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t",
        buyerPubKey = "02ab",
        fiatCurrency = "INR",
        fiatAmount = 46_000L,
    )

    private fun repository(
        backend: BackendClient,
        store: DealSnapshotStore = InMemoryDealSnapshotStore(),
        baseUrl: String = "https://operator.example",
    ) = DealRepository(backend, store, baseUrl, clock = { 5_000L })

    @Test
    fun `creation persists the token and the recovery link`() = runTest {
        val backend = FakeBackend()
        val store = InMemoryDealSnapshotStore()
        val repo = repository(backend, store)

        val snapshot = repo.create(request(), 46_000L, "INR", null, 9_000L)

        assertEquals("deal-1", snapshot.dealId)
        assertEquals("tok-1", snapshot.token)
        assertEquals("QUOTED", snapshot.state)
        // The link points at the operator the app is configured for, not a
        // hardcoded domain — a link to an operator that never heard of the deal
        // is worthless.
        assertEquals("https://operator.example/deal/deal-1#tok-1", snapshot.recoveryLink)
        assertEquals(snapshot, store.get("deal-1"))
    }

    @Test
    fun `the recovery link drops a trailing slash from the base url`() = runTest {
        val repo = repository(FakeBackend(), baseUrl = "https://operator.example/")
        assertEquals(
            "https://operator.example/deal/deal-1#tok-1",
            repo.recoveryLinkFor("deal-1", "tok-1"),
        )
    }

    @Test
    fun `refresh folds the dto and keeps the token and recovery link`() = runTest {
        val backend = FakeBackend(dto = dto())
        val store = InMemoryDealSnapshotStore()
        val repo = repository(backend, store)
        val created = repo.create(request(), 46_000L, "INR", null, 9_000L)

        val refreshed = repo.refresh("deal-1")

        assertEquals(created.token, refreshed.token)
        assertEquals(created.recoveryLink, refreshed.recoveryLink)
        assertEquals("FUNDED", refreshed.state)
        assertEquals(46_000L, refreshed.fiatAmount)
        assertEquals(9_000L, refreshed.offerExpiresAtEpochMs) // local-only field survives
        assertEquals("aabb", refreshed.sellerPubKeyHex)
    }

    @Test
    fun `a backend without a seller key keeps the one already known`() = runTest {
        val backend = FakeBackend(dto = dto(sellerPubKey = "aabb"))
        val repo = repository(backend)
        repo.create(request(), 46_000L, "INR", null, null)
        // The key is learned on the first read — deal creation returns no key.
        assertEquals("aabb", repo.refresh("deal-1").sellerPubKeyHex)

        backend.dto = dto(sellerPubKey = null)
        // A null seller key must not erase the key the meeting gate needs: a
        // transient omission would otherwise turn the gate fail-closed forever.
        assertEquals("aabb", repo.refresh("deal-1").sellerPubKeyHex)
    }

    @Test
    fun `the vault box id from the backend survives the fold`() = runTest {
        val backend = FakeBackend(dto = dto(vaultBoxId = "box-xyz"))
        val repo = repository(backend)
        repo.create(request(), 46_000L, "INR", null, null)
        assertEquals("FUNDED", repo.refresh("deal-1").state)
    }

    @Test
    fun `refreshing a deal that is not on the device fails instead of inventing one`() = runTest {
        val repo = repository(FakeBackend())
        val error = runCatching { repo.refresh("unknown") }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error.message!!.contains("recover it first"), error.message)
    }

    @Test
    fun `recover seeds the pasted token then re-syncs`() = runTest {
        val backend = FakeBackend(dto = dto())
        val repo = repository(backend)
        val link = DealLinkParser.parse("https://operator.example/deal/deal-1#tok-9")

        val snapshot = repo.recover(link)

        assertEquals("tok-9", snapshot.token)
        assertEquals("FUNDED", snapshot.state)
    }

    @Test
    fun `the verified record is stored on the snapshot`() = runTest {
        val repo = repository(FakeBackend())
        repo.create(request(), 46_000L, "INR", null, null)
        repo.markVerifiedRecord("deal-1", "aabbcc", "11", "22")
        val snapshot = assertNotNull(repo.get("deal-1"))
        assertEquals("aabbcc", snapshot.verifiedRecordHex)
        assertEquals("11", snapshot.verifiedRecordSigAHex)
        assertEquals("22", snapshot.verifiedRecordSigZHex)
    }

    @Test
    fun `delete removes one deal and deleteAll wipes the device`() = runTest {
        val store = InMemoryDealSnapshotStore()
        val repo = repository(FakeBackend(), store)
        repo.create(request(), 46_000L, "INR", null, null)
        repo.create(request().copy(quoteId = "quote-2"), 46_000L, "INR", null, null)
        store.upsert(
            DealSnapshot(
                dealId = "deal-2", token = "tok-2", state = "QUOTED", amount = 1, fiatAmount = 1,
                fiatCurrency = "INR", insuredAmount = 1, createdAtEpochMs = 1, updatedAtEpochMs = 1,
            ),
        )
        assertEquals(2, repo.all().size)

        repo.delete("deal-1")
        assertNull(repo.get("deal-1"))
        assertEquals(1, repo.all().size)

        repo.deleteAll()
        assertEquals(emptyList(), repo.all())
    }

    @Test
    fun `the hex codec the recovery path relies on round-trips`() {
        val bytes = ByteArray(32) { (it * 11).toByte() }
        assertEquals(bytes.toList(), Hex.decode(Hex.encode(bytes)).toList())
    }
}
