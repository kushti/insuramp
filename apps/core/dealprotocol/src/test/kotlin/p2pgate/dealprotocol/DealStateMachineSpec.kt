package p2pgate.dealprotocol

import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Transition matrix for the on-ramp deal state machine, `specs/deal-protocol.md` §1 + §4
 * (v2: single courier-signed handoff record, oracle-only release, 12h maturation).
 * Covers: happy path, no-show reclaim, paid-and-ghosted reclaim, the claim branch from
 * PAYMENT_PENDING (seller never paid) and without-cause claims, the oracle-signal contest
 * of an open claim (path C′ counter), release from PAYMENT_PENDING (on-chain attestation
 * subsumes the off-chain signal), on-ramp sequencing (cash before crypto leg), freshness
 * guards, terminal-state absorption, and boundary values for RECLAIM_TIMEOUT (24h) and
 * CLAIM_MATURATION (12h).
 */
class DealStateMachineSpec {

    private val t0 = Instant.ofEpochSecond(1_700_000_000L)
    private val day = ProtocolConstants.RECLAIM_TIMEOUT
    private val maturation = ProtocolConstants.CLAIM_MATURATION
    private fun seconds(n: Long) = Duration.ofSeconds(n)

    private fun advanced(machine: DealStateMachine, event: DealEvent): DealStateMachine {
        val outcome = machine.transition(event)
        assertIs<TransitionOutcome.Advanced>(outcome, "expected advance on $event from ${machine.state}")
        return outcome.machine
    }

    private fun invalid(machine: DealStateMachine, event: DealEvent): String {
        val outcome = machine.transition(event)
        return assertIs<TransitionOutcome.Invalid>(outcome, "expected invalid on $event from ${machine.state}").reason
    }

    private fun funded(at: Instant = t0) = advanced(DealStateMachine.initial(), DealEvent.VaultFunded(at))
    private fun cashCollected(at: Instant = t0, confirmedAt: Instant = at) = advanced(
        funded(at),
        DealEvent.CashCollected(courierTimestamp = at, confirmedAt = confirmedAt),
    )
    private fun paymentConfirmed(at: Instant = t0) = advanced(cashCollected(at), DealEvent.PaymentConfirmed)

    // ---------- happy path ----------

    @Test
    fun `initial state is QUOTED and not terminal`() {
        val m = DealStateMachine.initial()
        assertEquals(DealState.QUOTED, m.state)
        assertTrue(!m.state.isTerminal)
        assertNull(m.fundedTimestamp)
        assertNull(m.proofTimestamp)
        assertFalse(m.claimContested)
    }

    @Test
    fun `happy path reaches RELEASED as terminal`() {
        val released = paymentConfirmed().let { advanced(it, DealEvent.ReleaseObserved) }
        assertEquals(DealState.RELEASED, released.state)
        assertTrue(released.state.isTerminal)
    }

    @Test
    fun `vault funded records the funding timestamp`() {
        val m = funded(t0)
        assertEquals(DealState.FUNDED, m.state)
        assertEquals(t0, m.fundedTimestamp)
    }

    @Test
    fun `cash collected moves FUNDED to PAYMENT_PENDING`() {
        assertEquals(DealState.PAYMENT_PENDING, cashCollected().state)
    }

    @Test
    fun `payment confirmed moves PAYMENT_PENDING to PAYMENT_CONFIRMED`() {
        assertEquals(DealState.PAYMENT_CONFIRMED, paymentConfirmed().state)
    }

    @Test
    fun `release observed from PAYMENT_CONFIRMED is the routine path C`() {
        // v2: the oracle attestation alone gates the release — no receipt signature.
        val m = advanced(paymentConfirmed(t0), DealEvent.ReleaseObserved)
        assertEquals(DealState.RELEASED, m.state)
    }

    @Test
    fun `release observed from PAYMENT_PENDING subsumes the off-chain oracle signal`() {
        // The on-chain C-spend carries the oracle box as a full input — it *is* the
        // attestation, so the spend wins when it races the off-chain signal.
        val m = advanced(cashCollected(t0), DealEvent.ReleaseObserved)
        assertEquals(DealState.RELEASED, m.state)
    }

    // ---------- full invalid matrix: every (state, event) pair ----------

    /**
     * States where an event is invalid exclude it from their list below; the timing
     * events embed an instant that is deliberately *not* a valid timeout observation
     * (before funding / before maturation), so the state guard is what fires rather
     * than the timing guard.
     */
    private fun assertInvalidFor(machine: DealStateMachine, events: List<DealEvent>) {
        for (event in events) {
            assertTrue(
                invalid(machine, event).isNotBlank(),
                "expected invalid: $event from ${machine.state}",
            )
        }
    }

