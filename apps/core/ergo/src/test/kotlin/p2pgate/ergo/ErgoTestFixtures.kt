package p2pgate.ergo

import org.ergoplatform.appkit.Address
import org.ergoplatform.appkit.ColdErgoClient
import org.ergoplatform.appkit.NetworkType
import org.ergoplatform.appkit.SignedTransaction
import org.ergoplatform.appkit.UnsignedTransaction
import p2pgate.contracts.ContractParams
import p2pgate.dealprotocol.DealTerms
import p2pgate.dealprotocol.HandoffRecord
import sigma.data.ProveDlog
import java.math.BigInteger

/**
 * Shared fixtures for the `:apps:core:ergo` suite: deterministic deal-scoped
 * keys, the compiled vault parameter set, and `ChainSource`-shaped vault/fee
 * boxes with register-exact contents (`specs/vault-contract.md` §3.1/§4.1
 * layouts).
 */
object ErgoTestFixtures {

    val networkType: NetworkType = NetworkType.MAINNET

    val sellerKeys = TestKeys.of(0x1111)
    val buyerKeys = TestKeys.of(0x2222)
    val dealKeys = TestKeys.of(0x4444)   // the buyer's deal key (fee inputs, change)
    val oracleKeys = TestKeys.of(0x9999) // the phase-1 dev oracle key (M3)

    /** Compiled vault trees. */
    val trees: ErgoContracts.VaultTrees = ErgoContracts.compile()

    val useTokenIdHex: String = Base16.encode(ByteArray(32) { (it * 7 + 3).toByte() })
    const val DEAL_AMOUNT: Long = 500_000_000L      // 500 USDT, 6 decimals
    const val BOX_VALUE_NANO_ERG: Long = 1_000_000L
    const val CREATION_HEIGHT: Int = 1000

    val dealKeysAddress: String = p2pkAddress(dealKeys.pubKeyCompressed)

    /** The phase-1 dev oracle pinned to this fixture set's oracle NFT id (M3). */
    fun devOracle(): DevOracle = DevOracle(oracleKeys.secret, oracleNftId = trees.oracleNftId)

    fun p2pkAddress(pubKeyCompressed: ByteArray): String =
        Address.fromSigmaBoolean(ProveDlog.apply(ErgoValues.decodePoint(pubKeyCompressed)), networkType).toString()

    // ---------------------------------------------------------------- deal shapes

    fun dealTerms(
        fiatAmount: Long = 250_000L,
        currency: String = "EGP",
        amount: Long = DEAL_AMOUNT,
    ): DealTerms = DealTerms(
        dealNonce = ByteArray(16) { it.toByte() },
        asset = 1,
        srcChainId = 1,
        amount = amount,
        fiatAmount = fiatAmount,
        fiatCurrency = currency.toByteArray(),
        buyerPubKey = buyerKeys.pubKeyCompressed,
        sellerPubKey = sellerKeys.pubKeyCompressed,
        quoteExpiry = 1_700_100_000L,
    )

    fun handoffRecord(terms: DealTerms, tsSec: Long = 1_700_000_000L): HandoffRecord = HandoffRecord(
        dealId = terms.dealId,
        amount = terms.fiatAmount,
        fiatCurrency = terms.fiatCurrency,
        timestamp = tsSec,
    )

    /** The seller's Schnorr signature over the record, as obtained at the meeting. */
    fun sellerSign(record: HandoffRecord): RefSchnorr.Signature =
        RefSchnorr.sign(sellerKeys.secret, record.encode(), sellerKeys.pubKeyCompressed)

    // ---------------------------------------------------------------- chain boxes

    fun fundedChainBox(
        terms: DealTerms,
        timeoutHeight: Int = CREATION_HEIGHT + ContractParams.RECLAIM_TIMEOUT_BLOCKS,
        tokens: List<ChainToken> = listOf(ChainToken(useTokenIdHex, DEAL_AMOUNT)),
        value: Long = BOX_VALUE_NANO_ERG,
        boxId: String = "aa".repeat(32),
        spentTxId: String? = null,
        trees: ErgoContracts.VaultTrees = this.trees,
    ): ChainBox = ChainBox(
        boxId = boxId,
        transactionId = "ab".repeat(32),
        index = 0,
        value = value,
        creationHeight = CREATION_HEIGHT,
        ergoTreeHex = trees.fundedPropositionHex,
        address = trees.fundedAddress.toString(),
        tokens = tokens,
        registers = listOf(
            ChainRegister.CollBytes(terms.dealId),
            ChainRegister.CollBytes(sellerKeys.pubKeyCompressed),
            ChainRegister.CollBytes(buyerKeys.pubKeyCompressed),
            ChainRegister.CollBytes(trees.oracleNftId),
            ChainRegister.Int64(timeoutHeight.toLong()),
            null,
        ),
        spentTransactionId = spentTxId,
    )

