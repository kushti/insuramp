package p2pgate.ergo

import org.ergoplatform.ErgoBox
import org.ergoplatform.ErgoBoxCandidate
import org.ergoplatform.UnsignedErgoLikeTransaction
import org.ergoplatform.appkit.Address
import org.ergoplatform.appkit.NetworkType
import org.ergoplatform.appkit.SignedTransaction
import org.ergoplatform.appkit.UnsignedTransaction
import org.ergoplatform.appkit.impl.BlockchainContextImpl
import org.ergoplatform.appkit.impl.UnsignedTransactionImpl
import org.ergoplatform.sdk.ExtendedInputBox
import org.ergoplatform.sdk.JavaHelpers
import org.ergoplatform.sdk.wallet.protocol.context.BlockchainStateContext
import scala.Tuple2
import sigma.Header
import sigma.ast.ErgoTree
import sigma.ast.EvaluatedValue
import sigma.ast.SType
import sigma.serialization.ValueSerializer

/**
 * Shared offline transaction assembly for the vault builders
 * ([ClaimTxBuilder], [OperatorTxBuilder]): exact-balance outputs (candidates +
 * optional change), the context extension on the vault input, an offline state
 * context carrying only the preheader, and the signer callback. Extracted from
 * `ClaimTxBuilder` (M2) so M3's operator-side builders reuse the exact same
 * machinery instead of copying it.
 *
 * Transactions are built offline — the unsigned transaction is assembled from
 * sigma `ErgoBox`es directly (appkit 6.0.1's `UnsignedTransactionBuilder`
 * requires a live context height, which a cold/offline context cannot provide),
 * then wrapped in appkit's [UnsignedTransaction] for signing and submission.
 */
internal object TxAssembly {

    /**
     * Assembles the unsigned transaction: exact-balance outputs (candidates +
     * change), the context extension on the vault input, an offline state
     * context carrying only the preheader, and finally the [signer] call.
     * [changeTokens] rides the change output (fund transactions with surplus
     * collateral); a change output is then mandatory.
     */
    fun assemble(
        inputs: List<ErgoBox>,
        contextVars: Map<Int, EvaluatedValue<out SType>>,
        contextVarInputIndex: Int,
        candidates: List<ErgoBoxCandidate>,
        changeTokens: List<ChainToken> = emptyList(),
        minerFeeNanoErg: Long,
        minChangeNanoErg: Long,
        currentHeight: Int,
        txTimestampMs: Long?,
        changeAddress: String,
        networkType: NetworkType,
        signer: DealTxSigner,
    ): SignedTransaction {
        val changeAddressErgo = Address.create(changeAddress).ergoAddress
        val changeTree = changeAddressErgo.script()

        val totalIn = inputs.sumOf { it.value() }
        val totalOut = candidates.sumOf { it.value() }
        val change = totalIn - totalOut - minerFeeNanoErg
        require(change >= 0) { "insufficient ERG: inputs $totalIn, outputs $totalOut, miner fee $minerFeeNanoErg" }
        require(changeTokens.isEmpty() || change > 0) {
            "surplus tokens require a change output, but no ERG remains for one"
        }
        require(change == 0L || change >= minChangeNanoErg) {
            "change $change below minimum box value $minChangeNanoErg"
        }

        val outputs = candidates.toMutableList()
        if (change > 0) {
            outputs += candidate(change, changeTree, changeTokens, emptyList(), currentHeight)
        }

        val ext = ErgoBridge.contextExtension(contextVars)
        val unsignedTx: UnsignedErgoLikeTransaction =
            ErgoBridge.unsignedTxWithExt(inputs, emptyList(), outputs, ext, contextVarInputIndex)

        // The prover reads context variables from each input's ExtendedInputBox
        // extension — mirror the context extension there (empty elsewhere).
        val extendedInputs = inputs.mapIndexed { i, b ->
            ExtendedInputBox(b, if (i == contextVarInputIndex) ext else ErgoBridge.emptyExt())
        }
        val preHeader = offlinePreHeader(currentHeight, txTimestampMs ?: 0L)
        val unsigned = UnsignedTransactionImpl(
            unsignedTx,
            extendedInputs,
            emptyList(),
            changeAddressErgo,
            OfflineStateContext(preHeader),
            offlineContext(networkType),
            emptyList(),
        )
        return signer.sign(unsigned)
    }

