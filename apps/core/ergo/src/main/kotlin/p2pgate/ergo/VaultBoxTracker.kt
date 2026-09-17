package p2pgate.ergo

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
 *  - box spent paying the seller → release (path C/C′, oracle-only) when the
 *    spend's height is within the reclaim window, reclaim (path A) once
 *    `height > timeoutHeight` (both pay the seller's P2PK; the height of the
 *    spend is the on-chain discriminator);
 *  - box spent paying the user → [DealEvent.ClaimPaid] (path D).
 *
 * Payouts are recognized by their P2PK proposition (from the box's R5/R6) —
 * the contracts force `OUTPUTS(0)` to exactly `proveDlog(sellerKey/userKey)`,
 * so tree comparison is exact.
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
        val sellerTreeHex = sellerTreeHex(box) ?: return emptyList()
        val userTreeHex = userTreeHex(box) ?: return emptyList()

        // Path B: a PAYMENT_PROVEN successor of THIS deal exists among the outputs.
        tx.outputs.firstOrNull {
            it.ergoTreeHex.equals(trees.provenPropositionHex, ignoreCase = true) &&
                it.registerBytes(4)?.contentEquals(dealId) == true
        }?.let { return listOf(DealEvent.ClaimOpened(now)) }

        // Path D: the user was paid (from the PAYMENT_PROVEN box).
        if (tx.outputs.any { it.ergoTreeHex.equals(userTreeHex, ignoreCase = true) && it.tokens.isNotEmpty() }) {
            return listOf(DealEvent.ClaimPaid)
        }

        // Paths A / C / C′: the seller was paid. Both reclaim (A) and release
        // (C/C′) pay the seller's P2PK; a release carries the oracle box as a
        // full input (its NFT among the input tokens), while reclaim is only
        // valid past the R8 timeout — either signal discriminates.
        if (tx.outputs.any { it.ergoTreeHex.equals(sellerTreeHex, ignoreCase = true) && it.tokens.isNotEmpty() }) {
            val oracleNftHex = Base16.encode(trees.oracleNftId)
            if (tx.inputTokenIds.any { it.equals(oracleNftHex, ignoreCase = true) }) {
                return listOf(DealEvent.ReleaseObserved)
            }
            val timeoutHeight = timeoutHeight(box)
            return if (timeoutHeight != null && tx.height > timeoutHeight) {
                listOf(DealEvent.ReclaimTimeoutElapsed(now))
            } else {
                listOf(DealEvent.ReleaseObserved)
            }
        }
        return emptyList()
    }

    /** `timeoutHeight` (high word of the FUNDED box's packed R8), or `null` for non-FUNDED boxes. */
    private fun timeoutHeight(box: ChainBox): Int? =
        if (box.ergoTreeHex.equals(trees.fundedPropositionHex, ignoreCase = true)) {
            box.registerLong(8)?.let { (it ushr 32).toInt() }
        } else {
            null
        }

    private fun sellerTreeHex(box: ChainBox): String? =
        box.registerBytes(5)?.let { ErgoValues.treeHex(ErgoValues.p2pkTree(it)) }

    private fun userTreeHex(box: ChainBox): String? =
        box.registerBytes(6)?.let { ErgoValues.treeHex(ErgoValues.p2pkTree(it)) }
}
