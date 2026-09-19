package p2pgate.backend.api

import p2pgate.backend.aml.AmlDecision
import p2pgate.backend.aml.RiskScorer
import p2pgate.backend.aml.RiskScorerException
import p2pgate.backend.bus.EventBus
import p2pgate.backend.disputes.DisputeInbox
import p2pgate.backend.engine.DealEngine
import p2pgate.backend.infra.InfraSignal
import p2pgate.backend.infra.InfraMonitor
import p2pgate.backend.oracle.OracleClient
import p2pgate.backend.quotes.QuotePublisher
import p2pgate.backend.store.AmlRecord
import p2pgate.backend.store.DealRecord
import p2pgate.backend.store.DealStore
import p2pgate.backend.util.Hex
import p2pgate.backend.vault.CollateralPool
import p2pgate.backend.vault.PoolView
import p2pgate.backend.vault.VaultManager
import p2pgate.backend.watcher.ChainWatcher
import p2pgate.dealprotocol.DealEvent
import p2pgate.dealprotocol.DealState
import p2pgate.dealprotocol.DealTerms
import p2pgate.dealprotocol.ProtocolConstants
import p2pgate.ergo.ErgoContracts
import java.time.Duration
import java.time.Instant

/**
 * Backend configuration surface. Wired from HOCON (`application.conf`) plus
 * environment overrides in the Ktor module; constructed directly in tests.
 */
data class BackendConfig(
    /** Operator dashboard key (long-lived). `null` disables dashboard auth (dev only). */
    val operatorKey: String? = null,
    /** Fail-loud webhook for unactioned claims approaching maturation (§7). */
    val webhookUrl: String? = null,
    /** Pre-mixed reserve balance (USDT base units) — the capacity base (§4). */
    val mixReadyCollateral: Long = 0,
    /** Operator cost floor in bps — a spread below it publishes with a warning. */
    val costFloorBps: Int = 0,
    val quoteTtl: Duration = QuotePublisher.DEFAULT_TTL,
    val quoteDefaultEtaMinutes: Int = 60,
    val escalationLeadTime: Duration = Duration.ofHours(2),
    /** On-chain reclaim timeout in blocks, pinned in the FUNDED box's R8. */
    val reclaimTimeoutBlocks: Int = p2pgate.contracts.ContractParams.RECLAIM_TIMEOUT_BLOCKS,
    /** Poll loop delay (ms) — the Ktor module runs the tickers on this cadence. */
    val pollDelayMs: Long = 30_000,
    /** The USE collateral token id (32-byte hex) for funded deals. */
    val collateralTokenIdHex: String = "",
) {
    init {
        if (collateralTokenIdHex.isNotEmpty()) {
            require(Hex.decode(collateralTokenIdHex).size == 32) { "collateralTokenIdHex must be 32 bytes" }
        }
    }
}

sealed interface CreateDealOutcome {
    data class Created(val deal: DealRecord, val dealToken: String) : CreateDealOutcome
    data class Rejected(val reason: String) : CreateDealOutcome
}

/**
 * Composition root: the service facade the HTTP layer (and the tests) drive.
 * Owns the schedulers' manual `tick()` (watcher sweep, reclaim scheduler,
 * dispute escalation, quote TTL) and the oracle polling loop that drives
 * `PaymentConfirmed` + the automatic release.
 */
