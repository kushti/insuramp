package p2pgate.ergo

import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.crypto.params.ECDomainParameters
import org.ergoplatform.appkit.NetworkType
import p2pgate.contracts.ConstValue
import p2pgate.contracts.ContractCompiler
import p2pgate.contracts.ContractParams
import p2pgate.dealprotocol.DealTerms
import sigma.ast.ErgoTree
import java.math.BigInteger

/**
 * The phase-1 oracle as a dev/test object (`specs/oracle-integration.md` §4 —
 * dev mode): holds the oracle Dlog key and NFT id, compiles `oracle.es`,
 * constructs oracle boxes, and posts deal attestations. No real Tron/Ethereum
 * observation happens here: the CALLER asserts the seller's transfer happened
 * and screened non-tainted (the e2e harness drives it); the production
 * observers replace this class behind the backend's oracle client seam.
 *
 * ## How the attestation reaches the release
 *
 * The vault contracts read the release attestation from the oracle box's R4
 * via `CONTEXT.dataInputs(0)` — the oracle singleton box is a DATA INPUT of
 * the release/contest tx, and a data input's script never executes, so the
 * oracle does NOT co-sign releases. The oracle's on-chain involvement is
 * posting the attestation: it spends its singleton box (governed by
 * `oracle.es`, which pins the NFT + value reproduction at `OUTPUTS(0)`; its
 * registers are unconstrained) and recreates it with R4 = the 32-byte dealId
 * — the single per-deal signal "this deal's USDT transfer seller→buyer is
 * done and non-tainted" (specs/oracle-integration.md §2). [attestationBox]
 * models that posted box; [oracleChainBox] models the at-rest box (no
 * registers) the rotation spends.
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
     * The at-rest oracle box governed by `oracle.es`: value + the NFT, no
     * registers (oracle.es requires none). The oracle's attestation-posting
     * rotation spends this box and recreates it with R4 = the payload
     * ([attestationBox] models the recreation).
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
     * The oracle box as the release/contest tx sees it: the `oracle.es`-governed
     * singleton carrying the NFT and R4 = the 32-byte dealId (the result of the
     * oracle's attestation-posting rotation). Attached as a DATA INPUT — the
     * script never executes, so no oracle signature is needed.
     */
    fun attestationBox(
        dealId: ByteArray,
        boxId: String = this.boxId,
        transactionId: String = this.transactionId,
    ): ChainBox {
        require(dealId.size == 32) { "dealId must be 32 bytes, got ${dealId.size}" }
        return ChainBox(
            boxId = boxId,
            transactionId = transactionId,
            index = 0,
            value = boxValueNanoErg,
            creationHeight = 0,
            ergoTreeHex = ErgoValues.treeHex(tree),
            address = "",
            tokens = listOf(ChainToken(Base16.encode(oracleNftId), 1L)),
            registers = listOf(ChainRegister.CollBytes(dealId), null, null, null, null, null),
        )
    }

    /**
     * Dev-mode attestation (`specs/oracle-integration.md` §2): the caller
     * asserts the seller's USDT transfer to the buyer's address happened on the
     * source chain and screened non-tainted — both preconditions are off-chain.
     * The attestation itself is just the deal's id: the single per-deal signal
     * the release paths check.
     */
    fun attest(dealTerms: DealTerms): ByteArray = dealTerms.dealId

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
