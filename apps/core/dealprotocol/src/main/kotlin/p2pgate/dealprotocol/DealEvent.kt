package p2pgate.dealprotocol

import java.time.Instant

/**
 * External evidence fed into the deal state machine. The module never performs
 * I/O itself; callers map observed chain/oracle/handoff facts to these events
 * (`specs/android-app.md` §2.1, `:core:dealprotocol`).
 *
 * Events that claim a timeout or freshness condition carry the observation
 * instant so the machine's guards can verify it against the recorded anchor
 * (`fundedTimestamp` / `proofTimestamp`) instead of trusting the event label.
 */
sealed interface DealEvent {

    /**
     * FUNDED vault box appeared on-chain: the seller locked collateral before the
     * meeting. Anchor: records `fundedTimestamp`, starting the `RECLAIM_TIMEOUT`
     * deal window (`specs/vault-contract.md` §2).
     */
    data class VaultFunded(val at: Instant) : DealEvent

    /**
     * The cash handover completed: the seller physically collected the cash and
     * signed the handoff record (a single Schnorr under the deal's seller
     * key over the P2PH message, `specs/deal-protocol.md` §3.2). The buyer
     * obtained the signed record at the meeting as the dispute artifact.
     * FUNDED → PAYMENT_PENDING: the seller is now obligated to send the USDT.
     */
    data class CashCollected(val recordTimestamp: Instant, val confirmedAt: Instant) : DealEvent

    /**
     * Oracle observed the seller's USDT transfer to the buyer's address (off-chain
     * signal; the box stays FUNDED). PAYMENT_PENDING → PAYMENT_CONFIRMED. From
     * CLAIM_OPENED / CLAIMABLE the same signal is the seller's contest: the claim
     * is without cause and dead — the machine records it as contested and awaits
     * the seller's path C′ counter-spend ([ReleaseObserved]).
     */
    data object PaymentConfirmed : DealEvent

    /**
     * Path C / C′ spend observed: box spent, collateral to seller minus fee.
     * In v2 the spend is gated on the oracle attestation alone — the oracle is
     * trusted, period (`specs/vault-contract.md` §3.3, §4.2).
     */
    data object ReleaseObserved : DealEvent

    /**
     * Path A spend observed (reclaim): the box paid the seller back minus fee.
     * [at] must be ≥ `fundedTimestamp` + [ProtocolConstants.RECLAIM_TIMEOUT].
     */
    data class ReclaimTimeoutElapsed(val at: Instant) : DealEvent

    /**
     * Path B tx landed: the buyer opened a claim carrying the SELLER-signed
     * handoff record — the box is PAYMENT_PROVEN. Anchor: records
     * `proofTimestamp`, starting `CLAIM_MATURATION`.
     */
    data class ClaimOpened(val at: Instant) : DealEvent

    /**
     * `CLAIM_MATURATION` elapsed since the claim landed; the box is now spendable by
     * the buyer (path D). [at] must be ≥ `proofTimestamp` +
     * [ProtocolConstants.CLAIM_MATURATION].
     */
    data class ClaimMatured(val at: Instant) : DealEvent

    /** Path D spend observed: buyer took the collateral minus fee. */
    data object ClaimPaid : DealEvent

    /**
     * Quote expired or the buyer ghosted before funding. Valid only in QUOTED;
     * the deal is abandoned with no on-chain footprint (`specs/deal-protocol.md`
     * §1 diagram), so the outcome is [TransitionOutcome.Aborted] rather than a
     * state — QUOTED, EXPIRED is not one of the canonical states.
     */
    data class QuoteExpired(val at: Instant) : DealEvent
}
