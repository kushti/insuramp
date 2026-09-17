package p2pgate.contracts

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Oracle box progression (contracts/src/main/ergoscript/oracle.es): every spend must
 * carry proveDlog(oracleKey) and preserve the NFT (id + amount) and value into
 * OUTPUTS(0) — a FIXED position (v2, 2026-09-17), so the vault contracts can pin their
 * seller payout at OUTPUTS(1) on joint release spends (specs/vault-contract.md §8.4).
 * The oracle box is SELF in all tests here.
 */
class OracleContractSpec {

    @Test
    fun `1 rotation by oracle key preserving NFT and value passes`() {
        val fx = VaultFixture()
        assertTrue(
            fx.verifySpend(
                fx.oracleTree, fx.oracleBox,
                inputs = listOf(fx.oracleBox),
                outputs = listOf(fx.oracleOut()),
                height = fx.creationHeight,
                secrets = listOf(fx.oracleKey),
            ),
        )
    }

    @Test
    fun `2 rotation to a box with increased value passes`() {
        val fx = VaultFixture()
        assertTrue(
            fx.verifySpend(
                fx.oracleTree, fx.oracleBox,
                inputs = listOf(fx.oracleBox),
                outputs = listOf(fx.oracleOut(value = fx.boxValue + 100_000L)),
                height = fx.creationHeight,
                secrets = listOf(fx.oracleKey),
            ),
        )
    }

    @Test
    fun `3 spend by a key other than oracleKey fails`() {
        val fx = VaultFixture()
        assertFalse(
            fx.verifySpend(
                fx.oracleTree, fx.oracleBox,
                inputs = listOf(fx.oracleBox),
                outputs = listOf(fx.oracleOut()),
                height = fx.creationHeight,
                secrets = listOf(fx.sellerKey),
            ),
        )
    }

    @Test
    fun `4 spend without oracle signature fails`() {
        val fx = VaultFixture()
        assertFalse(
            fx.verifySpend(
                fx.oracleTree, fx.oracleBox,
                inputs = listOf(fx.oracleBox),
                outputs = listOf(fx.oracleOut()),
                height = fx.creationHeight,
            ),
        )
    }

    @Test
    fun `5 rotation output missing the NFT fails`() {
        val fx = VaultFixture()
        assertFalse(
            fx.verifySpend(
                fx.oracleTree, fx.oracleBox,
                inputs = listOf(fx.oracleBox),
                outputs = listOf(
                    SigmaBridge.candidate(
                        fx.boxValue, fx.oracleTree, fx.creationHeight,
                        SigmaBridge.emptyTokens(), SigmaBridge.emptyRegs(),
                    ),
                ),
                height = fx.creationHeight,
                secrets = listOf(fx.oracleKey),
            ),
        )
    }

    @Test
    fun `6 rotation output carrying a different token id fails`() {
        val fx = VaultFixture()
        assertFalse(
            fx.verifySpend(
                fx.oracleTree, fx.oracleBox,
                inputs = listOf(fx.oracleBox),
                outputs = listOf(
                    SigmaBridge.candidate(
                        fx.boxValue, fx.oracleTree, fx.creationHeight,
                        SigmaBridge.tokens(listOf(scala.Tuple2.apply(fx.wrongNftId, 1L))),
                        SigmaBridge.emptyRegs(),
                    ),
                ),
                height = fx.creationHeight,
                secrets = listOf(fx.oracleKey),
            ),
        )
    }

    @Test
    fun `7 rotation with the NFT reproduction at OUTPUTS 1 fails`() {
        // The joint-spend shape pins the vault's seller payout at OUTPUTS(0) under the
        // old convention; v2 fixes the reproduction at OUTPUTS(0) instead (the vault
        // payout moved to OUTPUTS(1)), so this layout no longer verifies.
        val fx = VaultFixture()
        assertFalse(
            fx.verifySpend(
                fx.oracleTree, fx.oracleBox,
                inputs = listOf(fx.oracleBox),
                outputs = listOf(
                    SigmaBridge.candidate(
                        fx.boxValue, fx.sellerTree, fx.creationHeight,
                        SigmaBridge.emptyTokens(), SigmaBridge.emptyRegs(),
                    ),
                    fx.oracleOut(),
                ),
                height = fx.creationHeight,
                secrets = listOf(fx.oracleKey),
            ),
        )
    }

    @Test
    fun `8 rotation draining value below SELF value fails`() {
        val fx = VaultFixture()
        assertFalse(
            fx.verifySpend(
                fx.oracleTree, fx.oracleBox,
                inputs = listOf(fx.oracleBox),
                outputs = listOf(fx.oracleOut(value = fx.boxValue - 1)),
                height = fx.creationHeight,
                secrets = listOf(fx.oracleKey),
            ),
        )
    }
}