    fun provenChainBox(
        terms: DealTerms,
        proofHeight: Int = 1500,
        recordId: ByteArray = ByteArray(32) { 7 },
        tokens: List<ChainToken> = listOf(ChainToken(useTokenIdHex, DEAL_AMOUNT)),
        value: Long = BOX_VALUE_NANO_ERG,
        boxId: String = "bb".repeat(32),
        spentTxId: String? = null,
        trees: ErgoContracts.VaultTrees = this.trees,
    ): ChainBox = ChainBox(
        boxId = boxId,
        transactionId = "ac".repeat(32),
        index = 0,
        value = value,
        creationHeight = proofHeight,
        ergoTreeHex = trees.provenPropositionHex,
        address = trees.provenAddress.toString(),        tokens = tokens,
        registers = listOf(
            ChainRegister.CollBytes(terms.dealId),
            ChainRegister.CollBytes(sellerKeys.pubKeyCompressed),
            ChainRegister.CollBytes(buyerKeys.pubKeyCompressed),
            ChainRegister.Int64(proofHeight.toLong()),
            ChainRegister.CollBytes(recordId),
            null,
        ),
        spentTransactionId = spentTxId,
    )

    /** A plain deal-key P2PK box to fund miner fees. */
    fun feeChainBox(
        value: Long = 5_000_000L,
        boxId: String = "cc".repeat(32),
    ): ChainBox = ChainBox(
        boxId = boxId,
        transactionId = "cd".repeat(32),
        index = 0,
        value = value,
        creationHeight = CREATION_HEIGHT,
        ergoTreeHex = ErgoValues.treeHex(ErgoValues.p2pkTree(dealKeys.pubKeyCompressed)),
        address = dealKeysAddress,
        tokens = emptyList(),
        registers = List(6) { null },
    )

    /** An operator-side funding box: ERG + USE collateral under the seller key. */
    fun fundingChainBox(
        value: Long = 20_000_000L,
        tokens: List<ChainToken> = listOf(ChainToken(useTokenIdHex, DEAL_AMOUNT)),
        keys: TestKeys = sellerKeys,
        boxId: String = "f1".repeat(32),
    ): ChainBox = ChainBox(
        boxId = boxId,
        transactionId = "f2".repeat(32),
        index = 0,
        value = value,
        creationHeight = CREATION_HEIGHT,
        ergoTreeHex = ErgoValues.treeHex(ErgoValues.p2pkTree(keys.pubKeyCompressed)),
        address = p2pkAddress(keys.pubKeyCompressed),
        tokens = tokens,
        registers = List(6) { null },
    )

    // ---------------------------------------------------------------- signers

    /** Signs with appkit's prover over a cold (offline) context — no wallet, no network. */
    class ProverSigner(vararg secrets: BigInteger) : DealTxSigner {
        private val secrets: List<BigInteger> = secrets.toList()

        override fun sign(tx: UnsignedTransaction): SignedTransaction {
            val client = ColdErgoClient(networkType, coldParameters())
            return client.execute { ctx ->
                val builder = ctx.newProverBuilder()
                secrets.forEach { builder.withDLogSecret(it) }
                builder.build().sign(tx)
            }
        }
    }

    /**
     * Full node parameters for the cold client (the 3-arg ColdErgoClient ctor
     * leaves cost fields null, which the prover's interpreter dereferences).
     * Mainnet reference values, block version 1 (the contracts suite's
     * activated version).
     */
    fun coldParameters(): org.ergoplatform.appkit.BlockchainParameters {
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

    /** Wraps a delegate and records the unsigned transaction for assertions. */
    class RecordingSigner(private val delegate: DealTxSigner) : DealTxSigner {
        var lastUnsigned: UnsignedTransaction? = null
            private set

        override fun sign(tx: UnsignedTransaction): SignedTransaction {
            lastUnsigned = tx
            return delegate.sign(tx)
        }
    }
}
