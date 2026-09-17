package p2pgate.ergo

import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.crypto.params.ECDomainParameters
import org.ergoplatform.appkit.ColdErgoClient
import org.ergoplatform.appkit.NetworkType
import org.ergoplatform.appkit.SignedTransaction
import org.ergoplatform.appkit.UnsignedTransaction
import p2pgate.contracts.ConstValue
import p2pgate.contracts.ContractCompiler
import p2pgate.contracts.ContractParams
import p2pgate.dealprotocol.DealTerms
import sigma.ast.ErgoTree
import java.math.BigInteger

/**
 * The oracle-signing seam for the operator backend (`specs/oracle-integration.md`
 * §3.1, §4.1 "Signing oracle"): the vault manager asks for the oracle's current
 * input box and hands over the unsigned release tx; whatever implements this
 * interface co-signs it. The phase-1 dev oracle implements it in-process; the
 * production backend swaps in a remote signer talking to the attestation API
 * (`POST /v1/attestations`) without touching the builders.
 *
 * Extends [DealTxSigner] so an [OperatorTxBuilder] release/contest can use the
 * oracle as the transaction's single signer callback.
 */
interface OracleSigner : DealTxSigner {

    /** The phase-1 oracle NFT id the vaults pin (FUNDED R7). */
    val oracleNftId: ByteArray

    /**
     * The box to include as a FULL INPUT of the release transaction: it must
     * carry [oracleNftId] among its tokens (the vault's in-script check).
     */
    fun oracleInputBox(): ChainBox

    /** Co-signs [tx] (which spends [oracleInputBox]) with the oracle key. */
    override fun sign(tx: UnsignedTransaction): SignedTransaction
}

/**
 * The phase-1 oracle as a dev/test object (`specs/oracle-integration.md` §4 —
 * dev mode): holds the oracle Dlog key and NFT id, compiles `oracle.es`,
 * constructs the current oracle box, and mints [PaymentAttestation]s for deals.
 * No real Tron/Ethereum observation happens here: the CALLER asserts the
 * seller's transfer happened (the e2e harness drives it); the production
 * observers replace this class behind [OracleSigner].
 *
 * ## Which box co-signs a release
 *
 * The vault authenticates the release by checking that a full INPUT carries
 * the oracle NFT (`vault_funded.es` / `vault_payment_proven.es` `oracleOk`),
 * so the release/contest input is the `oracle.es`-governed box itself
 * ([oracleChainBox], exposed to [OracleSigner] as [releaseInputBox]): a joint
 * vault+oracle spend works because `oracle.es` pins its self-reproduction at
 * `OUTPUTS(0)` (NFT id + amount and value preserved, same tree) while the
 * vault pays the seller at `OUTPUTS(1)` on release paths (v2, 2026-09-17 —
 * see `specs/vault-contract.md` §8.4). [OperatorTxBuilder] recreates the input
 * as the output at index 0 under its actual (oracle.es) script, exactly per
 * that condition.
 */
