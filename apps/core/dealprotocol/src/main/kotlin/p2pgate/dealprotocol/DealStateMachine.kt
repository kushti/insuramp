package p2pgate.dealprotocol

import java.time.Duration
import java.time.Instant

/**
 * Pure-function deal state machine for the cash→USDT on-ramp, `specs/deal-protocol.md`
 * §1 + §4 (v2: single seller-signed handoff record, oracle-only release).
 *
 * `transition(state, evidence) → newState | Invalid` (`specs/android-app.md` §2.1):
 * every guard is here, including the on-ramp ordering (cash is collected before the
 * crypto leg starts) and the reclaim restriction (once cash has changed hands and no
 * payment proof exists, reclaim is a theft path and is rejected). The machine holds
 * no I/O and no clock of its own — events carry their observation instants.
 *
 * On-chain correlation (`specs/vault-contract.md` §1, §3, §4):
 * - FUNDED-family states (FUNDED, PAYMENT_PENDING, PAYMENT_CONFIRMED) hold the
 *   FUNDED vault box — an instance of `vault_funded.es` (R4 dealId, R5
 *   sellerPubKey, R6 buyerPubKey, R7 oracleNftId, R8 the plain `Long`
 *   timeoutHeight).
 * - CLAIM_OPENED / CLAIMABLE hold the PAYMENT_PROVEN box — `vault_payment_proven.es`,
 *   created by path B, which carried the seller-signed handoff record (one Schnorr
 *   under the seller's R5 key over the P2PH cash-collection message).
 * - RELEASED = box spent via path C/C′, gated on the oracle attestation of the
 *   seller's USDT transfer **alone** — the phase-1 oracle is trusted, period.
 *   RECLAIMED = path A (HEIGHT > timeoutHeight); CLAIMED = path D (HEIGHT >
 *   proofHeight + CLAIM_MATURATION). All collateral-moving paths pay the
 *   recipient in full — there is no protocol fee.
 *
 * Design decisions, made explicit for review:
 * - RECLAIMED is accepted only from FUNDED (buyer no-show — nothing happened) and
 *   PAYMENT_CONFIRMED (the seller already paid and the digest exists, so the seller
 *   reclaiming its own collateral harms no one — the buyer keeps the USDT). It is
 *   rejected from PAYMENT_PENDING: cash has been collected and there is no payment
 *   proof, so reclaim is a theft path; the buyer's answer is the claim.
 * - CLAIM_OPENED is accepted from PAYMENT_PENDING (the honest dispute: cash
 *   collected, seller never paid) and PAYMENT_CONFIRMED (a without-cause claim —
 *   the seller's contest is the oracle signal alone, so [DealEvent.PaymentConfirmed]
 *   landing during a claim marks it contested: the claim is dead and the machine
 *   awaits the seller's path C′ counter-spend rather than leaving a zombie claim).
 * - RELEASED is accepted from PAYMENT_CONFIRMED (routine path C) and from
 *   CLAIM_OPENED / CLAIMABLE (path C′). It is also accepted from PAYMENT_PENDING:
 *   the on-chain C-spend carries the oracle box as a data input, i.e. it *is*
 *   the oracle attestation, so observing the spend subsumes the off-chain
 *   signal when the two race.
 */
