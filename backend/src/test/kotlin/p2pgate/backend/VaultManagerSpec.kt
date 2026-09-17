package p2pgate.backend

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import p2pgate.backend.aml.AmlDecision
import p2pgate.backend.aml.ConfigRiskScorer
import p2pgate.backend.aml.RiskScorer
import p2pgate.backend.aml.RiskScorerException
import p2pgate.backend.bus.TxKind
import p2pgate.backend.vault.VaultManager
import p2pgate.dealprotocol.DealState
import java.time.Duration

/**
 * The reclaim invariant (spec §1, hard rule) and the four operator txs,
 * exercised against the real `OperatorTxBuilder` + compiled vault scripts
 * (every build is prover-verified — a successful build IS the proof).
 */
class VaultManagerSpec {

    private fun realEnv(riskScorer: RiskScorer = ConfigRiskScorer(scorerId = "test-scorer")) =
        TestEnv(riskScorer = riskScorer, vaultSigner = Fx.RealSigner())

    /** Funds a real vault and installs the funded box into the fake chain. */
    private fun fund(env: TestEnv): p2pgate.backend.store.DealRecord {
        val deal = env.quotedDeal()
        val outcome = env.vaultManager.fundDeal(deal.dealId, T0)
        assertInstanceOf(VaultManager.Outcome.Submitted::class.java, outcome)
        val stored = env.store.getDeal(deal.dealId)!!
        assertEquals(TxKind.FUND, (outcome as VaultManager.Outcome.Submitted).kind)
        assertTrue(stored.vaultBoxId != null)
        assertEquals(DealState.FUNDED, stored.state)
        assertEquals(T0, stored.fundedAt)
        env.chain.boxes[stored.vaultBoxId!!] = Fx.fundedBox(stored, stored.vaultBoxId!!)
        return stored
    }

    @Test
    fun `fund builds a real fund tx and anchors the funded state`() {
        val env = realEnv()
        fund(env)
        assertEquals(1, env.submitter.submitted.size)
    }

    @Test
    fun `fund is halted while infra is paused`() {
        val env = realEnv()
        val deal = env.quotedDeal()
        env.infra.report(p2pgate.backend.infra.InfraSignal.ORACLE_LAG, false, "oracle down")
        val outcome = env.vaultManager.fundDeal(deal.dealId, T0)
        assertEquals("funding halted: infra paused", (outcome as VaultManager.Outcome.Rejected).reason)
        assertTrue(env.submitter.submitted.isEmpty())
    }

    @Test
    fun `fund refuses a deal with an AML reject on file`() {
        val env = realEnv()
        val deal = env.quotedDeal(aml = AmlDecision.REJECT)
        val outcome = env.vaultManager.fundDeal(deal.dealId, T0)
        assertTrue((outcome as VaultManager.Outcome.Rejected).reason.contains("REJECT"))
        assertTrue(env.submitter.submitted.isEmpty())
    }

    @Test
    fun `unreachable scorer means no funding even with an accept on file for another address`() {
        val throwing = object : RiskScorer {
            override val scorerId: String = "flaky"
            override fun score(address: ByteArray, chainId: Int): AmlDecision =
                throw RiskScorerException("down")
        }
        val env = realEnv(riskScorer = throwing)
        val deal = env.quotedDeal()
        // Address swap since the QUOTED check: re-score fires and fails closed.
        env.store.updateDeal(deal.dealId) { it.copy(recipientAddrHex = "ab".repeat(21)) }
        val outcome = env.vaultManager.fundDeal(deal.dealId, T0)
        assertTrue((outcome as VaultManager.Outcome.Rejected).reason.contains("fail-closed"))
        assertTrue(env.submitter.submitted.isEmpty())
    }

    @Test
    fun `reclaim scheduler reclaims a funded deal past the timeout`() {
        val env = realEnv()
        val deal = fund(env)
        env.chain.height = 1000 + p2pgate.contracts.ContractParams.RECLAIM_TIMEOUT_BLOCKS + 1
        val outcomes = env.vaultManager.tickReclaims(T0.plus(Duration.ofHours(25)))
        assertEquals(1, outcomes.size)
        assertInstanceOf(VaultManager.Outcome.Submitted::class.java, outcomes.single())
        assertEquals(TxKind.RECLAIM, (outcomes.single() as VaultManager.Outcome.Submitted).kind)
        assertEquals(DealState.RECLAIMED, env.store.getDeal(deal.dealId)!!.state)
    }

