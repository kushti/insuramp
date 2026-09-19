package p2pgate.e2e

import org.ergoplatform.ErgoBox
import org.ergoplatform.ErgoBoxCandidate
import org.ergoplatform.appkit.ColdErgoClient
import org.ergoplatform.appkit.NetworkType
import org.ergoplatform.appkit.SignedTransaction
import org.ergoplatform.appkit.impl.BlockchainContextImpl
import org.ergoplatform.appkit.impl.UnsignedTransactionImpl
import org.ergoplatform.sdk.ExtendedInputBox
import org.ergoplatform.sdk.JavaHelpers
import org.ergoplatform.sdk.wallet.protocol.context.BlockchainStateContext
import p2pgate.ergo.ChainBox
import p2pgate.ergo.ChainToken
import p2pgate.ergo.ErgoBridge
import scala.Tuple2
import sigma.Header
import sigma.ast.ErgoTree
import sigma.ast.EvaluatedValue
import sigma.ast.SType
import sigma.serialization.ValueSerializer
import java.math.BigInteger

/**
 * Offline transaction assembly for the harness's own setup/payment
 * transactions (mint the dev oracle NFT, mint the collateral token, fund the
 * buyer address) — a port of `:apps:core:ergo`'s internal `TxAssembly` tail
 * (not visible across the module boundary), over the public `ErgoBridge`
 * interop surface. The vault txs themselves never pass through here: they are
 * built by `OperatorTxBuilder`/`ClaimTxBuilder` exactly as in production.
 */
object RawTx {

    /** Rebuilds an [ErgoBox] from a [ChainBox] under the given [tree]. */
    fun toErgoBox(box: ChainBox, tree: ErgoTree): ErgoBox {
        val regs = box.registers.mapIndexedNotNull { i, reg ->
            if (reg == null) null else Tuple2(
                ErgoBridge.regId(i + 4),
                ValueSerializer.deserialize(reg.serialized, 0) as EvaluatedValue<out SType>,
            )
        }
        return ErgoBridge.box(
            box.value,
            tree,
            ErgoBridge.tokens(box.tokens.map { Tuple2(Hex.decode(it.tokenId), it.amount) }),
            ErgoBridge.regs(regs),
            box.transactionId,
            box.index.toShort(),
            box.creationHeight,
        )
    }

    /** A wallet input's tree comes from the explorer-provided hex. */
    fun decodeTree(box: ChainBox): ErgoTree = JavaHelpers.decodeStringToErgoTree(box.ergoTreeHex)

    fun candidate(
        value: Long,
        tree: ErgoTree,
        tokens: List<ChainToken>,
        creationHeight: Int,
        registers: List<Pair<Int, sigma.ast.EvaluatedValue<out sigma.ast.SType>>> = emptyList(),
    ): ErgoBoxCandidate = ErgoBridge.candidate(
        value,
        tree,
        creationHeight,
        if (tokens.isEmpty()) ErgoBridge.emptyTokens() else ErgoBridge.tokens(tokens.map { Tuple2(Hex.decode(it.tokenId), it.amount) }),
        if (registers.isEmpty()) ErgoBridge.regs(emptyList()) else ErgoBridge.regs(
            registers.map { Tuple2(ErgoBridge.regId(it.first), it.second) },
        ),
    )

