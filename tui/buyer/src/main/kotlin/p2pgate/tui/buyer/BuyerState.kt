package p2pgate.tui.buyer

import p2pgate.dealprotocol.DealState
import p2pgate.tui.common.wire.AttestationDto
import p2pgate.tui.common.wire.ClaimGuideDto
import p2pgate.tui.common.wire.DealDto
import p2pgate.tui.common.wire.QuoteDto

/**
 * Everything the buyer console draws, as one immutable value — the same shape
 * discipline as the seller console's [p2pgate.tui.seller.SellerState], and for
 * the same reason: the screen is a pure function of this, so it can be
 * snapshot-tested headlessly.
 */
data class BuyerState(
    /** Live quote feed, newest first. */
    val quotes: List<QuoteDto> = emptyList(),
    /** Index of the highlighted quote in [quotes]. */
    val selectedQuote: Int = 0,
    /** The deal this console is following, if one was created or restored. */
    val deal: DealDto? = null,
    /** What the backend says the claim path needs (the funded box, the record). */
    val claimGuide: ClaimGuideDto? = null,
    /** The oracle's view of the payment. `null` before the first fetch. */
    val attestation: AttestationDto? = null,
    /** Chain facts the flow needs, read from the chain rather than the backend. */
    val chainHeight: Int? = null,
    /**
     * R7 of the PAYMENT_PROVEN box — the block the claim-open was mined at. Read
     * from the chain via `ChainBox.registerLong(7)`, which decodes the sigma
     * `Coll[Long]` properly; the backend's hex DTO is a serialised form this
     * console deliberately does not try to re-parse.
     */
    val proofHeight: Int? = null,
    /** R5 of the vault box, read from the chain — the seller key we trust. */
    val vaultSellerPubKey: String? = null,
    /** The key file's status. Presence only; never the secret. */
    val key: KeyStatus = KeyStatus(),
    /**
     * Where the USDT goes — a **TRON** address (`T…`), and nothing to do with the
     * Ergo deal key.
     *
     * These are two different keys for two different chains, and conflating them
     * is a mistake this console made once: it offered its Ergo P2PK address as
     * `receiveAddress` and the backend refused the deal with "receiveAddress is not
     * a valid TRON address". The Ergo key authorises the *claim spend*; this
     * address receives the *USDT*.
     */
    val payoutAddress: String? = null,
    /** The captured handoff record, if the buyer has one. */
    val handoff: StoredHandoff? = null,
    /** Live events from the backend, newest first. */
    val events: List<String> = emptyList(),
    /** Transient message line. */
    val status: String = "connecting…",
    /** Epoch millis of the last *action's* status message; 0 = the poll may write. */
    val statusFromActionAt: Long = 0L,
    /** Epoch millis of the last successful refresh; 0 before the first one. */
    val lastSyncedAt: Long = 0L,
    val busy: Boolean = false,
) {
    val state: DealState? get() = deal?.let { runCatching { DealState.valueOf(it.state) }.getOrNull() }

    /** The decision inputs, assembled from what is known right now. */
    val facts: BuyerFlow.Facts
        get() = BuyerFlow.Facts(
            state = state,
            hasRecord = handoff != null,
            hasKey = key.present,
            vaultBoxObserved = deal?.vaultBoxId != null,
            claimBlocksRemaining = blocksRemaining,
            funded = deal?.fundedAtEpochMs != null,
        )

    /**
     * Blocks until a path-D payout unlocks, from the chain's current height and
     * the PAYMENT_PROVEN box's R7 `proofHeight`. `null` when no claim is open or
     * the box has not been observed yet — see `BuyerFlow.blocksRemaining` for why
     * that is not the same as "ready".
     */
    val blocksRemaining: Int?
        get() {
            val height = chainHeight ?: return null
            return BuyerFlow.blocksRemaining(proofHeight, height, CLAIM_MATURATION_BLOCKS)
        }

    /** True when a path-D payout is unlocked right now. */
    val payoutReady: Boolean
        get() {
            val height = chainHeight ?: return false
            return BuyerFlow.payoutReady(proofHeight, height, CLAIM_MATURATION_BLOCKS)
        }

    /** True when an action wrote [status] recently enough to survive the poll. */
    fun statusPinned(at: Long, windowMs: Long): Boolean =
        statusFromActionAt != 0L && at - statusFromActionAt in 0 until windowMs

    companion object {
        /**
         * Mirrors `ContractParams.CLAIM_MATURATION_BLOCKS`; the buyer only needs
         * it to render a countdown, and the contract is the authority on the
         * spend. Mainnet keeps the canonical 12h value.
         */
        const val CLAIM_MATURATION_BLOCKS = 360
    }
}

/** The key file's status for the status bar. Nothing here is secret. */
data class KeyStatus(
    val path: String = "",
    val present: Boolean = false,
    /** First four bytes of the secret's SHA-256 — identifies a key, reveals nothing. */
    val fingerprint: String? = null,
    /** The address the key derives, so it can be checked against the deal. */
    val address: String? = null,
) {
    val line: String
        get() = when {
            path.isEmpty() -> "key: not configured"
            !present -> "key: none at $path — press k to create one"
            else -> "key: $address (${fingerprint ?: "?"}) at $path"
        }
}
