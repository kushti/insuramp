package p2pgate.ergo

import p2pgate.contracts.ContractParams
import p2pgate.dealprotocol.DealEvent
import p2pgate.dealprotocol.ProtocolConstants
import java.time.Duration
import java.time.Instant

/**
 * Vault box monitoring, `specs/android-app.md` §4.2: polls a vault box id and
 * maps chain facts to [DealEvent]s for the deal state machine. Stateless at
 * its core — [classify] is a pure function of the observed [VaultBoxState]
 * plus the wall-clock anchor of funding; [pollOnce] is the thin `ChainSource`
 * wrapper. No threads, no coroutines: the caller schedules polling
 * (Android WorkManager later, §2.1).
 *
 * Classification (§4.2 rows):
 *  - box unspent, FUNDED, timeout not reached → no events (caller keeps
 *    FUNDED / PAYMENT_PENDING);
 *  - box unspent, FUNDED, `fundedAt + RECLAIM_TIMEOUT` passed →
 *    [DealEvent.ReclaimTimeoutElapsed] (the machine's guard validates the
 *    instant against its own anchor);
 *  - box unspent, PAYMENT_PROVEN (the watcher was repointed at the successor)
 *    → [DealEvent.ClaimOpened];
 *  - box spent into a PAYMENT_PROVEN successor (path B) → [DealEvent.ClaimOpened];
 *  - box spent paying out the collateral → release (path C/C′, oracle-attested)
 *    or reclaim (path A, past `timeoutHeight`), or [DealEvent.ClaimPaid] (path D);
 *    the spend's shape decides which, not its destination.
 *
 * Payouts are recognized by **conservation, not by destination**: since the
 * 2026-09-24 payout-freedom change every collateral-moving path pays the full
 * collateral to *any* address (key rotation — the signing side's `proveDlog`
 * authorizes the spend), so the payout tree carries no information. What the
 * contracts do pin is `OUTPUTS(0)` carrying the vault box's whole collateral, so
 * that is the check ([conservesCollateral]), and the path is read off the spend
 * itself:
 *  - path B is a PAYMENT_PROVEN output with this deal's R4;
 *  - paths C/C′ carry the attestation box as a DATA input (path C′ *always*
 *    does; path D requires `dataInputs.size == 0`), which is what separates a
 *    contested claim the seller won (`RELEASED`) from the buyer's claim payout
 *    (`CLAIMED`) — a discrimination the destination tree used to make;
 *  - path A is the only FUNDED payout that needs no data input, and it is
 *    spendable only past `timeoutHeight`.
 *
 * Degraded backends: a backend that does not serve data inputs reports
 * [ChainSpend.dataInputCount] `0`, so a post-timeout release reads as a reclaim
 * and a post-maturation contest as a claim payout. Both shipped backends serve
 * them; the release-vs-reclaim tie is then broken by the oracle NFT among the
 * data-input token ids where available ([ChainSpend.dataInputTokenIds]).
 */