    /** Rebuilds an [ErgoBox] from a [ChainBox] under the given vault [tree]. */
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
            ErgoBridge.tokens(box.tokens.map { Tuple2(Base16.decode(it.tokenId), it.amount) }),
            ErgoBridge.regs(regs),
            box.transactionId,
            box.index.toShort(),
            box.creationHeight,
        )
    }

    /** Fee/funding inputs are ordinary wallet boxes: their tree comes from the explorer-provided hex. */
    fun decodeTree(box: ChainBox): ErgoTree =
        JavaHelpers.decodeStringToErgoTree(box.ergoTreeHex)

    fun candidate(
        value: Long,
        tree: ErgoTree,
        tokens: List<ChainToken>,
        registers: List<Pair<Int, EvaluatedValue<out SType>>>,
        creationHeight: Int,
    ): ErgoBoxCandidate = ErgoBridge.candidate(
        value,
        tree,
        creationHeight,
        if (tokens.isEmpty()) ErgoBridge.emptyTokens() else ErgoBridge.tokens(tokens.map { Tuple2(Base16.decode(it.tokenId), it.amount) }),
        ErgoBridge.regs(registers.map { Tuple2(ErgoBridge.regId(it.first), it.second) }),
    )

    /** [candidate] for outputs whose registers are already sigma (registers copied from an input box). */
    fun candidateRaw(
        value: Long,
        tree: ErgoTree,
        tokens: List<ChainToken>,
        registers: List<Tuple2<org.ergoplatform.ErgoBox.NonMandatoryRegisterId, EvaluatedValue<out SType>>>,
        creationHeight: Int,
    ): ErgoBoxCandidate = ErgoBridge.candidate(
        value,
        tree,
        creationHeight,
        if (tokens.isEmpty()) ErgoBridge.emptyTokens() else ErgoBridge.tokens(tokens.map { Tuple2(Base16.decode(it.tokenId), it.amount) }),
        ErgoBridge.regs(registers),
    )

    /** sigma 6 stores tuple registers as Coll, so the contracts pack two ints as (hi << 32) | lo. */
    fun packInts(hi: Int, lo: Int): Long = hi.toLong() * 4294967296L + lo

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

    /**
     * appkit's [UnsignedTransactionImpl] wants a concrete context; an offline
     * build has no node, so a stub data source (empty headers, no queries) stands in.
     */
    private class OfflineDataSource(private val networkName: String) : org.ergoplatform.appkit.BlockchainDataSource {
        override fun getParameters(): org.ergoplatform.appkit.BlockchainParameters {
            val nodeInfo = org.ergoplatform.restapi.client.NodeInfo()
            nodeInfo.network = networkName
            // Mainnet parameter defaults (ergo node reference values) — enough for
            // offline cost accounting during signing; no live node is consulted.
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
        override fun getUnspentBoxesFor(address: Address, offset: Int, limit: Int): List<org.ergoplatform.appkit.InputBox> = emptyList()
        override fun getUnconfirmedUnspentBoxesFor(address: Address, offset: Int, limit: Int): List<org.ergoplatform.appkit.InputBox> = emptyList()
        override fun getUnconfirmedTransactions(offset: Int, limit: Int): List<org.ergoplatform.appkit.Transaction> = emptyList()
    }

    private fun offlineContext(networkType: NetworkType): BlockchainContextImpl =
        BlockchainContextImpl(OfflineDataSource(networkType.verboseName), networkType)

    fun offlinePreHeader(height: Int, timestampMs: Long): sigma.PreHeader =
        sigmastate.eval.CPreHeader.apply(
            1.toByte(),
            JavaHelpers.collFrom(ByteArray(32)),
            timestampMs,
            0L,
            height,
            sigma.data.CGroupElement.apply(sigma.crypto.CryptoContext.default().generator()),
            JavaHelpers.collFrom(byteArrayOf(1, 1, 1)),
        )
}
