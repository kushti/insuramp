package p2pgate.backend

import org.ergoplatform.appkit.ColdErgoClient
import org.ergoplatform.appkit.NetworkType
import org.ergoplatform.appkit.SignedTransaction
import org.ergoplatform.appkit.UnsignedTransaction
import p2pgate.backend.aml.AmlDecision
import p2pgate.backend.aml.ConfigRiskScorer
import p2pgate.backend.aml.RiskScorer
import p2pgate.backend.api.BackendApp
import p2pgate.backend.api.BackendConfig
import p2pgate.backend.api.TokenService
import p2pgate.backend.bus.BackendEvent
import p2pgate.backend.bus.EventBus
import p2pgate.backend.disputes.DisputeInbox
import p2pgate.backend.disputes.EscalationHook
import p2pgate.backend.disputes.NoOpEscalation
import p2pgate.backend.disputes.WebhookEscalation
import p2pgate.backend.engine.DealEngine
import p2pgate.backend.infra.InfraMonitor
import p2pgate.backend.oracle.DevOracleClient
import p2pgate.backend.quotes.QuotePublisher
import p2pgate.backend.store.AmlRecord
import p2pgate.backend.store.DealRecord
import p2pgate.backend.store.DealStore
import p2pgate.backend.store.InMemoryDealStore
import p2pgate.backend.util.Hex
import p2pgate.backend.util.Secp256k1
import p2pgate.backend.util.SigmaTrees
import p2pgate.backend.vault.CollateralPool
import p2pgate.backend.vault.NoOpTxSubmitter
import p2pgate.backend.vault.TxSubmitter
import p2pgate.backend.vault.VaultManager
import p2pgate.backend.vault.VaultSigner
import p2pgate.backend.watcher.ChainWatcher
import p2pgate.dealprotocol.DealEvent
import p2pgate.dealprotocol.DealTerms
import p2pgate.dealprotocol.HandoffRecord
import p2pgate.ergo.ChainBox
import p2pgate.ergo.ChainRegister
import p2pgate.ergo.ChainSource
import p2pgate.ergo.ChainSpend
import p2pgate.ergo.ChainToken
import p2pgate.ergo.DealTxSigner
import p2pgate.ergo.DevOracle
import p2pgate.ergo.ErgoContracts
import p2pgate.ergo.PaymentAttestation
import java.math.BigInteger
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Shared wall-clock anchor for the backend suite. */
val T0: Instant = Instant.parse("2026-09-17T12:00:00Z")

/** Deterministic keys, boxes, fake chain and a full fake-wired BackendApp. */
object Fx {
    val networkType: NetworkType = NetworkType.MAINNET

    class Keys(val secret: BigInteger, val pubKeyCompressed: ByteArray)

    fun keys(d: Long): Keys = BigInteger.valueOf(d).let { Keys(it, Secp256k1.publicKeyCompressed(it)) }

    val seller = keys(0x1111)
    val buyer = keys(0x2222)
    val oracle = keys(0x9999)

    /** Compiled vault trees (canonical dummy parameters). */
    val trees: ErgoContracts.VaultTrees by lazy { ErgoContracts.compile() }

    fun devOracle(): DevOracle = DevOracle(oracle.secret, oracleNftId = trees.oracleNftId)

    val useTokenIdHex: String = Hex.encode(ByteArray(32) { (it * 7 + 3).toByte() })
    val recipientRaw: ByteArray = ByteArray(21) { (it * 19 + 6).toByte() }
    const val AMOUNT: Long = 500_000_000L       // 500 USDT, 6 decimals
    const val FUNDING_HEIGHT: Int = 1000

    fun p2pkAddress(pk: ByteArray): String = SigmaTrees.p2pkAddress(pk, networkType)

    /** Signs offline via appkit's cold client (no wallet, no network). */
    class ProverSigner(vararg secrets: BigInteger) : DealTxSigner {
        private val secrets = secrets.toList()
        override fun sign(tx: UnsignedTransaction): SignedTransaction =
            ColdErgoClient(networkType, DevOracle.coldParameters(networkType)).execute { ctx ->
                val builder = ctx.newProverBuilder()
                secrets.forEach { builder.withDLogSecret(it) }
                builder.build().sign(tx)
            }
    }