    @Test
    fun `reclaim scheduler refuses payment pending and logs an invariant violation without building a tx`() {
        val env = realEnv()
        val deal = fund(env)
        env.collectCash(deal.dealId)
        val outcomes = env.vaultManager.tickReclaims(T0.plus(Duration.ofHours(25)))
        assertEquals(1, outcomes.size)
        assertInstanceOf(VaultManager.Outcome.Rejected::class.java, outcomes.single())
        assertTrue(
            env.engine.violations.entries().any {
                it.dealId == deal.dealId && it.reason.contains("cash already collected")
            },
        )
        assertEquals(1, env.submitter.submitted.size) // only the fund tx
        assertEquals(DealState.PAYMENT_PENDING, env.store.getDeal(deal.dealId)!!.state)
    }

    @Test
    fun `reclaim scheduler reclaims payment confirmed when the release has not landed`() {
        val env = realEnv()
        val deal = fund(env)
        env.collectCash(deal.dealId)
        env.engine.apply(deal.dealId, p2pgate.dealprotocol.DealEvent.PaymentConfirmed, T0.plusSeconds(600))
        env.chain.height = 1000 + p2pgate.contracts.ContractParams.RECLAIM_TIMEOUT_BLOCKS + 1
        val outcomes = env.vaultManager.tickReclaims(T0.plus(Duration.ofHours(25)))
        assertInstanceOf(VaultManager.Outcome.Submitted::class.java, outcomes.single())
        assertEquals(TxKind.RECLAIM, (outcomes.single() as VaultManager.Outcome.Submitted).kind)
        assertEquals(DealState.RECLAIMED, env.store.getDeal(deal.dealId)!!.state)
    }

    @Test
    fun `manual reclaim before the timeout is refused`() {
        val env = realEnv()
        val deal = fund(env)
        val outcome = env.vaultManager.reclaimDeal(deal.dealId, T0.plus(Duration.ofHours(1)))
        assertTrue((outcome as VaultManager.Outcome.Rejected).reason.contains("not elapsed"))
        assertEquals(DealState.FUNDED, env.store.getDeal(deal.dealId)!!.state)
    }

    @Test
    fun `automatic release builds path c on payment confirmation`() {
        val env = realEnv()
        val deal = fund(env)
        env.collectCash(deal.dealId)
        env.engine.apply(deal.dealId, p2pgate.dealprotocol.DealEvent.PaymentConfirmed, T0.plusSeconds(600))
        env.attestPayment(deal)
        val outcome = env.vaultManager.releaseIfConfirmed(deal.dealId, T0.plusSeconds(700))
        assertInstanceOf(VaultManager.Outcome.Submitted::class.java, outcome)
        assertEquals(TxKind.RELEASE, (outcome as VaultManager.Outcome.Submitted).kind)
        assertEquals(DealState.RELEASED, env.store.getDeal(deal.dealId)!!.state)
    }

    @Test
    fun `release without an attestation on file is refused`() {
        val env = realEnv()
        val deal = fund(env)
        env.collectCash(deal.dealId)
        env.engine.apply(deal.dealId, p2pgate.dealprotocol.DealEvent.PaymentConfirmed, T0.plusSeconds(600))
        val outcome = env.vaultManager.releaseIfConfirmed(deal.dealId, T0.plusSeconds(700))
        assertTrue((outcome as VaultManager.Outcome.Rejected).reason.contains("no attestation"))
        assertEquals(DealState.PAYMENT_CONFIRMED, env.store.getDeal(deal.dealId)!!.state)
    }

    @Test
    fun `contest builds path c prime from the proven box`() {
        val env = realEnv()
        val deal = fund(env)
        env.collectCash(deal.dealId)
        val claimAt = T0.plusSeconds(600)
        env.engine.apply(deal.dealId, p2pgate.dealprotocol.DealEvent.ClaimOpened(claimAt), claimAt)
        val provenId = "bb".repeat(32)
        env.store.updateDeal(deal.dealId) { it.copy(provenBoxId = provenId) }
        env.chain.boxes[provenId] = Fx.provenBox(env.store.getDeal(deal.dealId)!!, boxId = provenId)
        env.attestPayment(env.store.getDeal(deal.dealId)!!)
        val outcome = env.vaultManager.contestDeal(deal.dealId, claimAt.plusSeconds(60))
        assertInstanceOf(VaultManager.Outcome.Submitted::class.java, outcome)
        assertEquals(TxKind.CONTEST, (outcome as VaultManager.Outcome.Submitted).kind)
        assertEquals(DealState.RELEASED, env.store.getDeal(deal.dealId)!!.state)
    }

    @Test
    fun `contest without an attestation is refused`() {
        val env = realEnv()
        val deal = fund(env)
        env.collectCash(deal.dealId)
        val claimAt = T0.plusSeconds(600)
        env.engine.apply(deal.dealId, p2pgate.dealprotocol.DealEvent.ClaimOpened(claimAt), claimAt)
        val outcome = env.vaultManager.contestDeal(deal.dealId, claimAt.plusSeconds(60))
        assertTrue((outcome as VaultManager.Outcome.Rejected).reason.contains("no attestation"))
    }
}
