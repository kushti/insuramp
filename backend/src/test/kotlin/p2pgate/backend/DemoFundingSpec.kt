package p2pgate.backend

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import p2pgate.backend.api.seedDemoFunding
import p2pgate.backend.util.SigmaTrees
import p2pgate.backend.vault.EmbeddedVaultSigner
import p2pgate.backend.vault.VaultManager
import p2pgate.dealprotocol.DealState

/**
 * Demo-mode funding (`P2P_DEMO_QUOTES=true`): the synthetic boxes
 * [seedDemoFunding] loads into the [EmbeddedVaultSigner] must actually fund a
 * vault — a successful build is prover-verified against the compiled scripts.
 * Regression guard for the "demo mode cannot fund" gap that used to block
 * end-to-end testing of both apps (`specs/README.md` open backlog).
 */
class DemoFundingSpec {

    private fun seededSigner(): EmbeddedVaultSigner {
        val signer = EmbeddedVaultSigner(
            secret = Fx.seller.secret,
            networkType = Fx.networkType,
            changeAddress = Fx.p2pkAddress(Fx.seller.pubKeyCompressed),
        )
        seedDemoFunding(signer, Fx.seller.secret, Fx.networkType, Fx.useTokenIdHex) {}
        return signer
    }

    @Test
    fun `seeded demo boxes are P2PK under the vault secret and carry ERG plus the collateral token`() {
        val signer = seededSigner()
        val funding = signer.fundingInputs(Fx.useTokenIdHex, 1)
        assertEquals(3, funding.size)
        funding.forEach { box ->
            assertEquals(SigmaTrees.p2pkTreeHex(Fx.seller.pubKeyCompressed), box.ergoTreeHex)
            // FUNDED box value + miner fee + minimum change.
            assertTrue(box.value >= 3_000_000L, "box value ${box.value} too small")
            assertEquals(1_000_000_000_000L, box.tokenAmount(Fx.useTokenIdHex))
        }
        assertEquals(2, signer.feeInputs().size)
    }

    @Test
    fun `a seeded signer funds a real vault tx`() {
        val env = TestEnv(vaultSigner = seededSigner())
        val deal = env.quotedDeal()
        val outcome = env.vaultManager.fundDeal(deal.dealId, T0)
        assertInstanceOf(VaultManager.Outcome.Submitted::class.java, outcome)
        assertEquals(DealState.FUNDED, env.store.getDeal(deal.dealId)!!.state)
    }

    @Test
    fun `the empty collateral token id matches boxes seeded with it`() {
        val signer = EmbeddedVaultSigner(
            secret = Fx.seller.secret,
            networkType = Fx.networkType,
            changeAddress = Fx.p2pkAddress(Fx.seller.pubKeyCompressed),
        )
        seedDemoFunding(signer, Fx.seller.secret, Fx.networkType, "") {}
        assertEquals(3, signer.fundingInputs("", 1).size)
    }

    @Test
    fun `a fund consumes the whole pool and reseeding funds the next deal`() {
        val signer = seededSigner()
        val env = TestEnv(vaultSigner = signer)
        env.vaultManager.fundDeal(env.quotedDeal().dealId, T0)

        val second = env.quotedDeal()
        val starved = env.vaultManager.fundDeal(second.dealId, T0)
        assertTrue(
            (starved as VaultManager.Outcome.Rejected).reason.startsWith("no funding inputs"),
            "expected the consumed pool to starve the next fund, got $starved",
        )

        // The dev wiring subscribes this reseed to TxSubmitted(FUND) on the bus.
        seedDemoFunding(signer, Fx.seller.secret, Fx.networkType, Fx.useTokenIdHex) {}
        assertInstanceOf(
            VaultManager.Outcome.Submitted::class.java,
            env.vaultManager.fundDeal(second.dealId, T0),
        )
    }
}