    fun p2pkBox(pk: ByteArray, value: Long, tokens: List<ChainToken>, boxId: String): ChainBox = ChainBox(
        boxId = boxId,
        transactionId = "cd".repeat(32),
        index = 0,
        value = value,
        creationHeight = FUNDING_HEIGHT,
        ergoTreeHex = SigmaTrees.p2pkTreeHex(pk),
        address = p2pkAddress(pk),
        tokens = tokens,
        registers = List(6) { null },
    )

    /** Operator-side funding box: ERG + USE collateral under the seller key. */
    fun fundingBox(amount: Long = AMOUNT, boxId: String = "f1".repeat(32)): ChainBox =
        p2pkBox(seller.pubKeyCompressed, 20_000_000L, listOf(ChainToken(useTokenIdHex, amount)), boxId)

    fun feeBox(pk: ByteArray = seller.pubKeyCompressed, value: Long = 5_000_000L, boxId: String = "cc".repeat(32)): ChainBox =
        p2pkBox(pk, value, emptyList(), boxId)

    /** A FUNDED vault box for [deal] (register-exact, `specs/vault-contract.md` §3.1). */
    fun fundedBox(
        deal: DealRecord,
        boxId: String,
        timeoutHeight: Int = FUNDING_HEIGHT + p2pgate.contracts.ContractParams.RECLAIM_TIMEOUT_BLOCKS,
        spentTxId: String? = null,
    ): ChainBox {
        val terms = deal.terms()
        return ChainBox(
            boxId = boxId,
            transactionId = "ab".repeat(32),
            index = 0,
            value = 1_000_000L,
            creationHeight = FUNDING_HEIGHT,
            ergoTreeHex = trees.fundedPropositionHex,
            address = trees.fundedAddress.toString(),
            tokens = listOf(ChainToken(deal.collateralTokenIdHex, deal.amount)),
            registers = listOf(
                ChainRegister.CollBytes(terms.dealId),
                ChainRegister.CollBytes(seller.pubKeyCompressed),
                ChainRegister.CollBytes(buyer.pubKeyCompressed),
                ChainRegister.CollBytes(trees.oracleNftId),
                ChainRegister.Int64(timeoutHeight.toLong()),
                ChainRegister.CollBytes(
                    PaymentAttestation.fundingBinding(
                        deal.srcChainId, deal.asset,
                        PaymentAttestation.padRecipient(Hex.decode(deal.recipientAddrHex), deal.srcChainId),
                        deal.amount,
                    ),
                ),
            ),
            spentTransactionId = spentTxId,
        )
    }

    /** A PAYMENT_PROVEN vault box (post-path-B, `specs/vault-contract.md` §4.1). */
    fun provenBox(
        deal: DealRecord,
        boxId: String,
        proofHeight: Int = 1500,
        recordId: ByteArray = ByteArray(32) { 7 },
        spentTxId: String? = null,
    ): ChainBox {
        val terms = deal.terms()
        return ChainBox(
            boxId = boxId,
            transactionId = "ac".repeat(32),
            index = 0,
            value = 1_000_000L,
            creationHeight = proofHeight,
            ergoTreeHex = trees.provenPropositionHex,
            address = trees.provenAddress.toString(),
            tokens = listOf(ChainToken(deal.collateralTokenIdHex, deal.amount)),
            registers = listOf(
                ChainRegister.CollBytes(terms.dealId),
                ChainRegister.CollBytes(seller.pubKeyCompressed),
                ChainRegister.CollBytes(buyer.pubKeyCompressed),
                ChainRegister.Int64(proofHeight.toLong()),
                ChainRegister.CollBytes(recordId),
                ChainRegister.CollBytes(
                    PaymentAttestation.fundingBinding(
                        deal.srcChainId, deal.asset,
                        PaymentAttestation.padRecipient(Hex.decode(deal.recipientAddrHex), deal.srcChainId),
                        deal.amount,
                    ),
                ),
            ),
            spentTransactionId = spentTxId,
        )
    }

    /** A buyer/seller payout box (plain P2PK carrying the collateral token). */
    fun payoutBox(pk: ByteArray, tokenId: String, amount: Long, boxId: String): ChainBox =
        p2pkBox(pk, 1_000_000L, listOf(ChainToken(tokenId, amount)), boxId)

    /** In-memory ChainSource: boxes, spends and a settable tip height. */
    class FakeChain(var height: Int = FUNDING_HEIGHT) : ChainSource {
        val boxes = ConcurrentHashMap<String, ChainBox>()
        val spends = ConcurrentHashMap<String, ChainSpend>()