class VaultBoxTracker(
    private val chain: ChainSource,
    private val trees: ErgoContracts.VaultTrees,
    /** Wall-clock instant the FUNDED box was observed (the `VaultFunded` anchor). */
    private val fundedAt: Instant,
    private val reclaimTimeout: Duration = ProtocolConstants.RECLAIM_TIMEOUT,
) {

    /** One observation of the watched box. */
    sealed interface VaultBoxState {
        /** The box is unspent on-chain. */
        class Unspent(val box: ChainBox) : VaultBoxState

        /** The box was spent by [spendingTx]. */
        class Spent(val box: ChainBox, val spendingTx: ChainSpend) : VaultBoxState
    }

    /**
     * One polling pass over [boxId]: reads the box and, when spent, its
     * spending transaction; maps the result to fresh events. Returns an empty
     * list when nothing new is observable (box unspent, timeout not reached),
     * or when the box is unknown — callers should treat long unknown-box
     * stretches as an explorer problem, not a deal event.
     */
    fun pollOnce(boxId: String, now: Instant = Instant.now()): List<DealEvent> {
        val box = chain.getBox(boxId) ?: return emptyList()
        return if (box.spentTransactionId == null) {
            classify(VaultBoxState.Unspent(box), now)
        } else {
            val spend = chain.getSpendingTransaction(boxId) ?: return emptyList()
            classify(VaultBoxState.Spent(box, spend), now)
        }
    }

    /** Pure mapping from an observed box state to deal-machine events. */
    fun classify(state: VaultBoxState, now: Instant): List<DealEvent> = when (state) {
        is VaultBoxState.Unspent -> classifyUnspent(state.box, now)
        is VaultBoxState.Spent -> classifySpent(state.box, state.spendingTx, now)
    }

    // ---------------------------------------------------------------- unspent

    private fun classifyUnspent(box: ChainBox, now: Instant): List<DealEvent> = when {
        box.ergoTreeHex.equals(trees.provenPropositionHex, ignoreCase = true) ->
            listOf(DealEvent.ClaimOpened(now))
        box.ergoTreeHex.equals(trees.fundedPropositionHex, ignoreCase = true) ->
            if (Duration.between(fundedAt, now) >= reclaimTimeout) {
                listOf(DealEvent.ReclaimTimeoutElapsed(now))
            } else {
                emptyList()
            }
        else -> emptyList() // not one of our vault boxes — nothing new to report
    }

    // ---------------------------------------------------------------- spent

    private fun classifySpent(box: ChainBox, tx: ChainSpend, now: Instant): List<DealEvent> {
        val dealId = box.registerBytes(4) ?: return emptyList()
        val funded = box.ergoTreeHex.equals(trees.fundedPropositionHex, ignoreCase = true)
        val proven = box.ergoTreeHex.equals(trees.provenPropositionHex, ignoreCase = true)
        if (!funded && !proven) return emptyList() // not one of our vault boxes

        // Path B: a PAYMENT_PROVEN successor of THIS deal exists among the outputs.
        tx.outputs.firstOrNull {
            it.ergoTreeHex.equals(trees.provenPropositionHex, ignoreCase = true) &&
                it.registerBytes(4)?.contentEquals(dealId) == true
        }?.let { return listOf(DealEvent.ClaimOpened(now)) }

        // Everything left is a payout path (A, C, C′, D), and every one of them
        // puts the vault box's whole collateral at OUTPUTS(0) while leaving the
        // payee free. Recognize that conservation, then read the path off the
        // spend's shape.
        if (!conservesCollateral(box, tx)) return emptyList()

        val oracleNftHex = Base16.encode(trees.oracleNftId)
        val attested = tx.inputTokenIds.any { it.equals(oracleNftHex, ignoreCase = true) } ||
            tx.dataInputTokenIds.any { it.equals(oracleNftHex, ignoreCase = true) }
        if (attested) return listOf(DealEvent.ReleaseObserved)

        if (proven) {
            // Path D (the buyer's claim payout) requires NO data input and
            // HEIGHT > proofHeight + CLAIM_MATURATION; path C′ (the seller's
            // contest) always carries the attestation data input. So a spend
            // with data inputs is C′, and one without can only be D if the
            // claim had matured — below that the contract makes D impossible.
            val claimSpendable = box.registerLong(7)
                ?.let { tx.height.toLong() > it + ContractParams.CLAIM_MATURATION_BLOCKS } == true
            return if (tx.dataInputCount > 0 || !claimSpendable) {
                listOf(DealEvent.ReleaseObserved)
            } else {
                listOf(DealEvent.ClaimPaid)
            }
        }

        // FUNDED box: path A (reclaim) needs HEIGHT > timeoutHeight and is the
        // only payout here that needs no data input; path C (release) needs the
        // attestation, already handled above. Past the timeout the two are
        // seller-signed and payout-identical on chain, so a data-input-free spend
        // is read as the reclaim it can only be.
        val timeout = timeoutHeight(box)
        return if (tx.dataInputCount == 0 && timeout != null && tx.height > timeout) {
            listOf(DealEvent.ReclaimTimeoutElapsed(now))
        } else {
            listOf(DealEvent.ReleaseObserved)
        }
    }

    /**
     * Whether some output carries the watched box's whole collateral — the one
     * thing every payout path pins (`OUTPUTS(0).tokens(0)` equals
     * `SELF.tokens(0)` in both vault scripts). Compared per token kind and with
     * `>=` so a top-up from another input in the same spend still counts. The
     * destination is deliberately NOT checked: the payee is free (key rotation).
     */
    private fun conservesCollateral(box: ChainBox, tx: ChainSpend): Boolean =
        box.tokens.all { token ->
            tx.outputs.any { it.tokenAmount(token.tokenId) >= token.amount }
        }

    /** `timeoutHeight` (the FUNDED box's plain-Long R8), or `null` for non-FUNDED boxes. */
    private fun timeoutHeight(box: ChainBox): Int? =
        if (box.ergoTreeHex.equals(trees.fundedPropositionHex, ignoreCase = true)) {
            box.registerLong(8)?.toInt()
        } else {
            null
        }
}