    @Test
    fun `invalid matrix - QUOTED`() {
        assertInvalidFor(DealStateMachine.initial(), listOf(
            DealEvent.CashCollected(t0, t0),
            DealEvent.PaymentConfirmed,
            DealEvent.ReleaseObserved,
            DealEvent.ReclaimTimeoutElapsed(t0.minus(day)),
            DealEvent.ClaimOpened(t0),
            DealEvent.ClaimMatured(t0),
            DealEvent.ClaimPaid,
            // VaultFunded (valid) and QuoteExpired (Aborted) covered separately.
        ))
    }

    @Test
    fun `invalid matrix - FUNDED`() {
        assertInvalidFor(funded(t0), listOf(
            DealEvent.VaultFunded(t0.plus(seconds(1))),
            DealEvent.PaymentConfirmed,
            DealEvent.ReleaseObserved,
            DealEvent.ReclaimTimeoutElapsed(t0), // before timeout elapsed
            DealEvent.ClaimOpened(t0),
            DealEvent.ClaimMatured(t0),
            DealEvent.ClaimPaid,
            DealEvent.QuoteExpired(t0.plus(day)),
            // CashCollected (valid) covered above.
        ))
    }

    @Test
    fun `invalid matrix - PAYMENT_PENDING`() {
        assertInvalidFor(cashCollected(t0), listOf(
            DealEvent.VaultFunded(t0.plus(seconds(1))),
            DealEvent.CashCollected(t0, t0),
            DealEvent.ReclaimTimeoutElapsed(t0),
            DealEvent.ClaimMatured(t0),
            DealEvent.ClaimPaid,
            DealEvent.QuoteExpired(t0.plus(day)),
            // PaymentConfirmed, ReleaseObserved and ClaimOpened (valid) covered separately.
        ))
    }

    @Test
    fun `invalid matrix - PAYMENT_CONFIRMED`() {
        assertInvalidFor(paymentConfirmed(t0), listOf(
            DealEvent.VaultFunded(t0.plus(seconds(1))),
            DealEvent.CashCollected(t0, t0),
            DealEvent.PaymentConfirmed,
            DealEvent.ClaimMatured(t0),
            DealEvent.ClaimPaid,
            DealEvent.QuoteExpired(t0.plus(day)),
            // ReleaseObserved and ClaimOpened (valid) covered separately; the
            // paid-and-ghosted reclaim is a valid transition (tested below).
        ))
    }

    private fun claimOpened(from: DealStateMachine, proofAt: Instant = t0) =
        advanced(from, DealEvent.ClaimOpened(proofAt))

    private fun claimable(proofAt: Instant = t0) =
        advanced(claimOpened(cashCollected(t0), proofAt), DealEvent.ClaimMatured(proofAt.plus(maturation)))

    @Test
    fun `invalid matrix - CLAIM_OPENED`() {
        val opened = claimOpened(cashCollected(t0), t0)
        assertInvalidFor(opened, listOf(
            DealEvent.VaultFunded(t0.plus(seconds(1))),
            DealEvent.CashCollected(t0, t0),
            DealEvent.ReclaimTimeoutElapsed(t0.plus(day)),
            DealEvent.ClaimOpened(t0),
            DealEvent.ClaimMatured(t0), // before maturation
            DealEvent.ClaimPaid,
            DealEvent.QuoteExpired(t0.plus(day)),
            // PaymentConfirmed (contest), ReleaseObserved (C′ counter) and
            // ClaimMatured-at-boundary (valid) covered separately.
        ))
    }

    @Test
    fun `invalid matrix - CLAIMABLE`() {
        assertInvalidFor(claimable(t0), listOf(
            DealEvent.VaultFunded(t0.plus(seconds(1))),
            DealEvent.CashCollected(t0, t0),
            DealEvent.ReclaimTimeoutElapsed(t0.plus(day).plus(day)),
            DealEvent.ClaimOpened(t0.plus(maturation)),
            DealEvent.ClaimMatured(t0.plus(maturation)),
            DealEvent.QuoteExpired(t0.plus(day)),
            // PaymentConfirmed (contest), ReleaseObserved (C′ counter) and
            // ClaimPaid (valid) covered separately.
        ))
    }