        override fun getBox(boxId: String): ChainBox? = boxes[boxId]

        override fun getSpendingTransaction(boxId: String): ChainSpend? {
            val txId = boxes[boxId]?.spentTransactionId ?: return null
            return spends[txId]
        }

        override fun getCurrentHeight(): Int = height

        override fun getUnspentBoxes(address: String): List<ChainBox> =
            boxes.values.filter { it.address == address && it.spentTransactionId == null }.toList()

        /** Marks [boxId] spent by a tx recorded under [txId]. */
        fun spend(boxId: String, txId: String, spend: ChainSpend) {
            val box = boxes[boxId] ?: throw NoSuchElementException("box $boxId not installed")
            boxes[boxId] = ChainBox(
                box.boxId, box.transactionId, box.index, box.value, box.creationHeight,
                box.ergoTreeHex, box.address, box.tokens, box.registers, txId,
            )
            spends[txId] = spend
        }
    }

    /** Records every submitted tx (assertion driver) and returns the tx id. */
    class RecordingSubmitter : TxSubmitter {
        val submitted = mutableListOf<SignedTransaction>()
        override fun submit(tx: SignedTransaction): String {
            submitted += tx
            return tx.id
        }
    }

    /** A vault signer with no boxes — for component tests that never build real txs. */
    object EmptySigner : VaultSigner {
        override val publicKeyCompressed: ByteArray = seller.pubKeyCompressed
        override val changeAddress: String = p2pkAddress(seller.pubKeyCompressed)
        override fun fundingInputs(collateralTokenIdHex: String, minAmount: Long): List<ChainBox> = emptyList()
        override fun feeInputs(): List<ChainBox> = emptyList()
        override fun signer(): DealTxSigner = ProverSigner(seller.secret)
        override fun signHandoff(record: ByteArray) = p2pgate.backend.util.Schnorr.sign(
            seller.secret, record, seller.pubKeyCompressed,
        )
    }

    /** A signer that can really fund/reclaim: seller-key funding + fee boxes. */
    class RealSigner(
        val funding: List<ChainBox> = listOf(fundingBox()),
        val fees: List<ChainBox> = listOf(feeBox()),
    ) : VaultSigner {
        override val publicKeyCompressed: ByteArray = seller.pubKeyCompressed
        override val changeAddress: String = p2pkAddress(seller.pubKeyCompressed)
        override fun fundingInputs(collateralTokenIdHex: String, minAmount: Long): List<ChainBox> =
            funding.filter { it.tokenAmount(collateralTokenIdHex) >= minAmount }
        override fun feeInputs(): List<ChainBox> = fees
        override fun signer(): DealTxSigner = ProverSigner(seller.secret)
        override fun signHandoff(record: ByteArray) = p2pgate.backend.util.Schnorr.sign(
            seller.secret, record, seller.pubKeyCompressed,
        )
    }
}

/**
 * A fully wired BackendApp over fakes: in-memory store, fake chain,
 * DevOracle, recording submitter. The scheduler stays manual (`tick()`).
 */
