package p2pgate.backend

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import p2pgate.backend.bus.BackendEvent
import p2pgate.backend.disputes.DisputeInbox
import p2pgate.dealprotocol.DealState
import java.time.Duration
import java.time.Instant

class DisputeInboxSpec {

    /** A funded + handed-off deal with the claim opened and the proven box installed. */
    private fun claimedDeal(env: TestEnv, claimAt: Instant = T0.plusSeconds(600)): p2pgate.backend.store.DealRecord {
        val deal = env.quotedDeal()
        env.forceFund(deal)
        env.collectCash(deal.dealId)
        env.engine.apply(deal.dealId, p2pgate.dealprotocol.DealEvent.ClaimOpened(claimAt), claimAt)
        val provenId = "bb".repeat(32)
        env.store.updateDeal(deal.dealId) { it.copy(provenBoxId = provenId) }
        env.chain.boxes[provenId] = Fx.provenBox(env.store.getDeal(deal.dealId)!!, boxId = provenId)
        return env.store.getDeal(deal.dealId)!!
    }

    @Test
    fun `rows list only open claims sorted by maturation deadline with evidence`() {
        val env = TestEnv()
        val early = claimedDeal(env, T0.plusSeconds(100))
        val late = claimedDeal(env, T0.plusSeconds(500))
        // A non-claim deal must not appear.
        env.forceFund(env.quotedDeal(nonce = ByteArray(16) { (it + 9).toByte() }))
        val rows = env.inbox.rows()
        assertEquals(listOf(early.dealId, late.dealId), rows.map { it.dealId })
        assertTrue(rows.all { it.handoffRecordRef != null })
        assertTrue(rows.none { it.oracleConfirmed })
        assertEquals(T0.plusSeconds(100).plus(Duration.ofHours(12)), rows.first().maturesAt)
    }

    @Test
    fun `row shows the contested flag and the attestation digest once confirmed`() {
        val env = TestEnv()
        val deal = claimedDeal(env)
        env.attestPayment(deal)
        env.engine.apply(deal.dealId, p2pgate.dealprotocol.DealEvent.PaymentConfirmed, T0.plusSeconds(700))
        val row = env.inbox.row(deal.dealId)
        assertTrue(row.contested)
        assertTrue(row.oracleConfirmed)
        assertTrue(row.attestationDigest != null)
    }

    @Test
    fun `contest builds path c prime and closes the claim`() {
        val env = TestEnv(vaultSigner = Fx.RealSigner())
        val deal = claimedDeal(env)
        env.attestPayment(deal)
        val outcome = env.inbox.contest(deal.dealId, T0.plusSeconds(800))
        assertEquals(DisputeInbox.ActionOutcome.Ok, outcome)
        assertEquals(1, env.submitter.submitted.size)
        val stored = env.store.getDeal(deal.dealId)!!
        assertEquals(DealState.RELEASED, stored.state)
        assertEquals("contest", stored.claimAction)
    }

    @Test
    fun `contest is refused without an attestation`() {
        val env = TestEnv(vaultSigner = Fx.RealSigner())
        val deal = claimedDeal(env)
        val outcome = env.inbox.contest(deal.dealId, T0.plusSeconds(800))
        assertTrue((outcome as DisputeInbox.ActionOutcome.Rejected).reason.contains("no attestation"))
        assertTrue(env.store.getDeal(deal.dealId)!!.claimAction == null)
    }

    @Test
    fun `accept records the loss and changes nothing on chain`() {
        val env = TestEnv(vaultSigner = Fx.RealSigner())
        val deal = claimedDeal(env)
        val txsBefore = env.submitter.submitted.size
        assertEquals(DisputeInbox.ActionOutcome.Ok, env.inbox.accept(deal.dealId))
        val stored = env.store.getDeal(deal.dealId)!!
        assertTrue(stored.lossRecorded)
        assertEquals("accept", stored.claimAction)
        assertEquals(txsBefore, env.submitter.submitted.size)
        assertEquals(DealState.CLAIM_OPENED, stored.state)
    }

    @Test
    fun `investigate routes to internal review and changes nothing on chain`() {
        val env = TestEnv(vaultSigner = Fx.RealSigner())
        val deal = claimedDeal(env)
        val txsBefore = env.submitter.submitted.size
        assertEquals(DisputeInbox.ActionOutcome.Ok, env.inbox.investigate(deal.dealId))
        assertEquals("investigate", env.store.getDeal(deal.dealId)!!.claimAction)
        assertEquals(txsBefore, env.submitter.submitted.size)
        assertEquals(DealState.CLAIM_OPENED, env.store.getDeal(deal.dealId)!!.state)
    }

    @Test
    fun `actions are refused on deals without an open claim`() {
        val env = TestEnv()
        val deal = env.quotedDeal()
        assertInstanceOf(DisputeInbox.ActionOutcome.Rejected::class.java, env.inbox.contest(deal.dealId))
        assertInstanceOf(DisputeInbox.ActionOutcome.Rejected::class.java, env.inbox.accept(deal.dealId))
        assertInstanceOf(DisputeInbox.ActionOutcome.Rejected::class.java, env.inbox.investigate(deal.dealId))
    }

    @Test
    fun `escalation hook fires near maturation exactly once`() {
        val calls = mutableListOf<String>()
        val env = TestEnv(webhook = { _, body -> calls += body })
        val deal = claimedDeal(env, T0.plusSeconds(600))
        val maturesAt = T0.plusSeconds(600).plus(Duration.ofHours(12))
        // Far away: silent.
        env.inbox.tick(maturesAt.minus(Duration.ofHours(4)))
        assertTrue(calls.isEmpty())
        // Inside the 2h lead: fires.
        env.inbox.tick(maturesAt.minus(Duration.ofHours(1)))
        assertEquals(1, calls.size)
        assertTrue(calls.single().contains(deal.dealId))
        assertTrue(env.store.getDeal(deal.dealId)!!.escalated)
        // Not twice.
        env.inbox.tick(maturesAt.minus(Duration.ofMinutes(30)))
        assertEquals(1, calls.size)
        // Actioned claims do not escalate.
        val other = claimedDeal(env, T0.plusSeconds(700))
        env.inbox.accept(other.dealId)
        env.inbox.tick(T0.plusSeconds(700).plus(Duration.ofHours(12)))
        assertEquals(1, calls.size)
    }
}