    @Test
    fun `invalid matrix - all events on terminal states are invalid`() {
        val released = advanced(paymentConfirmed(), DealEvent.ReleaseObserved)
        val reclaimed = advanced(funded(t0), DealEvent.ReclaimTimeoutElapsed(t0.plus(day)))
        val claimed = advanced(claimable(t0), DealEvent.ClaimPaid)
        val allEvents = listOf(
            DealEvent.VaultFunded(t0),
            DealEvent.CashCollected(t0, t0),
            DealEvent.PaymentConfirmed,
            DealEvent.ReleaseObserved,
            DealEvent.ReclaimTimeoutElapsed(t0.plus(day)),
            DealEvent.ClaimOpened(t0),
            DealEvent.ClaimMatured(t0.plus(maturation)),
            DealEvent.ClaimPaid,
            DealEvent.QuoteExpired(t0),
        )
        for (terminal in listOf(released, reclaimed, claimed)) {
            assertInvalidFor(terminal, allEvents)
        }
    }

    // ---------- sequencing (deal-protocol section 1 and 4 guards) ----------

    @Test
    fun `double funding is invalid`() {
        assertTrue(invalid(funded(), DealEvent.VaultFunded(t0.plus(seconds(1)))).isNotBlank())
    }

    @Test
    fun `cash collection before funding is invalid`() {
        assertTrue(invalid(DealStateMachine.initial(), DealEvent.CashCollected(t0, t0)).isNotBlank())
    }

    @Test
    fun `double cash collection is invalid`() {
        assertTrue(invalid(cashCollected(), DealEvent.CashCollected(t0, t0)).isNotBlank())
    }

    @Test
    fun `payment confirmed before cash collection is invalid`() {
        val reason = invalid(funded(), DealEvent.PaymentConfirmed)
        assertTrue(reason.contains("cash"), reason)
    }

    @Test
    fun `claim opened before cash collection is invalid`() {
        assertTrue(invalid(funded(), DealEvent.ClaimOpened(t0)).isNotBlank())
    }

    @Test
    fun `release before cash collection is invalid`() {
        assertTrue(invalid(funded(), DealEvent.ReleaseObserved).isNotBlank())
    }

    // ---------- quote expiry / ghosting ----------

    @Test
    fun `quote expiry aborts without on-chain footprint`() {
        val outcome = DealStateMachine.initial().transition(DealEvent.QuoteExpired(t0))
        assertIs<TransitionOutcome.Aborted>(outcome)
    }

    @Test
    fun `quote expiry after funding is invalid`() {
        assertTrue(invalid(funded(), DealEvent.QuoteExpired(t0.plus(day))).isNotBlank())
    }

    // ---------- reclaim path ----------

    @Test
    fun `reclaim before timeout elapsed is invalid`() {
        val reason = invalid(funded(t0), DealEvent.ReclaimTimeoutElapsed(t0.plus(day).minus(seconds(1))))
        assertTrue(reason.contains("timeout"), reason)
    }

    @Test
    fun `reclaim at exactly the timeout boundary succeeds`() {
        val m = advanced(funded(t0), DealEvent.ReclaimTimeoutElapsed(t0.plus(day)))
        assertEquals(DealState.RECLAIMED, m.state)
    }

    @Test
    fun `reclaim before the funding instant is invalid`() {
        val reason = invalid(funded(t0), DealEvent.ReclaimTimeoutElapsed(t0.minus(seconds(1))))
        assertTrue(reason.contains("timeout"), reason)
    }

    @Test
    fun `user no-show - reclaim from FUNDED`() {
        val m = advanced(funded(t0), DealEvent.ReclaimTimeoutElapsed(t0.plus(day).plus(seconds(3600))))
        assertEquals(DealState.RECLAIMED, m.state)
    }

    @Test
    fun `seller paid and user ghosted - reclaim from PAYMENT_CONFIRMED harms no one`() {
        val m = advanced(paymentConfirmed(t0), DealEvent.ReclaimTimeoutElapsed(t0.plus(day)))
        assertEquals(DealState.RECLAIMED, m.state)
    }

    @Test
    fun `reclaim after cash collected with no payment is invalid`() {
        val reason = invalid(cashCollected(t0), DealEvent.ReclaimTimeoutElapsed(t0.plus(day)))
        assertTrue(reason.contains("claim"), reason)
    }

    @Test
    fun `reclaim with handoff record on-chain is invalid`() {
        val opened = claimOpened(cashCollected(t0), t0.plus(seconds(3600)))
        val reason = invalid(opened, DealEvent.ReclaimTimeoutElapsed(t0.plus(day).plus(day)))
        assertTrue(reason.contains("handoff record"), reason)
    }

    // ---------- claim path ----------

