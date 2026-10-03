package p2pgate.tui.buyer

import p2pgate.dealprotocol.DealState

/**
 * What the buyer console is allowed to do next, as a pure function of the facts
 * (`specs/tui-apps.md` §5). Nothing here touches the network or the chain, which
 * is what makes the *interesting* part — when a claim may be opened, when the
 * payout is unlocked — testable without either.
 *
 * The two claim paths differ in exactly one way that matters here, and the whole
 * consent design follows from it:
 *
 *  - **path B, claim-open** (`ClaimTxBuilder.buildClaimOpen`) is `sigmaProp`-only —
 *    it carries the *seller's* signature as a context variable, so the buyer
 *    needs no private key to open a claim;
 *  - **path D, payout** (`buildClaimPayout`) needs `proveDlog(buyerKey)`.
 *
 * So a buyer who only ever claims can run the console with no key file at all.
 */
object BuyerFlow {

    /** The buyer's actions, in the order they become meaningful. */
    enum class Action(val key: String, val label: String, val needsKey: Boolean) {
        /** Attach the seller-signed handoff record to the deal (the meeting). */
        UPLOAD_HANDOFF("u", "upload the seller-signed handoff record", needsKey = false),

        /** Build and broadcast path B — open the claim. No key needed. */
        OPEN_CLAIM("o", "open the claim (path B, no key needed)", needsKey = false),

        /** Build and broadcast path D — take the payout. Needs the deal key. */
        TAKE_PAYOUT("p", "take the claim payout (path D, needs the key)", needsKey = true),

        /** Write a fresh key file for a deal that has not funded yet. */
        CREATE_KEY("k", "create a key file for this deal", needsKey = false),

        /** Refresh from the backend. */
        REFRESH("r", "refresh", needsKey = false),

        /** Forget the deal token on this machine. */
        FORGET("f", "forget this deal", needsKey = false),
    }

    /**
     * The facts the decision needs. [hasRecord] is whether the buyer holds a
     * seller-signed handoff record for this deal; [hasKey] whether a decryptable
     * key file exists; [claimBlocksRemaining] the distance to maturity, or `null`
     * when no claim is open (nothing to count down to).
     */
    data class Facts(
        val state: DealState?,
        val hasRecord: Boolean = false,
        val hasKey: Boolean = false,
        val vaultBoxObserved: Boolean = false,
        val claimBlocksRemaining: Int? = null,
        val funded: Boolean = false,
    )

    /**
     * The actions available, in display order.
     *
     * `FUNDED` offers the handoff upload *and* a key creation: the buyer is still
     * able to write a key, because a key written after funding can only ever be
     * used for this deal's payout — the vault pins the buyer key in R6, so a
     * different key would not spend the box anyway. After funding the key is
     * chosen, so the console stops offering to create one.
     *
     * A claim is offered from `PAYMENT_PENDING` and `PAYMENT_CONFIRMED` — the two
     * states `DealStateMachine` accepts `ClaimOpened` from — and only when the
     * buyer holds the seller's signed record. That record is the whole basis of
     * the dispute: it proves the cash was handed over, which is exactly what
     * reclaim is refused for in those states, and it is what makes the claim an
     * honest one. A seller who actually sent the USDT answers with the oracle
     * attestation (path C′); a seller who did not, loses.
     *
     * `CLAIM_OPENED` is where the chain side takes over — the box has moved to
     * `PAYMENT_PROVEN` and the buyer is counting down to maturation — so the
     * offer there is a resume, not an opening.
     */
    fun actionsFor(facts: Facts): List<Action> = buildList {
        if (!facts.funded) add(Action.CREATE_KEY)
        when (facts.state) {
            DealState.FUNDED -> add(Action.UPLOAD_HANDOFF)
            DealState.PAYMENT_PENDING, DealState.PAYMENT_CONFIRMED ->
                if (facts.hasRecord) add(Action.OPEN_CLAIM)
            DealState.CLAIM_OPENED -> if (facts.hasRecord) add(Action.OPEN_CLAIM)
            DealState.CLAIMABLE -> if (facts.hasKey) add(Action.TAKE_PAYOUT)
            else -> Unit
        }
        add(Action.REFRESH)
        add(Action.FORGET)
    }

    /**
     * Why an action is unavailable, for the status line. Returns `null` when the
     * action *is* available.
     *
     * The wording names the missing thing rather than refusing abstractly: "no
     * handoff record — scan it at the meeting" tells a buyer what to do, and
     * "withdrawing a claim needs the deal key" tells them which file is missing.
     */
    fun blockedReason(action: Action, facts: Facts): String? = when {
        !actionsFor(facts).contains(action) -> when (action) {
            Action.CREATE_KEY -> if (facts.funded) "the deal is already funded — the key is fixed" else "not available now"
            Action.UPLOAD_HANDOFF -> "only from FUNDED"
            Action.OPEN_CLAIM -> when {
                facts.hasRecord -> "not available in ${stateLabel(facts.state)}"
                facts.state == DealState.FUNDED -> "no handoff record yet — scan the seller's QR at the meeting"
                else -> "no handoff record — the claim needs the seller-signed record as evidence"
            }
            Action.TAKE_PAYOUT -> when {
                facts.hasKey -> "the claim has not matured yet"
                else -> "the payout needs the deal key (path D) — write the key file with `k`"
            }
            Action.REFRESH, Action.FORGET -> "not available now"
        }
        else -> null
    }

    /** Human-readable name for a state, or `?` before the first deal loads. */
    fun stateLabel(state: DealState?): String = state?.name ?: "no deal"

    /**
     * Blocks left before a path-D payout is allowed: the contract requires
     * `HEIGHT > proofHeight + CLAIM_MATURATION`, so the payout opens on the block
     * *after* the maturation delay elapses. Returns 0 when it is already open.
     *
     * `null` when no claim is open — there is nothing to count down to, and
     * rendering "0 blocks left" there would read as "go ahead", which is the
     * opposite of the truth.
     */
    fun blocksRemaining(proofHeight: Int?, currentHeight: Int, maturationBlocks: Int): Int? {
        if (proofHeight == null) return null
        return (proofHeight + maturationBlocks + 1 - currentHeight).coerceAtLeast(0)
    }

    /** True when the payout is unlocked right now. */
    fun payoutReady(proofHeight: Int?, currentHeight: Int, maturationBlocks: Int): Boolean =
        blocksRemaining(proofHeight, currentHeight, maturationBlocks) == 0

    /**
     * The one-line answer to "can I leave the meeting yet?", the gate
     * `onramp-ux.md` §2.3 describes. A buyer who leaves without a verified
     * seller-signed record has no evidence at all, so this is deliberately a
     * hard gate rather than a warning.
     */
    fun safeToLeave(facts: Facts): Boolean = facts.hasRecord
}