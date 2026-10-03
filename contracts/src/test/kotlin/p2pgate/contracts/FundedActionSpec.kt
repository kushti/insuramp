package p2pgate.contracts

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drift guard for the FUNDED box's action discriminator.
 *
 * `vault_funded.es` hardcodes its path codes as bare literals (`action == 0`, `== 1`,
 * `== 2`) rather than taking them through the `%%...%%` substitution mechanism, because
 * they are structural rather than deployment parameters — nothing varies them between
 * compiles. `vault_payment_proven.es` followed on 2026-10-03 (`action == 0` payout,
 * `== 1` contest) — the tests below guard both scripts, plus the hardcoded `360L`
 * maturation literal.
 *
 * The cost of hardcoding is that `ContractParams.ACTION_*` (which the tx builders put on
 * the wire) could drift from the source, and a drifted code does not fail loudly: a
 * reclaim built with the wrong byte lands on `sigmaProp(false)` and the vault simply
 * cannot be spent. So this spec reads the `.es` resource and asserts the literals match.
 *
 * `VaultContractSpec` proves the codes *behave* correctly (tests 53–56); this proves they
 * *agree*.
 */
class FundedActionSpec {

    @Test
    fun `the action codes in vault_funded match ContractParams`() {
        val source = ContractCompiler.loadSource("vault_funded.es")

        // The three dispatch comparisons, in source order. Matched on the whole
        // expression so a stray `action ==` elsewhere cannot be mistaken for one.
        val literals = Regex("""action == (\d+)""").findAll(source)
            .map { it.groupValues[1].toInt() }
            .toList()

        assertEquals(
            listOf(ContractParams.ACTION_CLAIM, ContractParams.ACTION_RECLAIM, ContractParams.ACTION_RELEASE),
            literals,
            "vault_funded.es dispatches on $literals, but ContractParams.ACTION_* says " +
                "[${ContractParams.ACTION_CLAIM}, ${ContractParams.ACTION_RECLAIM}, " +
                "${ContractParams.ACTION_RELEASE}] — the tx builders would put the wrong byte on the wire",
        )
    }

    @Test
    fun `the action var is read from context extension var 0 and is mandatory`() {
        val source = ContractCompiler.loadSource("vault_funded.es")
        // `.get`, not `.getOrElse` — a spend that declines to name a path must be
        // rejected outright rather than defaulting to some branch (test 53).
        assertTrue(
            source.contains("val action = getVar[Byte](${ContractParams.ACTION_VAR_INDEX}).get"),
            "the action byte must be read with `.get` at var ${ContractParams.ACTION_VAR_INDEX}",
        )
    }

    @Test
    fun `an unrecognized action code is rejected rather than falling through`() {
        val source = ContractCompiler.loadSource("vault_funded.es")
        // The fail-closed tail. Guards the 2026-10-04 regression risk: before the
        // explicit action, release was the residual branch, so any unmatched shape
        // was *attempted* as a release.
        assertTrue(
            source.contains("sigmaProp(false)"),
            "vault_funded.es must reject an unrecognized action code explicitly",
        )
    }

    @Test
    fun `the action codes in vault_payment_proven match ContractParams`() {
        val source = ContractCompiler.loadSource("vault_payment_proven.es")
        val literals = Regex("""action == (\d+)""").findAll(source)
            .map { it.groupValues[1].toInt() }
            .toList()

        assertEquals(
            listOf(ContractParams.ACTION_CLAIM_PAYOUT, ContractParams.ACTION_CONTEST),
            literals,
            "vault_payment_proven.es dispatches on $literals, but ContractParams says " +
                "[${ContractParams.ACTION_CLAIM_PAYOUT}, ${ContractParams.ACTION_CONTEST}] — " +
                "the tx builders would put the wrong byte on the wire",
        )
    }

    @Test
    fun `the proven action var is read from context extension var 0 and is mandatory`() {
        val source = ContractCompiler.loadSource("vault_payment_proven.es")
        assertTrue(
            source.contains("val action = getVar[Byte](${ContractParams.ACTION_VAR_INDEX}).get"),
            "the proven box's action byte must be read with `.get` at var ${ContractParams.ACTION_VAR_INDEX}",
        )
    }

    @Test
    fun `the hardcoded claim maturation in vault_payment_proven matches ContractParams`() {
        val source = ContractCompiler.loadSource("vault_payment_proven.es")
        // Same drift risk as the action codes: the tracker and the tx builders
        // read ContractParams.CLAIM_MATURATION_BLOCKS, so a drifted literal
        // fails quietly — a payout pre-checked as mature that the chain rejects.
        assertTrue(
            source.contains("claimOpenedAtHeight + ${ContractParams.CLAIM_MATURATION_BLOCKS}L"),
            "vault_payment_proven.es must hardcode the maturation as " +
                "${ContractParams.CLAIM_MATURATION_BLOCKS}L to match ContractParams.CLAIM_MATURATION_BLOCKS",
        )
    }
}