data class DealStateMachine(
    val state: DealState = DealState.QUOTED,
    val fundedTimestamp: Instant? = null,
    val proofTimestamp: Instant? = null,
    val claimContested: Boolean = false,
) {

    fun transition(event: DealEvent): TransitionOutcome {
        if (state.isTerminal) return invalid("deal already ${state.name.lowercase()}")
        return transitionNonTerminal(event)
    }

    private fun transitionNonTerminal(event: DealEvent): TransitionOutcome = when (event) {

        // QUOTED has no on-chain footprint; funding creates the FUNDED box
        // (vault_funded.es) and starts the RECLAIM_TIMEOUT deal window.
        is DealEvent.VaultFunded -> when (state) {
            DealState.QUOTED -> moved(
                copy(state = DealState.FUNDED, fundedTimestamp = event.at),
                DealState.FUNDED,
            )
            else -> invalid("vault already funded")
        }

        // The meeting happened: cash collected, seller-signed handoff record
        // complete (the buyer holds it as the dispute artifact). The box is
        // unchanged (still FUNDED); the seller is now obligated to send the USDT.
        // Freshness: the signer's device timestamp must be sane — the buyer never
        // hands cash over a record whose clock is absurd.
        is DealEvent.CashCollected -> {
            val skew = Duration.between(event.recordTimestamp, event.confirmedAt).abs()
            when {
                state != DealState.FUNDED -> invalid(
                    "cash can only be collected against a funded vault",
                )
                skew > ProtocolConstants.HANDOFF_CLOCK_SKEW -> invalid(
                    "handoff record clock is off by more than ${ProtocolConstants.HANDOFF_CLOCK_SKEW.toMinutes()} minutes",
                )
                else -> moved(copy(state = DealState.PAYMENT_PENDING), DealState.PAYMENT_PENDING)
            }
        }

        // Off-chain oracle signal — deliberately does not touch the chain
        // (deal-protocol §1): the digest only goes on-chain with the seller's
        // release (path C/C′), where it is the seller's proof of performance.
        // During a claim the same signal is the seller's contest: the claim is
        // without cause and dead — record it contested and await the C′ spend.
        is DealEvent.PaymentConfirmed -> when (state) {
            DealState.PAYMENT_PENDING -> moved(
                copy(state = DealState.PAYMENT_CONFIRMED), DealState.PAYMENT_CONFIRMED,
            )
            DealState.CLAIM_OPENED, DealState.CLAIMABLE -> moved(
                copy(claimContested = true), state,
            )
            DealState.QUOTED, DealState.FUNDED -> invalid("cash has not been collected yet")
            else -> invalid("payment already confirmed")
        }

        // Box spent by the seller: path C (FUNDED box) or C′ (PAYMENT_PROVEN box),
        // in v2 both gated on the oracle attestation alone. C′ from a claim is the
        // seller's counter to a without-cause claim. From PAYMENT_PENDING the spend
        // subsumes the off-chain oracle signal (the tx carries the oracle box).
        // The buyer app never builds this tx (specs/android-app.md §2.1).
        is DealEvent.ReleaseObserved -> when (state) {
            DealState.PAYMENT_PENDING, DealState.PAYMENT_CONFIRMED,
            DealState.CLAIM_OPENED, DealState.CLAIMABLE -> moved(
                copy(state = DealState.RELEASED), DealState.RELEASED,
            )
            else -> invalid("release requires a collected cash handoff")
        }

        // Path A: vault_funded.es timeout spend back to the seller (in full).
        // Contract guard equivalent: HEIGHT > timeoutHeight. From FUNDED = no-show.
        // From PAYMENT_CONFIRMED = the seller already paid (the digest exists) and
        // the buyer ghosted — the buyer keeps the USDT, so the reclaim harms no one.
        // Rejected from PAYMENT_PENDING: cash has been collected and no payment
        // proof exists — reclaim there is a theft path, answered by a claim.
        is DealEvent.ReclaimTimeoutElapsed -> when (state) {
            DealState.FUNDED, DealState.PAYMENT_CONFIRMED -> {
                val fundedAt = fundedTimestamp
                when {
                    fundedAt == null -> invalid("vault has no funding timestamp")
                    Duration.between(fundedAt, event.at) < ProtocolConstants.RECLAIM_TIMEOUT -> invalid(
                        "reclaim timeout has not elapsed yet",
                    )
                    else -> moved(copy(state = DealState.RECLAIMED), DealState.RECLAIMED)
                }
            }
            DealState.PAYMENT_PENDING -> invalid(
                "cash already collected with no payment proof — the buyer must claim instead",
            )
            DealState.CLAIM_OPENED, DealState.CLAIMABLE -> invalid(
                "handoff record is on-chain — reclaim path no longer exists",
            )
            else -> invalid("nothing to reclaim")
        }

        // Path B: the buyer opened a claim; the tx carries the SELLER-signed
        // handoff record and re-creates the box under vault_payment_proven.es
        // (FUNDED → PAYMENT_PROVEN on-chain). Starts CLAIM_MATURATION, during
        // which the seller counters with the oracle signal alone (path C′).
        is DealEvent.ClaimOpened -> when (state) {
            DealState.PAYMENT_PENDING, DealState.PAYMENT_CONFIRMED -> moved(
                copy(state = DealState.CLAIM_OPENED, proofTimestamp = event.at),
                DealState.CLAIM_OPENED,
            )
            else -> invalid("a claim requires a collected cash handoff and an unspent funded box")
        }

        // Not a spend: the PAYMENT_PROVEN box is unchanged, but HEIGHT > proofHeight
        // + CLAIM_MATURATION now holds, so path D became spendable by the buyer. A
        // contested claim still matures on-chain (the contract never sees the
        // oracle signal); the seller is expected to win the race with C′.
        is DealEvent.ClaimMatured -> when (state) {
            DealState.CLAIM_OPENED -> {
                val proofAt = proofTimestamp
                when {
                    proofAt == null -> invalid("claim has no proof timestamp")
                    Duration.between(proofAt, event.at) < ProtocolConstants.CLAIM_MATURATION -> invalid(
                        "claim maturation has not elapsed yet",
                    )
                    else -> moved(copy(state = DealState.CLAIMABLE), DealState.CLAIMABLE)
                }
            }
            else -> invalid("no open claim to mature")
        }

        // Path D: vault_payment_proven.es payout spend to the buyer's deal-key address
        // (in full). Buyer-side tx, built by the buyer app (specs/android-app.md §4.3).
        // Accepted from CLAIMABLE even when contested: the machine tracks what the
        // chain allows, and the contract cannot see the oracle signal.
        is DealEvent.ClaimPaid -> when (state) {
            DealState.CLAIMABLE -> moved(copy(state = DealState.CLAIMED), DealState.CLAIMED)
            DealState.CLAIM_OPENED -> invalid("claim has not matured yet")
            else -> invalid("no claim payout expected")
        }

        // Quote died before funding: nothing was ever on-chain, so the deal is
        // abandoned rather than moved (Aborted is not a DealState).
        is DealEvent.QuoteExpired -> when (state) {
            DealState.QUOTED -> TransitionOutcome.Aborted
            else -> invalid("quote already funded — use the reclaim path")
        }
    }

    private fun moved(machine: DealStateMachine, to: DealState) =
        TransitionOutcome.Advanced(machine, to)

    private fun invalid(reason: String) = TransitionOutcome.Invalid(reason)

    companion object {
        /** Fresh deal at QUOTED (`specs/deal-protocol.md` §1: buyer picks quote). */
        fun initial(): DealStateMachine = DealStateMachine()
    }
}

sealed interface TransitionOutcome {
    /** Guard passed; [machine] is the next machine and [to] its state. */
    data class Advanced(val machine: DealStateMachine, val to: DealState) : TransitionOutcome

    /** QUOTED expired / buyer ghosted before funding: abandoned, no on-chain footprint. */
    data object Aborted : TransitionOutcome

    /** Guard failed; [reason] is buyer-safe (rendered by the timeline screen). */
    data class Invalid(val reason: String) : TransitionOutcome
}
