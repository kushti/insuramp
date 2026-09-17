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
 * path-B output check), `CLAIM_MATURATION_BLOCKS` and `FEE_BPS` come from
 * [ContractParams], and `HANDOFF_RECORD_MAX_AGE_MS` is injected as a raw Long
 * literal. `TREASURY_SCRIPT_HASH` / `ORACLE_NFT_ID` are deployment parameters:
 * the defaults are the fixed dummies the fixture uses (see [DUMMY_ORACLE_NFT_ID]),
 * and both are injectable — an operator deploys from its own parameter set,
 * and `treasuryScriptHash` must equal `blake2b256(treasuryTree.bytes())` of the
 * tree the operator pays fees to (§6).
 *
 * The compiled trees are network-agnostic ErgoTrees; the network prefix only
 * affects the rendered P2S addresses ([fundedAddress]/[provenAddress]).
 */
object ErgoContracts {

    /** Fixed dummy oracle NFT id, mirroring `VaultFixture.oracleNftId`. */
    val DUMMY_ORACLE_NFT_ID: ByteArray = ByteArray(32) { (it * 5 + 1).toByte() }

    /** Fixed dummy treasury script hash (compile-time pin, §6); governance rotates it. */
    val DUMMY_TREASURY_SCRIPT_HASH: ByteArray = ByteArray(32) { (it * 3 + 7).toByte() }

    /**
     * One compiled parameter set: the two vault trees plus the derived
     * proposition hex strings (for classifying `ChainBox`es) and P2S addresses.
     */
    class VaultTrees(
        val networkPrefix: Byte,
        val fundedTree: ErgoTree,
        val provenTree: ErgoTree,
        /** The treasury script hash the trees were compiled with (§6 pin). */
        val treasuryScriptHash: ByteArray,
        /** The phase-1 oracle NFT id (R7 pin of the FUNDED tree, compile-time pin of the PROVEN tree). */
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
     * Compiles the two vault trees. [treasuryScriptHash] is `blake2b256` of the
     * treasury proposition bytes (§6); [oracleNftId] the phase-1 oracle's NFT
     * (compile-time pin of the PAYMENT_PROVEN tree, §8.3 item 3).
     * [claimMaturationBlocks]/[handoffRecordMaxAgeMs] default to the canonical
     * constants and are injectable for fast compiles (see [compileFast]).
     */
    fun compile(
        oracleNftId: ByteArray = DUMMY_ORACLE_NFT_ID,
        treasuryScriptHash: ByteArray = DUMMY_TREASURY_SCRIPT_HASH,
        networkPrefix: Byte = ContractParams.NETWORK_PREFIX_MAINNET,
        claimMaturationBlocks: Int = ContractParams.CLAIM_MATURATION_BLOCKS,
        handoffRecordMaxAgeMs: Long = ContractParams.HANDOFF_RECORD_MAX_AGE_MS,
    ): VaultTrees {
        require(oracleNftId.size == 32) { "oracleNftId must be 32 bytes, got ${oracleNftId.size}" }
        require(treasuryScriptHash.size == 32) { "treasuryScriptHash must be 32 bytes, got ${treasuryScriptHash.size}" }

        val provenTree = ContractCompiler.compileResource(
            "vault_payment_proven.es",
            mapOf(
                "TREASURY_SCRIPT_HASH" to ConstValue.Bytes(treasuryScriptHash),
                "ORACLE_NFT_ID" to ConstValue.Bytes(oracleNftId),
                "HANDOFF_RECORD_MAX_AGE_MS" to ConstValue.Raw("${handoffRecordMaxAgeMs}L"),
                "CLAIM_MATURATION_BLOCKS" to ConstValue.IntNum(claimMaturationBlocks),
                "FEE_BPS" to ConstValue.IntNum(ContractParams.PROTOCOL_FEE_BPS),
            ),
            networkPrefix = networkPrefix,
        )
        val fundedTree = ContractCompiler.compileResource(
            "vault_funded.es",
            mapOf(
                "TREASURY_SCRIPT_HASH" to ConstValue.Bytes(treasuryScriptHash),
                "PAYMENT_PROVEN_SCRIPT" to ConstValue.Bytes(provenTree.bytes()),
                "HANDOFF_RECORD_MAX_AGE_MS" to ConstValue.Raw("${handoffRecordMaxAgeMs}L"),
                "FEE_BPS" to ConstValue.IntNum(ContractParams.PROTOCOL_FEE_BPS),
            ),
            networkPrefix = networkPrefix,
        )
        return VaultTrees(networkPrefix, fundedTree, provenTree, treasuryScriptHash, oracleNftId.copyOf())
    }

    /**
     * Small constants for the e2e gate: a full fund → claim → payout
     * cycle on a low-height chain runs in minutes, not 12 hours. The
     * compiled-in constants ([CLAIM_MATURATION_BLOCKS], [HANDOFF_RECORD_MAX_AGE_MS])
     * feed [compileFast]; [RECLAIM_TIMEOUT_BLOCKS] is a funding-time parameter
     * (the FUNDED box's R8 timeout height), not a compiled constant — the
     * e2e harness passes `currentHeight + RECLAIM_TIMEOUT_BLOCKS` to
     * [OperatorTxBuilder.buildFund]. ClaimTxBuilder needs the two compiled
     * values as its pre-check overrides so its build-time checks match the
     * fast trees.
     */
    object Fast {
        /** Fast reclaim timeout: funding-time `timeoutHeight` offset in blocks. */
        const val RECLAIM_TIMEOUT_BLOCKS: Int = 6

        /** Compiled into the fast trees: maturation 3 blocks (~6 min). */
        const val CLAIM_MATURATION_BLOCKS: Int = 3

        /** Compiled into the fast trees: handoff-record freshness 10 min. */
        const val HANDOFF_RECORD_MAX_AGE_MS: Long = 600_000L
    }

    /**
     * Compiles the vault trees with [Fast] constants (mainnet prefix by
     * default since 2026-09-17; testnet stays selectable via [networkPrefix])
     * — the bundle shape is identical to [compile], so every builder
     * works unchanged given matching pre-check overrides.
     */
    fun compileFast(
        oracleNftId: ByteArray = DUMMY_ORACLE_NFT_ID,
        treasuryScriptHash: ByteArray = DUMMY_TREASURY_SCRIPT_HASH,
        networkPrefix: Byte = ContractParams.NETWORK_PREFIX_MAINNET,
    ): VaultTrees = compile(
        oracleNftId = oracleNftId,
        treasuryScriptHash = treasuryScriptHash,
        networkPrefix = networkPrefix,
        claimMaturationBlocks = Fast.CLAIM_MATURATION_BLOCKS,
        handoffRecordMaxAgeMs = Fast.HANDOFF_RECORD_MAX_AGE_MS,
    )

    /** Default mainnet parameter set (dummy oracle NFT / treasury hash). */
    val mainnet: VaultTrees by lazy { compile() }

    /** Default testnet parameter set (same dummies, testnet address rendering). */
    val testnet: VaultTrees by lazy { compile(networkPrefix = ContractParams.NETWORK_PREFIX_TESTNET) }
}