class BackendApp(
    val store: DealStore,
    val bus: EventBus,
    val engine: DealEngine,
    val watcher: ChainWatcher,
    val vaultManager: VaultManager,
    val quotes: QuotePublisher,
    val oracle: OracleClient,
    val riskScorer: RiskScorer,
    val infra: InfraMonitor,
    val inbox: DisputeInbox,
    val tokens: TokenService,
    val pool: CollateralPool,
    val trees: ErgoContracts.VaultTrees,
    val chain: p2pgate.ergo.ChainSource,
    val config: BackendConfig,
) {
    init {
        tokens.subscribeToClosures(bus)
        // §8 auto-pause: the moment verification degrades, the quote feed is
        // withdrawn — buyers see no quotes, not stale ones.
        bus.subscribe { e ->
            if (e is p2pgate.backend.bus.BackendEvent.PauseChanged && e.paused) {
                quotes.withdraw("auto-pause: ${e.cause}", e.at)
            }
        }
    }

    /**
     * One scheduler sweep: chain facts, reclaim timeouts, claim maturation,
     * dispute escalation, quote TTL. No threads here — the Ktor module calls
     * this on a fixed-delay loop; tests call it by hand.
     */
    fun tick(now: Instant = Instant.now()) {
        watcher.tick(now)
        vaultManager.tickReclaims(now)
        inbox.tick(now)
        quotes.tick(now)
    }

    /**
     * Oracle polling: for deals with an attestation on file, applies
     * `PaymentConfirmed` (PAYMENT_PENDING → PAYMENT_CONFIRMED, or the
     * contested flag during a claim — never rejected) and follows
     * confirmation with the automatic release (path C) without buyer action.
     * An unreachable oracle degrades the ORACLE_LAG signal and pauses new
     * business; in-flight deals are unaffected (§8).
     */
    fun pollOracle(now: Instant = Instant.now()) {
        val reachable = try {
            oracle.reachable()
        } catch (e: Exception) {
            false
        }
        infra.report(InfraSignal.ORACLE_LAG, reachable)
        if (!reachable) return
        for (deal in store.openDeals()) {
            val attestation = try {
                oracle.attestationFor(deal.dealId)
            } catch (e: p2pgate.backend.oracle.OracleUnavailableException) {
                null
            } ?: continue
            if (deal.state == DealState.PAYMENT_PENDING) {
                val r = engine.apply(deal.dealId, DealEvent.PaymentConfirmed, now)
                if (r is DealEngine.Result.Advanced &&
                    store.getDeal(deal.dealId)?.state == DealState.PAYMENT_CONFIRMED
                ) {
                    vaultManager.releaseIfConfirmed(deal.dealId, now)
                }
            } else if (deal.state == DealState.CLAIM_OPENED || deal.state == DealState.CLAIMABLE) {
                engine.apply(deal.dealId, DealEvent.PaymentConfirmed, now)
            }
        }
    }

    /**
     * Deal creation at QUOTED (POST /v1/deals): validates the live quote and
     * amount, runs the AML pre-check on the declared receive address
     * (fail-closed: an unreachable scorer rejects the deal), builds the
     * canonical terms, and mints the deal token. A REJECT means no deal.
     */
    fun createDeal(request: CreateDealRequest, now: Instant = Instant.now()): CreateDealOutcome {
        val quote = quotes.activeQuote(request.quoteId, now)
            ?: return CreateDealOutcome.Rejected("unknown or inactive quote ${request.quoteId}")
        if (request.amount <= 0 || request.amount > quote.maxAmount) {
            return CreateDealOutcome.Rejected("amount ${request.amount} outside quote bounds (max ${quote.maxAmount})")
        }
        val recipient = try {
            Hex.decode(request.receiveAddress)
        } catch (e: IllegalArgumentException) {
            return CreateDealOutcome.Rejected("receiveAddress is not valid hex")
        }
        if (recipient.isEmpty()) return CreateDealOutcome.Rejected("receiveAddress is empty")
        val buyerPubKey = try {
            Hex.decode(request.buyerPubKey)
        } catch (e: IllegalArgumentException) {
            return CreateDealOutcome.Rejected("buyerPubKey is not valid hex")
        }
        if (buyerPubKey.size != DealTerms.PUBKEY_SIZE) {
            return CreateDealOutcome.Rejected("buyerPubKey must be ${DealTerms.PUBKEY_SIZE} bytes")
        }

        val decision = try {
            riskScorer.score(recipient, 1)
        } catch (e: RiskScorerException) {
            return CreateDealOutcome.Rejected("AML scorer unreachable — deal not created (fail-closed)")
        }
        if (decision == AmlDecision.REJECT) {
            return CreateDealOutcome.Rejected("AML decision is REJECT")
        }

        val sellerPubKey = vaultManager.signer.publicKeyCompressed
        val nonce = ByteArray(DealTerms.NONCE_SIZE).also { java.security.SecureRandom().nextBytes(it) }
        val terms = DealTerms(
            dealNonce = nonce,
            asset = 1,              // USDT
            srcChainId = 1,         // Tron (phase-1 default)
            amount = request.amount,
            fiatAmount = 0,         // fiat leg settles off-chain in M3
            fiatCurrency = "USD".toByteArray(),
            buyerPubKey = buyerPubKey,
            sellerPubKey = sellerPubKey,
            quoteExpiry = now.plus(ProtocolConstants.RECLAIM_TIMEOUT).epochSecond.coerceIn(0, 0xFFFF_FFFFL),
        )
        val record = DealRecord(
            dealId = Hex.encode(terms.dealId),
            quoteId = quote.id,
            dealNonceHex = Hex.encode(nonce),
            quoteExpiry = terms.quoteExpiry,
            asset = terms.asset,
            srcChainId = terms.srcChainId,
            amount = terms.amount,
            fiatAmount = terms.fiatAmount,
            fiatCurrency = "USD",
            buyerPubKeyHex = Hex.encode(buyerPubKey),
            sellerPubKeyHex = Hex.encode(sellerPubKey),
            recipientAddrHex = Hex.encode(recipient),
            collateralTokenIdHex = config.collateralTokenIdHex,
            createdAt = now,
            amlRecords = listOf(AmlRecord(decision, riskScorer.scorerId, Hex.encode(recipient), now)),
        )
        return when (val r = engine.createDeal(record, now)) {
            is DealEngine.Result.Advanced -> {
                val dealToken = tokens.mint(TokenService.Scope.DealToken(record.dealId))
                CreateDealOutcome.Created(record, dealToken)
            }
            is DealEngine.Result.Violation -> CreateDealOutcome.Rejected(r.reason)
            DealEngine.Result.Aborted -> CreateDealOutcome.Rejected("deal aborted")
        }
    }

    fun poolView(): PoolView = PoolView(
        mixReady = pool.mixReady,
        locked = pool.locked(store),
        free = pool.free(store),
        utilizationPct = pool.utilizationPct(store),
        openDeals = store.openDeals().size,
    )

    /** The vault-lane kanban (§3): deals grouped under their canonical state names. */
    fun lane(): Map<DealState, List<DealRecord>> =
        DealState.entries.associateWith { state -> store.openDeals().filter { it.state == state } }

    companion object {
        /**
         * The exit-path hint a lane card shows (§3: every card shows locked
         * collateral, the timeout countdown, and the exit path currently
         * available — a card with no exit and no countdown is a bug indicator).
         */
        fun exitPath(deal: DealRecord): String = when (deal.state) {
            DealState.QUOTED -> "cancel/expire before funding (QuoteExpired; no on-chain footprint)"
            DealState.FUNDED ->
                if (deal.handoffRecordHex != null) "none — handoff record on file, do not show timeout reclaim"
                else "timeout reclaim (auto-job)"
            DealState.PAYMENT_PENDING -> "none — reclaim from here is a theft path; the buyer's answer is the claim"
            DealState.PAYMENT_CONFIRMED -> "automatic release via oracle digest; timeout reclaim as fallback"
            DealState.RELEASED -> "terminal — collateral returns to pool"
            DealState.RECLAIMED -> "terminal"
            DealState.CLAIM_OPENED -> "contest (path C′), accept (loss), investigate (evidence review)"
            DealState.CLAIMABLE -> "last-chance contest only"
            DealState.CLAIMED -> "terminal — loss recorded"
        }
    }
}