    /**
     * Assembles and signs an exact-balance transaction: [candidates] plus a
     * change output when the remainder after [minerFeeNanoErg] warrants one.
     * The prover runs offline against the preheader only — mirroring
     * `TxAssembly.assemble` (core's builders prover-verify the same way).
     */
    fun assemble(
        inputs: List<ErgoBox>,
        candidates: List<ErgoBoxCandidate>,
        minerFeeNanoErg: Long,
        minChangeNanoErg: Long,
        currentHeight: Int,
        txTimestampMs: Long,
        changeTree: ErgoTree,
        networkType: NetworkType,
        secrets: List<BigInteger>,
    ): SignedTransaction {
        val totalIn = inputs.sumOf { it.value() }
        val totalOut = candidates.sumOf { it.value() }
        val change = totalIn - totalOut - minerFeeNanoErg
        require(change >= 0) { "insufficient ERG: inputs $totalIn, outputs $totalOut, miner fee $minerFeeNanoErg" }
        require(change == 0L || change >= minChangeNanoErg) {
            "change $change below minimum box value $minChangeNanoErg"
        }

        val outputs = candidates.toMutableList()
        if (change > 0) {
            outputs += ErgoBridge.candidate(
                change,
                changeTree,
                currentHeight,
                ErgoBridge.emptyTokens(),
                ErgoBridge.regs(emptyList()),
            )
        }

        val ext = ErgoBridge.emptyExt()
        val unsignedTx = ErgoBridge.unsignedTxWithExt(inputs, emptyList(), outputs, ext, 0)
        val extendedInputs = inputs.map { ExtendedInputBox(it, ErgoBridge.emptyExt()) }
        val preHeader = offlinePreHeader(currentHeight, txTimestampMs)
        val unsigned = UnsignedTransactionImpl(
            unsignedTx,
            extendedInputs,
            emptyList(),
            org.ergoplatform.appkit.Address.create(changeAddress(changeTree, networkType)).ergoAddress,
            OfflineStateContext(preHeader),
            offlineContext(networkType),
            emptyList(),
        )
        val client = ColdErgoClient(networkType, coldParameters(networkType))
        return client.execute { ctx ->
            val builder = ctx.newProverBuilder()
            secrets.forEach { builder.withDLogSecret(it) }
            builder.build().sign(unsigned)
        }
    }

    private fun changeAddress(changeTree: ErgoTree, networkType: NetworkType): String =
        org.ergoplatform.appkit.Address.fromErgoTree(changeTree, networkType).toString()

    /** Minimal state context: only the preheader is real (offline building). */
    private class OfflineStateContext(private val preHeader: sigma.PreHeader) : BlockchainStateContext() {
        override fun sigmaPreHeader(): sigma.PreHeader = preHeader
        override fun sigmaLastHeaders(): sigma.Coll<Header> =
            sigma.`package`.Colls().fromItems(
                ErgoBridge.indexedSeq<Header>(emptyList()),
                sigma.`package`.HeaderRType(),
            )

        override fun previousStateDigest(): sigma.Coll<Any> = JavaHelpers.collFrom(ByteArray(32))
    }

    /** Stub data source — enough for offline cost accounting, no live node. */
    private class OfflineDataSource(private val networkName: String) : org.ergoplatform.appkit.BlockchainDataSource {
        override fun getParameters(): org.ergoplatform.appkit.BlockchainParameters {
            val nodeInfo = org.ergoplatform.restapi.client.NodeInfo()
            nodeInfo.network = networkName
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

        override fun getLastBlockHeaders(n: Int, wait: Boolean): List<org.ergoplatform.appkit.BlockHeader> = emptyList()
        override fun getBoxById(id: String, includeMempool: Boolean, includeMempoolSpent: Boolean): org.ergoplatform.appkit.InputBox =
            throw UnsupportedOperationException("offline build")
        override fun sendTransaction(tx: SignedTransaction): String = throw UnsupportedOperationException("offline build")
        override fun getUnspentBoxesFor(address: org.ergoplatform.appkit.Address, offset: Int, limit: Int): List<org.ergoplatform.appkit.InputBox> = emptyList()
        override fun getUnconfirmedUnspentBoxesFor(address: org.ergoplatform.appkit.Address, offset: Int, limit: Int): List<org.ergoplatform.appkit.InputBox> = emptyList()
        override fun getUnconfirmedTransactions(offset: Int, limit: Int): List<org.ergoplatform.appkit.Transaction> = emptyList()
    }

    private fun offlineContext(networkType: NetworkType): BlockchainContextImpl =
        BlockchainContextImpl(OfflineDataSource(networkType.verboseName), networkType)

    private fun offlinePreHeader(height: Int, timestampMs: Long): sigma.PreHeader =
        sigmastate.eval.CPreHeader.apply(
            1.toByte(),
            JavaHelpers.collFrom(ByteArray(32)),
            timestampMs,
            0L,
            height,
            sigma.data.CGroupElement.apply(sigma.crypto.CryptoContext.default().generator()),
            JavaHelpers.collFrom(byteArrayOf(1, 1, 1)),
        )

    /** Full-node parameters for the cold client (the 3-arg ctor leaves cost fields null). */
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
