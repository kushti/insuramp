package p2pgate.backend

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import p2pgate.backend.api.TokenService

class AuthSpec {

    private fun service() = TokenService()

    @Test
    fun `minted deal token verifies only for its deal`() {
        val tokens = service()
        val token = tokens.mint(TokenService.Scope.DealToken("deal-a"))
        assertTrue(tokens.verifyDeal(token, "deal-a"))
        assertFalse(tokens.verifyDeal(token, "deal-b"))
        assertFalse(tokens.verifyDeal(null, "deal-a"))
        assertFalse(tokens.verifyDeal("deadbeef", "deal-a"))
    }

    @Test
    fun `courier tokens are per deal and do not satisfy the deal scope`() {
        val tokens = service()
        val courier = tokens.mint(TokenService.Scope.CourierToken("deal-a", "courier-7"))
        assertTrue(tokens.verifyCourier(courier, "deal-a", "courier-7"))
        assertFalse(tokens.verifyCourier(courier, "deal-a", "courier-8"))
        assertFalse(tokens.verifyCourier(courier, "deal-b", "courier-7"))
        // Scope isolation: a courier token is not a user token.
        assertFalse(tokens.verifyDeal(courier, "deal-a"))
    }

    @Test
    fun `operator scope is separate`() {
        val tokens = service()
        val op = tokens.mint(TokenService.Scope.OperatorToken)
        assertTrue(tokens.verifyOperator(op))
        assertFalse(tokens.verifyOperator(tokens.mint(TokenService.Scope.DealToken("d"))))
        assertFalse(tokens.verifyOperator(null))
    }

    @Test
    fun `deal tokens die at close`() {
        val env = TestEnv()
        val deal = env.quotedDeal()
        val token = env.tokens.mint(TokenService.Scope.DealToken(deal.dealId))
        val courierToken = env.tokens.mint(TokenService.Scope.CourierToken(deal.dealId, deal.courierId))
        assertTrue(env.tokens.verifyDeal(token, deal.dealId))
        // Drive the deal to a terminal state through the engine (timeout reclaim).
        env.forceFund(deal)
        env.engine.apply(
            deal.dealId,
            p2pgate.dealprotocol.DealEvent.ReclaimTimeoutElapsed(T0.plusSeconds(25 * 3600)),
            T0.plusSeconds(25 * 3600),
        )
        assertEquals(p2pgate.dealprotocol.DealState.RECLAIMED, env.store.getDeal(deal.dealId)!!.state)
        assertFalse(env.tokens.verifyDeal(token, deal.dealId))
        assertFalse(env.tokens.verifyCourier(courierToken, deal.dealId, deal.courierId))
    }

    @Test
    fun `revoking one deal leaves other deals tokens intact`() {
        val tokens = service()
        val a = tokens.mint(TokenService.Scope.DealToken("deal-a"))
        val b = tokens.mint(TokenService.Scope.DealToken("deal-b"))
        tokens.revokeDeal("deal-a")
        assertFalse(tokens.verifyDeal(a, "deal-a"))
        assertTrue(tokens.verifyDeal(b, "deal-b"))
    }

    @Test
    fun `token hashes are stored not raw tokens`() {
        val tokens = service()
        val raw = tokens.mint(TokenService.Scope.OperatorToken)
        // A single-character corruption must not verify (hash equality).
        val corrupted = if (raw.last() == '0') raw.dropLast(1) + "1" else raw.dropLast(1) + "0"
        assertFalse(tokens.verifyOperator(corrupted))
    }
}
