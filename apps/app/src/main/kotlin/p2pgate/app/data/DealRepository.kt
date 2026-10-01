package p2pgate.app.data

import p2pgate.app.net.BackendClient
import p2pgate.app.net.CreateDealRequest
import p2pgate.app.net.DealDto

/** Maps a backend deal DTO onto the existing snapshot (token/verified record survive). */
fun snapshotFromDto(dto: DealDto, existing: DealSnapshot?, nowEpochMs: Long): DealSnapshot =
    DealSnapshot(
        dealId = dto.dealId,
        token = existing?.token ?: "",
        state = dto.state,
        amount = dto.amount,
        fiatAmount = dto.fiatAmount,
        fiatCurrency = dto.fiatCurrency,
        insuredAmount = dto.insuredAmount,
        sellerPubKeyHex = dto.sellerPubKey ?: existing?.sellerPubKeyHex,
        createdAtEpochMs = dto.createdAtEpochMs,
        updatedAtEpochMs = nowEpochMs,
        reclaimDeadlineEpochMs = dto.reclaimDeadlineEpochMs,
        claimMaturesAtEpochMs = dto.claimMaturesAtEpochMs,
        contested = dto.contested,
        terminal = dto.terminal,
        offerExpiresAtEpochMs = existing?.offerExpiresAtEpochMs,
        verifiedRecordHex = existing?.verifiedRecordHex,
        verifiedRecordSigAHex = existing?.verifiedRecordSigAHex,
        verifiedRecordSigZHex = existing?.verifiedRecordSigZHex,
        recoveryLink = existing?.recoveryLink,
    )

/**
 * Deal repository: the only writer of [DealSnapshot]s. Creation persists the
 * deal token immediately; refresh/sync re-reads the backend and folds the DTO
 * into the snapshot; recovery re-presents a pasted link's token
 * (`specs/android-app.md` §6.3).
 *
 * [backendBaseUrl] is the operator the app talks to — the recovery link is
 * built from it, because a link is only useful if it points at the operator
 * that can answer for the deal (a hardcoded domain would send a recovering
 * buyer to an operator that has never heard of the deal).
 */
class DealRepository(
    private val backend: BackendClient,
    private val store: DealSnapshotStore,
    private val backendBaseUrl: String = "",
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun create(
        request: CreateDealRequest,
        fiatAmount: Long,
        fiatCurrency: String,
        recoveryLink: String?,
        offerExpiresAtEpochMs: Long?,
    ): DealSnapshot {
        val response = backend.createDeal(request)
        val snapshot = DealSnapshot(
            dealId = response.dealId,
            token = response.dealToken,
            state = "QUOTED",
            amount = request.amount,
            fiatAmount = fiatAmount,
            fiatCurrency = fiatCurrency,
            insuredAmount = request.amount,
            createdAtEpochMs = clock(),
            updatedAtEpochMs = clock(),
            offerExpiresAtEpochMs = offerExpiresAtEpochMs,
            recoveryLink = recoveryLink ?: recoveryLinkFor(response.dealId, response.dealToken),
        )
        store.upsert(snapshot)
        return snapshot
    }

    /**
     * The one-time recovery link (`specs/android-app.md` §5): the deal id and
     * its token in a URL fragment. The token is the only credential a buyer who
     * lost the app has, so it is shown once at creation and reachable again
     * from the deal list.
     */
    fun recoveryLinkFor(dealId: String, token: String): String {
        val base = backendBaseUrl.trimEnd('/')
        return if (base.isEmpty()) {
            // No operator configured (tests): a bare fragment is still a usable
            // relative link, and the app renders it as such.
            "/deal/$dealId#$token"
        } else {
            "$base/deal/$dealId#$token"
        }
    }

    /** GET /v1/deals/{id} + fold; the deal token authorizes the read. */
    suspend fun refresh(dealId: String): DealSnapshot {
        val existing = store.get(dealId)
        require(existing != null) { "no local deal $dealId — recover it first" }
        val dto = backend.deal(dealId, existing.token)
        val snapshot = snapshotFromDto(dto, existing, clock())
        store.upsert(snapshot)
        return snapshot
    }

    /** Deal recovery by link token: the app re-presents the token and re-syncs. */
    suspend fun recover(link: DealLinkParser.DealLink): DealSnapshot {
        val existing = store.get(link.dealId)
        val seed = existing?.copy(token = link.token)
            ?: DealSnapshot(
                dealId = link.dealId,
                token = link.token,
                state = "QUOTED",
                amount = 0,
                fiatAmount = 0,
                fiatCurrency = "",
                insuredAmount = 0,
                createdAtEpochMs = clock(),
                updatedAtEpochMs = clock(),
            )
        store.upsert(seed)
        return refresh(link.dealId)
    }

    /** The meeting gate passed — persist the verified record (before upload). */
    suspend fun markVerifiedRecord(dealId: String, recordHex: String, sigAHex: String, sigZHex: String) {
        val existing = store.get(dealId) ?: return
        store.upsert(
            existing.copy(
                verifiedRecordHex = recordHex,
                verifiedRecordSigAHex = sigAHex,
                verifiedRecordSigZHex = sigZHex,
                updatedAtEpochMs = clock(),
            ),
        )
    }

    suspend fun get(dealId: String): DealSnapshot? = store.get(dealId)

    suspend fun all(): List<DealSnapshot> = store.all()

    suspend fun delete(dealId: String) {
        store.delete(dealId)
    }

    /** Wipe every local deal row (keys are the caller's business — see [DealDataController]). */
    suspend fun deleteAll() {
        store.deleteAll()
    }
}