    @Test
    fun `claim opened from PAYMENT_PENDING records the proof timestamp`() {
        val proofAt = t0.plus(seconds(3600))
        val m = claimOpened(cashCollected(t0), proofAt)
        assertEquals(DealState.CLAIM_OPENED, m.state)
        assertEquals(proofAt, m.proofTimestamp)
        assertFalse(m.claimContested)
    }

    @Test
    fun `claim opened from PAYMENT_CONFIRMED is the without-cause scenario`() {
        val m = claimOpened(paymentConfirmed(t0), t0.plus(seconds(600)))
        assertEquals(DealState.CLAIM_OPENED, m.state)
    }

    @Test
    fun `maturation before CLAIM_MATURATION elapsed is invalid`() {
        val opened = claimOpened(cashCollected(t0), t0)
        val reason = invalid(opened, DealEvent.ClaimMatured(t0.plus(maturation).minus(seconds(1))))
        assertTrue(reason.contains("maturation"), reason)
    }

    @Test
    fun `maturation at exactly the boundary succeeds`() {
        val m = claimable(t0)
        assertEquals(DealState.CLAIMABLE, m.state)
    }

    @Test
    fun `claim payout before maturation is invalid`() {
        val opened = claimOpened(cashCollected(t0), t0)
        assertTrue(invalid(opened, DealEvent.ClaimPaid).isNotBlank())
    }

    @Test
    fun `claim payout reaches CLAIMED as terminal`() {
        val m = advanced(claimable(t0), DealEvent.ClaimPaid)
        assertEquals(DealState.CLAIMED, m.state)
        assertTrue(m.state.isTerminal)
    }

    // ---------- oracle-signal contest of an open claim (path C′ counter) ----------

    @Test
    fun `oracle signal during an open claim marks it contested`() {
        val m = advanced(claimOpened(cashCollected(t0), t0), DealEvent.PaymentConfirmed)
        assertEquals(DealState.CLAIM_OPENED, m.state)
        assertTrue(m.claimContested)
    }

    @Test
    fun `oracle signal during CLAIMABLE marks it contested`() {
        val m = advanced(claimable(t0), DealEvent.PaymentConfirmed)
        assertEquals(DealState.CLAIMABLE, m.state)
        assertTrue(m.claimContested)
    }

    @Test
    fun `contest survives maturation`() {
        val contested = advanced(claimOpened(cashCollected(t0), t0), DealEvent.PaymentConfirmed)
        val m = advanced(contested, DealEvent.ClaimMatured(t0.plus(maturation)))
        assertEquals(DealState.CLAIMABLE, m.state)
        assertTrue(m.claimContested)
    }

    @Test
    fun `release-early from CLAIM_OPENED counters the claim`() {
        val m = advanced(claimOpened(cashCollected(t0), t0), DealEvent.ReleaseObserved)
        assertEquals(DealState.RELEASED, m.state)
    }

    @Test
    fun `release from CLAIMABLE counters the claim`() {
        val m = advanced(claimable(t0), DealEvent.ReleaseObserved)
        assertEquals(DealState.RELEASED, m.state)
    }

    @Test
    fun `contested claim resolved by the seller's C-prime spend`() {
        val contested = advanced(claimOpened(cashCollected(t0), t0), DealEvent.PaymentConfirmed)
        val m = advanced(contested, DealEvent.ReleaseObserved)
        assertEquals(DealState.RELEASED, m.state)
    }

    @Test
    fun `contested claim payout remains representable`() {
        // The contract never sees the oracle signal, so a matured path D spend is
        // still possible on-chain; the machine tracks chain truth.
        val contested = advanced(claimable(t0), DealEvent.PaymentConfirmed)
        val m = advanced(contested, DealEvent.ClaimPaid)
        assertEquals(DealState.CLAIMED, m.state)
    }

    // ---------- freshness guards ----------

    @Test
    fun `courier clock skew beyond 10 minutes is rejected at cash collection`() {
        val skew = ProtocolConstants.COURIER_CLOCK_SKEW
        val reason = invalid(
            funded(t0),
            DealEvent.CashCollected(courierTimestamp = t0.minus(skew).minus(seconds(1)), confirmedAt = t0),
        )
        assertTrue(reason.contains("clock"), reason)
    }

    @Test
    fun `clock skew boundary exactly at 10 minutes passes`() {
        val skew = ProtocolConstants.COURIER_CLOCK_SKEW
        val collected = advanced(
            funded(t0),
            DealEvent.CashCollected(courierTimestamp = t0.minus(skew), confirmedAt = t0),
        )
        assertEquals(DealState.PAYMENT_PENDING, collected.state)
    }
}