class TestEnv(
    val riskScorer: RiskScorer = ConfigRiskScorer(scorerId = "test-scorer"),
    mixReady: Long = 10_000_000_000L,
    val operatorKey: String? = "op-secret",
    vaultSigner: VaultSigner = Fx.EmptySigner,
    webhook: ((url: String, body: String) -> Unit)? = null,
) {
    val chain = Fx.FakeChain()
    val store: DealStore = InMemoryDealStore()
    val bus = EventBus()
    val engine = DealEngine(store, bus)
    val infra = InfraMonitor(bus)
    val pool = CollateralPool(mixReady)
    val devOracle = Fx.devOracle()
    val oracle = DevOracleClient(devOracle)
    val submitter = Fx.RecordingSubmitter()
    val vaultManager = VaultManager(
        trees = Fx.trees,
        signer = vaultSigner,
        chain = chain,
        submitter = submitter,
        engine = engine,
        store = store,
        bus = bus,
        infra = infra,
        oracle = oracle,
        riskScorer = riskScorer,
    )
    val watcher = ChainWatcher(chain, Fx.trees, engine, store)
    val quotes = QuotePublisher(store, infra, { pool.free(store) }, bus)
    val hook: EscalationHook = webhook?.let { WebhookEscalation("http://hook.local/escalate", it) } ?: NoOpEscalation
    val inbox = DisputeInbox(store, vaultManager, oracle, hook, bus)
    val tokens = TokenService()
    val config = BackendConfig(
        operatorKey = operatorKey,
        mixReadyCollateral = mixReady,
        collateralTokenIdHex = Fx.useTokenIdHex,
    )
    val app = BackendApp(
        store, bus, engine, watcher, vaultManager, quotes, oracle, riskScorer,
        infra, inbox, tokens, pool, Fx.trees, chain, config,
    )

    /** Every bus event, in order (assertion driver). */
    val events = mutableListOf<BackendEvent>()

    init {
        bus.subscribe { events += it }
    }

    /** A QUOTED deal record (committed to the store) with an AML decision on file. */
    fun quotedDeal(
        amount: Long = Fx.AMOUNT,
        createdAt: Instant = T0,
        recipient: ByteArray = Fx.recipientRaw,
        aml: AmlDecision = AmlDecision.ACCEPT,
        nonce: ByteArray = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) },
    ): DealRecord {
        val terms = DealTerms(
            dealNonce = nonce,
            asset = 1,
            srcChainId = 1,
            amount = amount,
            fiatAmount = 250_000L,
            fiatCurrency = "USD".toByteArray(),
            buyerPubKey = Fx.buyer.pubKeyCompressed,
            sellerPubKey = Fx.seller.pubKeyCompressed,
            quoteExpiry = createdAt.plusSeconds(3600).epochSecond,
        )
        val record = DealRecord(
            dealId = Hex.encode(terms.dealId),
            quoteId = "quote-test",
            dealNonceHex = Hex.encode(nonce),
            quoteExpiry = terms.quoteExpiry,
            asset = terms.asset,
            srcChainId = terms.srcChainId,
            amount = terms.amount,
            fiatAmount = terms.fiatAmount,
            fiatCurrency = "USD",
            buyerPubKeyHex = Hex.encode(Fx.buyer.pubKeyCompressed),
            sellerPubKeyHex = Hex.encode(Fx.seller.pubKeyCompressed),
            recipientAddrHex = Hex.encode(recipient),
            collateralTokenIdHex = Fx.useTokenIdHex,
            createdAt = createdAt,
            amlRecords = listOf(AmlRecord(aml, riskScorer.scorerId, Hex.encode(recipient), createdAt)),
        )
        engine.createDeal(record, createdAt)
        return record
    }

    /** Moves a QUOTED deal to FUNDED with a funded box installed in the fake chain. */
    fun forceFund(record: DealRecord, boxId: String = "aa".repeat(32), at: Instant = T0): DealRecord {
        store.updateDeal(record.dealId) { it.copy(vaultBoxId = boxId) }
        chain.boxes[boxId] = Fx.fundedBox(record, boxId)
        engine.apply(record.dealId, DealEvent.VaultFunded(at), at)
        return store.getDeal(record.dealId)!!
    }

    /** The seller-signed handoff flow at component level (record as obtained at the meeting). */
    fun collectCash(dealId: String, at: Instant = T0) {
        val deal = store.getDeal(dealId)!!
        val record = HandoffRecord(
            dealId = deal.terms().dealId,
            amount = deal.fiatAmount,
            fiatCurrency = deal.fiatCurrency.toByteArray(Charsets.US_ASCII),
            timestamp = at.epochSecond,
        )
        store.updateDeal(dealId) { it.copy(handoffRecordHex = Hex.encode(record.encode())) }
        engine.apply(dealId, DealEvent.CashCollected(at, at), at)
    }

    fun handoffRecordFor(deal: DealRecord, at: Instant = T0): HandoffRecord = HandoffRecord(
        dealId = deal.terms().dealId,
        amount = deal.fiatAmount,
        fiatCurrency = deal.fiatCurrency.toByteArray(Charsets.US_ASCII),
        timestamp = at.epochSecond,
    )

    /** Mints a seller-payment attestation and registers it with the dev oracle client. */
    fun attestPayment(deal: DealRecord): PaymentAttestation {
        val attestation = devOracle.attest(
            deal.terms(),
            Hex.decode(deal.recipientAddrHex),
            srcTxId = ByteArray(32) { (it * 11 + 5).toByte() },
            srcBlockHeight = 61_000_000L,
            srcBlockTime = T0.epochSecond,
        )
        oracle.attest(deal.dealId, attestation)
        return attestation
    }
}