class DevOracle(
    /** The oracle Dlog secret (HSM/encrypted keystore in production). */
    val secret: BigInteger,
    /** The phase-1 oracle NFT id (32 B, minted once by the oracle operator). */
    val oracleNftId: ByteArray = ErgoContracts.DUMMY_ORACLE_NFT_ID.copyOf(),
    /** Network prefix for script compilation and address rendering. */
    val networkPrefix: Byte = ContractParams.NETWORK_PREFIX_MAINNET,
    /** ERG value of the constructed oracle boxes. */
    val boxValueNanoErg: Long = 1_000_000L,
    /** Offline stand-ins — no box ids exist until a tx is broadcast. */
    val boxId: String = "dd".repeat(32),
    val transactionId: String = "de".repeat(32),
) {

    init {
        require(oracleNftId.size == 32) { "oracleNftId must be 32 bytes, got ${oracleNftId.size}" }
        require(boxValueNanoErg > 0) { "box value must be positive" }
    }

    /** 33-byte compressed secp256k1 point of [secret]. */
    val pubKeyCompressed: ByteArray = publicKey(secret)

    val networkType: NetworkType =
        if (networkPrefix == ContractParams.NETWORK_PREFIX_MAINNET) NetworkType.MAINNET else NetworkType.TESTNET

    /** `oracle.es` compiled with this oracle's NFT id and key. */
    val tree: ErgoTree = ContractCompiler.compileResource(
        "oracle.es",
        mapOf(
            "ORACLE_NFT_ID" to ConstValue.Bytes(oracleNftId),
            "ORACLE_KEY" to ConstValue.Bytes(pubKeyCompressed),
        ),
        networkPrefix = networkPrefix,
    )

    /**
     * The current oracle box governed by `oracle.es`: value + the NFT, no
     * registers (oracle.es requires none) — the at-rest box rotations spend
     * and recreate, and the box [OracleSigner] offers as the release/contest
     * input (NFT id + amount and value preserved into OUTPUTS(0) —
     * oracle.es's pinned reproduction, see the class doc).
     */
    fun oracleChainBox(
        boxId: String = this.boxId,
        transactionId: String = this.transactionId,
    ): ChainBox = ChainBox(
        boxId = boxId,
        transactionId = transactionId,
        index = 0,
        value = boxValueNanoErg,
        creationHeight = 0,
        ergoTreeHex = ErgoValues.treeHex(tree),
        address = "",
        tokens = listOf(ChainToken(Base16.encode(oracleNftId), 1L)),
        registers = List(6) { null },
    )

    /**
     * The box the oracle offers as the release/contest input: the
     * `oracle.es`-governed box ([oracleChainBox]) carrying the NFT. Its script
     * validates in the joint vault spend — oracle.es pins its reproduction at
     * `OUTPUTS(0)`, so the vault pays the seller at `OUTPUTS(1)` on release
     * paths (see the class doc). Recreated by [OperatorTxBuilder] at output
     * index 0 per that self-reproduction condition.
     */
    fun releaseInputBox(
        boxId: String = this.boxId,
        transactionId: String = this.transactionId,
    ): ChainBox = oracleChainBox(boxId, transactionId)

    /**
     * A plain oracle-key P2PK box holding only ERG — the release/contest
     * miner-fee input for the dev harness (the oracle's prover must be able to
     * prove every input it signs; in production the operator wallet and the
     * oracle co-sign in stages).
     */
    fun feeInputBox(
        valueNanoErg: Long = 5_000_000L,
        boxId: String = "e1".repeat(32),
        transactionId: String = "e2".repeat(32),
    ): ChainBox = ChainBox(
        boxId = boxId,
        transactionId = transactionId,
        index = 0,
        value = valueNanoErg,
        creationHeight = 0,
        ergoTreeHex = ErgoValues.treeHex(ErgoValues.p2pkTree(pubKeyCompressed)),
        address = "",
        tokens = emptyList(),
        registers = List(6) { null },
    )

    /**
     * Dev-mode attestation (`specs/oracle-integration.md` §2.2): the caller
     * asserts the seller's USDT transfer to the buyer's address happened on the
     * source chain; the funding-set fields are derived from the deal terms.
     */
    fun attest(
        dealTerms: DealTerms,
        recipientAddr: ByteArray,
        srcTxId: ByteArray,
        srcBlockHeight: Long,
        srcBlockTime: Long,
    ): PaymentAttestation = PaymentAttestation.build(
        dealTerms = dealTerms,
        recipientAddr = recipientAddr,
        srcTxId = srcTxId,
        srcBlockHeight = srcBlockHeight,
        srcBlockTime = srcBlockTime,
    )

    /** In-process [OracleSigner] over this oracle's key (the dev seam). */
    fun signer(): OracleSigner = object : OracleSigner {
        override val oracleNftId: ByteArray get() = this@DevOracle.oracleNftId.copyOf()
        override fun oracleInputBox(): ChainBox = releaseInputBox()
        override fun sign(tx: UnsignedTransaction): SignedTransaction =
            ColdErgoClient(networkType, coldParameters(networkType)).execute { ctx ->
                ctx.newProverBuilder()
                    .withDLogSecret(secret)
                    .build()
                    .sign(tx)
            }
    }

    companion object {
        private val spec = CustomNamedCurves.getByName("secp256k1")
        private val params = ECDomainParameters(spec.curve, spec.g, spec.n, spec.h)

        /** Compressed secp256k1 public key of [secret]. */
        fun publicKey(secret: BigInteger): ByteArray =
            params.g.multiply(secret).normalize().getEncoded(true)

        /**
         * Full-node parameters for the cold client (the 3-arg ColdErgoClient
         * ctor leaves cost fields null, which the prover's interpreter
         * dereferences). Mainnet reference values, block version 1 — same
         * block as `ErgoTestFixtures.coldParameters` (test tree).
         */
        fun coldParameters(networkType: NetworkType): org.ergoplatform.appkit.BlockchainParameters {
            val nodeInfo = org.ergoplatform.restapi.client.NodeInfo()
            nodeInfo.network = networkType.verboseName
            val params = org.ergoplatform.restapi.client.Parameters()
            params.storageFeeFactor(1_250_000)
            params.minValuePerByte(360)
            params.maxBlockSize(1_048_576)
            params.maxBlockCost(10_000_000)
            params.blockVersion(1)
            params.tokenAccessCost(100)
            params.inputCost(2_000)
            params.dataInputCost(100)
            params.outputCost(100)
            nodeInfo.parameters(params)
            return org.ergoplatform.appkit.impl.NodeInfoParameters(nodeInfo)
        }
    }
}
