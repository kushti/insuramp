package p2pgate.ergo

import org.ergoplatform.appkit.Address
import org.ergoplatform.appkit.NetworkType
import p2pgate.contracts.ConstValue
import p2pgate.contracts.ContractCompiler
import p2pgate.contracts.ContractParams
import sigma.ast.ErgoTree

/**
 * Compiled v2 vault contracts for chain interaction, `specs/vault-contract.md`
 * §3/§4 (v2, §8.4): `vault_funded.es` (FUNDED box) and `vault_payment_proven.es`
 * (PAYMENT_PROVEN box), compiled from the same `.es` resources the contracts
 * suite tests — this module never re-implements contract logic.
 *
 * Constants mirror `VaultFixture` (the §7 matrix fixture): the PAYMENT_PROVEN
 * tree is compiled first (the FUNDED tree embeds its proposition bytes for the
 * path-B output check), `CLAIM_MATURATION_BLOCKS` is a hardcoded literal in the
 * `.es` (360, ~12h — since 2026-10-03 nothing varies it between compiles), and
 * there is no freshness substitution (the in-script window was removed
 * 2026-10-04).
 * `ORACLE_NFT_ID` is a deployment descriptor, not compiled into either tree
 * (since 2026-10-03 both boxes pin it per-box: FUNDED R7, PROVEN R9): the
 * default is the fixed dummy the fixture uses (see [DUMMY_ORACLE_NFT_ID]), and
 * it is injectable — an operator deploys from its own parameter set.
 *
 * The compiled trees are network-agnostic ErgoTrees; the network prefix only
 * affects the rendered P2S addresses ([fundedAddress]/[provenAddress]).
 */
object ErgoContracts {

    /** Fixed dummy oracle NFT id, mirroring `VaultFixture.oracleNftId`. */
    val DUMMY_ORACLE_NFT_ID: ByteArray = ByteArray(32) { (it * 5 + 1).toByte() }

    /**
     * One compiled parameter set: the two vault trees plus the derived
     * proposition hex strings (for classifying `ChainBox`es) and P2S addresses.
     */
    class VaultTrees(
        val networkPrefix: Byte,
        val fundedTree: ErgoTree,
        val provenTree: ErgoTree,
        /** The phase-1 oracle NFT id — per-box pin (FUNDED R7, copied to the PROVEN box's R9 at claim-open), not compiled into either tree. */
        val oracleNftId: ByteArray,
    ) {
        val networkType: NetworkType =
            if (networkPrefix == ContractParams.NETWORK_PREFIX_MAINNET) NetworkType.MAINNET else NetworkType.TESTNET

        /** Base16 proposition bytes of the FUNDED contract (box `ergoTree` equality). */
        val fundedPropositionHex: String = ErgoValues.treeHex(fundedTree)

        /** Base16 proposition bytes of the PAYMENT_PROVEN contract. */
        val provenPropositionHex: String = ErgoValues.treeHex(provenTree)

        /** P2S address of the FUNDED contract. */
        val fundedAddress: Address = Address.fromErgoTree(fundedTree, networkType)

        /** P2S address of the PAYMENT_PROVEN contract. */
        val provenAddress: Address = Address.fromErgoTree(provenTree, networkType)
    }

    /**
     * Compiles the two vault trees. [oracleNftId] is the phase-1 oracle's NFT —
     * a **deployment descriptor**, not a compile-time constant (since
     * 2026-10-03 both boxes read it per-box: FUNDED R7, PROVEN R9): it is what
     * [OperatorTxBuilder.buildFund] writes into the FUNDED box's R7 and what
     * [VaultBoxTracker] recognizes the oracle's attestation box by.
     *
     * The trees take no timing parameters anymore: the claim maturation is a
     * hardcoded literal in `vault_payment_proven.es` (2026-10-03), and the
     * handoff-record freshness window was removed from the contract entirely
     * (2026-10-04) — with it went `compileFast`, whose 10-minute freshness
     * override was the last remaining fast-compile parameter. The e2e gate's
     * only shortened timing is the funding-time reclaim timeout
     * (`E2eConfig.reclaimTimeoutBlocks`), which lives in the box's R8, not in
     * the scripts.
     */
    fun compile(
        oracleNftId: ByteArray = DUMMY_ORACLE_NFT_ID,
        networkPrefix: Byte = ContractParams.NETWORK_PREFIX_MAINNET,
    ): VaultTrees {
        require(oracleNftId.size == 32) { "oracleNftId must be 32 bytes, got ${oracleNftId.size}" }

        val provenTree = ContractCompiler.compileResource(
            "vault_payment_proven.es",
            emptyMap(),
            networkPrefix = networkPrefix,
        )
        val fundedTree = ContractCompiler.compileResource(
            "vault_funded.es",
            mapOf(
                "PAYMENT_PROVEN_SCRIPT" to ConstValue.Bytes(provenTree.bytes()),
            ),
            networkPrefix = networkPrefix,
        )
        return VaultTrees(networkPrefix, fundedTree, provenTree, oracleNftId.copyOf())
    }

    /** Default mainnet parameter set (dummy oracle NFT). */
    val mainnet: VaultTrees by lazy { compile() }

    /** Default testnet parameter set (same dummies, testnet address rendering). */
    val testnet: VaultTrees by lazy { compile(networkPrefix = ContractParams.NETWORK_PREFIX_TESTNET) }
}
