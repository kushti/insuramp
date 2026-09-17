package p2pgate.backend.api

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.config.tryGetString
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import p2pgate.backend.bus.BackendEvent
import p2pgate.backend.bus.EventBus
import p2pgate.backend.disputes.DisputeInbox
import p2pgate.backend.disputes.WebhookEscalation
import p2pgate.backend.engine.DealEngine
import p2pgate.backend.infra.InfraMonitor
import p2pgate.backend.infra.InfraSignal
import p2pgate.backend.oracle.DevOracleClient
import p2pgate.backend.quotes.QuotePublisher
import p2pgate.backend.store.DealRecord
import p2pgate.backend.store.InMemoryDealStore
import p2pgate.backend.util.Hex
import p2pgate.backend.util.SigmaTrees
import p2pgate.backend.vault.CollateralPool
import p2pgate.backend.vault.EmbeddedVaultSigner
import p2pgate.backend.vault.NoOpTxSubmitter
import p2pgate.backend.vault.VaultManager
import p2pgate.backend.watcher.ChainWatcher
import p2pgate.dealprotocol.DealEvent
import p2pgate.dealprotocol.DealState
import p2pgate.dealprotocol.HandoffRecord
import p2pgate.dealprotocol.ProtocolConstants
import p2pgate.ergo.ChainBox
import p2pgate.ergo.ChainRegister
import p2pgate.ergo.DevOracle
import p2pgate.ergo.ErgoContracts
import p2pgate.ergo.ExplorerChainSource
import java.math.BigInteger
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

private fun ApplicationCall.bearer(): String? =
    request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim()?.takeIf { it.isNotEmpty() }

private fun quoteDto(q: p2pgate.backend.store.QuoteRecord) = QuoteDto(
    id = q.id, version = q.version, spreadBps = q.spreadBps, etaMinutes = q.etaMinutes,
    maxAmount = q.maxAmount, createdAtEpochMs = q.createdAt.toEpochMilli(), expiresAtEpochMs = q.expiresAt.toEpochMilli(),
)

private fun dealDto(d: DealRecord) = DealDto(
    dealId = d.dealId, state = d.state.name, amount = d.amount, fiatAmount = d.fiatAmount,
    fiatCurrency = d.fiatCurrency, insuredAmount = d.amount, vaultBoxId = d.vaultBoxId,
    contested = d.contested, createdAtEpochMs = d.createdAt.toEpochMilli(),
    fundedAtEpochMs = d.fundedAt?.toEpochMilli(),
    reclaimDeadlineEpochMs = d.fundedAt?.plus(ProtocolConstants.RECLAIM_TIMEOUT)?.toEpochMilli(),
    claimMaturesAtEpochMs = d.proofTimestamp?.plus(ProtocolConstants.CLAIM_MATURATION)?.toEpochMilli(),
    abandoned = d.abandoned, terminal = d.state.isTerminal,
)

private fun chainBoxDto(b: ChainBox) = ChainBoxDto(
    boxId = b.boxId, transactionId = b.transactionId, index = b.index, value = b.value,
    creationHeight = b.creationHeight, ergoTreeHex = b.ergoTreeHex, address = b.address,
    tokens = b.tokens.map { ChainTokenDto(it.tokenId, it.amount) },
    registers = b.registers.map { reg ->
        when (reg) {
            null -> null
            is ChainRegister.CollBytes -> Hex.encode(reg.serialized)
            is ChainRegister.Int64 -> Hex.encode(reg.serialized)
        }
    },
    spentTransactionId = b.spentTransactionId,
)

private fun disputeRowDto(r: p2pgate.backend.disputes.DisputeRow) = DisputeRowDto(
    dealId = r.dealId, state = r.state.name, openedAtEpochMs = r.openedAt.toEpochMilli(),
    maturesAtEpochMs = r.maturesAt.toEpochMilli(), contested = r.contested,
    handoffRecordRef = r.handoffRecordRef, geoRef = r.geoRef,
    oracleConfirmed = r.oracleConfirmed, attestationDigest = r.attestationDigest,
    actioned = r.actioned, action = r.action,
)

private fun eventDto(e: BackendEvent): EventDto = when (e) {
    is BackendEvent.DealTransitioned ->
        EventDto("deal.transitioned", e.dealId, "${e.from} → ${e.to}", e.at.toEpochMilli())
    is BackendEvent.DealAbandoned -> EventDto("deal.abandoned", e.dealId, "quote expired", e.at.toEpochMilli())
    is BackendEvent.InvariantViolation -> EventDto("violation", e.dealId, "${e.event}: ${e.reason}", e.at.toEpochMilli())
    is BackendEvent.QuotePublished -> EventDto("quote.published", null, json.encodeToString(quoteDto(e.quote)), e.at.toEpochMilli())
    is BackendEvent.QuoteWithdrawn -> EventDto("quote.withdrawn", null, e.cause ?: "manual", e.at.toEpochMilli())
    is BackendEvent.QuoteRejected -> EventDto("quote.rejected", null, e.reason, e.at.toEpochMilli())
    is BackendEvent.QuoteWarning -> EventDto("quote.warning", null, e.reason, e.at.toEpochMilli())
    is BackendEvent.PauseChanged -> EventDto("pause.changed", null, if (e.paused) "paused: ${e.cause}" else "resumed", e.at.toEpochMilli())
    is BackendEvent.TxSubmitted -> EventDto("tx.submitted", e.dealId, "${e.kind} $e.txId", e.at.toEpochMilli())
    is BackendEvent.Escalated -> EventDto("escalated", e.dealId, e.reason, e.at.toEpochMilli())
    is BackendEvent.CourierPanicked -> EventDto("courier.panicked", e.dealId, e.reason, e.at.toEpochMilli())
    is BackendEvent.CourierCredentialFlagged -> EventDto("courier.flagged", null, e.courierId, e.at.toEpochMilli())
}

/** Result of applying one courier handoff record. */
private enum class HandoffApply { APPLIED, DUPLICATE, REJECTED }

/**
 * The `/v1` API surface (`specs/operator-backend.md` §9). All endpoints
 * versioned under `/v1`, JSON in/out, canonical state names verbatim.
 */
fun Application.module(app: BackendApp) {
    install(ContentNegotiation) { json(json) }
    install(WebSockets)
    install(StatusPages) {
        exception<IllegalArgumentException> { call, e ->
            call.respond(HttpStatusCode.BadRequest, ErrorDto(e.message ?: "bad request"))
        }
        exception<NoSuchElementException> { call, e ->
            call.respond(HttpStatusCode.NotFound, ErrorDto(e.message ?: "not found"))
        }
        exception<Exception> { call, e ->
            call.application.log.error("unhandled failure", e)
            call.respond(HttpStatusCode.InternalServerError, ErrorDto("internal error"))
        }
    }

    val submitter = CourierOps(app)

    routing {
        route("/v1") {
            // ---------------------------------------------------------- user
            get("/quotes") {
                call.respond(QuoteFeedDto(app.quotes.active()?.let(::quoteDto)))
            }
            webSocket("/quotes/stream") {
                send(Frame.Text(json.encodeToString(QuoteFeedDto(app.quotes.active()?.let(::quoteDto)))))
                val channel = Channel<BackendEvent>(Channel.BUFFERED)
                val unsubscribe = app.bus.subscribe { e -> channel.trySend(e) }
                try {
                    for (event in channel) {
                        if (event is BackendEvent.QuotePublished || event is BackendEvent.QuoteWithdrawn ||
                            event is BackendEvent.PauseChanged
                        ) {
                            send(Frame.Text(json.encodeToString(eventDto(event))))
                        }
                    }
                } finally {
                    unsubscribe()
                }
            }
            post("/deals") {
                val request = call.receive<CreateDealRequest>()
                when (val outcome = app.createDeal(request)) {
                    is CreateDealOutcome.Created ->
                        call.respond(CreateDealResponse(outcome.deal.dealId, outcome.dealToken))
                    is CreateDealOutcome.Rejected ->
                        call.respond(HttpStatusCode.UnprocessableEntity, ErrorDto(outcome.reason))
                }
            }
            route("/deals/{id}") {
                get {
                    val id = call.dealId()
                    val token = call.bearer() ?: return@get call.respond(HttpStatusCode.Unauthorized, ErrorDto("missing bearer token"))
                    if (!app.tokens.verifyDeal(token, id)) {
                        return@get call.respond(HttpStatusCode.Forbidden, ErrorDto("invalid deal token"))
                    }
                    val deal = app.store.getDeal(id) ?: return@get call.respond(HttpStatusCode.NotFound, ErrorDto("unknown deal"))
                    call.respond(dealDto(deal))
                }
                webSocket("/stream") {
                    val id = call.dealId()
                    val token = call.request.queryParameters["token"] ?: call.bearer()
                    if (!app.tokens.verifyDeal(token, id)) {
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "invalid deal token"))
                        return@webSocket
                    }
                    val channel = Channel<BackendEvent>(Channel.BUFFERED)
                    val unsubscribe = app.bus.subscribe { e ->
                        if ((e as? BackendEvent.DealTransitioned)?.dealId == id) channel.trySend(e)
                    }
                    try {
                        for (event in channel) send(Frame.Text(json.encodeToString(eventDto(event))))
                    } finally {
                        unsubscribe()
                    }
                }
                get("/attestation") {
                    val id = call.dealId()
                    val token = call.bearer() ?: return@get call.respond(HttpStatusCode.Unauthorized, ErrorDto("missing bearer token"))
                    if (!app.tokens.verifyDeal(token, id)) {
                        return@get call.respond(HttpStatusCode.Forbidden, ErrorDto("invalid deal token"))
                    }
                    app.store.getDeal(id) ?: return@get call.respond(HttpStatusCode.NotFound, ErrorDto("unknown deal"))
                    val attestation = try {
                        app.oracle.attestationFor(id)
                    } catch (e: p2pgate.backend.oracle.OracleUnavailableException) {
                        null
                    }
                    call.respond(
                        if (attestation == null) AttestationDto("UNCONFIRMED")
                        else AttestationDto(
                            "CONFIRMED",
                            digest = Hex.encode(attestation.digest()),
                            srcTxId = Hex.encode(attestation.srcTxId),
                            srcBlockHeight = attestation.srcBlockHeight,
                            srcBlockTime = attestation.srcBlockTime,
                        ),
                    )
                }
                post("/claim") {
                    val id = call.dealId()
                    val token = call.bearer() ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorDto("missing bearer token"))
                    if (!app.tokens.verifyDeal(token, id)) {
                        return@post call.respond(HttpStatusCode.Forbidden, ErrorDto("invalid deal token"))
                    }
                    val deal = app.store.getDeal(id) ?: return@post call.respond(HttpStatusCode.NotFound, ErrorDto("unknown deal"))
                    if (deal.state != DealState.PAYMENT_PENDING && deal.state != DealState.PAYMENT_CONFIRMED) {
                        return@post call.respond(
                            HttpStatusCode.Conflict,
                            ErrorDto("a claim requires a collected cash handoff (deal is ${deal.state})"),
                        )
                    }
                    val record = deal.handoffRecordHex ?: return@post call.respond(
                        HttpStatusCode.Conflict,
                        ErrorDto("no handoff record on file for this deal"),
                    )
                    val fundedBox = app.vaultManagerBox(id) ?: return@post call.respond(
                        HttpStatusCode.Conflict,
                        ErrorDto("vault box not observable"),
                    )
                    call.respond(
                        ClaimGuideDto(
                            dealId = id,
                            state = deal.state.name,
                            handoffRecordHex = record,
                            fundedBox = chainBoxDto(fundedBox),
                            courierPubKey = deal.courierPubKeyHex,
                            userPubKey = deal.userPubKeyHex,
                            instructions = listOf(
                                "Build the path-B claim tx with ClaimTxBuilder (user side, specs/android-app.md §4.3).",
                                "Spend the funded vault box carrying the handoff record; the tx re-creates it under vault_payment_proven.es.",
                                "Broadcast it yourself — the backend observes the claim on-chain and opens the dispute timer.",
                                "After CLAIM_MATURATION (${ProtocolConstants.CLAIM_MATURATION.toHours()}h) with no seller contest, path D pays you the collateral minus fee.",
                            ),
                        ),
                    )
                }
            }

            // -------------------------------------------------------- courier
            route("/courier") {
                get("/jobs") {
                    val courierId = call.authenticateCourierAny(app) ?: return@get
                    val jobs = app.store.openDeals()
                        .filter { it.courierId == courierId }
                        .map {
                            CourierJobDto(it.dealId, it.state.name, it.amount, it.fiatAmount, it.fiatCurrency, "")
                        }
                    call.respond(jobs)
                }
                post("/sync") {
                    val courierId = call.authenticateCourierAny(app) ?: return@post
                    val request = call.receive<CourierSyncRequest>()
                    var accepted = 0
                    var duplicates = 0
                    var rejected = 0
                    for (item in request.items) {
                        when (submitter.applyHandoff(item.dealId, item.recordHex, item.gps, courierId)) {
                            HandoffApply.APPLIED -> accepted++
                            HandoffApply.DUPLICATE -> duplicates++
                            HandoffApply.REJECTED -> rejected++
                        }
                    }
                    call.respond(SyncResponse(accepted, duplicates, rejected))
                }
                route("/jobs/{dealId}") {
                    get("/key") {
                        val (deal, _) = call.authenticateCourierDeal(app) ?: return@get
                        call.respond(CourierKeyDto(deal.dealId, deal.courierId, deal.courierPubKeyHex))
                    }
                    get("/handoff") {
                        val (deal, _) = call.authenticateCourierDeal(app) ?: return@get
                        val timestamp = Instant.now().epochSecond.coerceIn(0, 0xFFFF_FFFFL)
                        val template = HandoffRecord(
                            dealId = deal.terms().dealId,
                            amount = deal.fiatAmount,
                            fiatCurrency = deal.fiatCurrency.toByteArray(Charsets.US_ASCII),
                            timestamp = timestamp,
                            courierIdHash = HandoffRecord.courierIdHash(deal.courierId.encodeToByteArray()),
                        )
                        call.respond(
                            HandoffTemplateDto(
                                dealId = deal.dealId,
                                magic = "P2PH",
                                amount = template.amount,
                                fiatCurrency = deal.fiatCurrency,
                                timestamp = timestamp,
                                courierIdHash = Hex.encode(template.courierIdHash),
                                qrPayloadPreview = Hex.encode(template.encode()),
                            ),
                        )
                    }
                    post("/handoff") {
                        val (deal, courierId) = call.authenticateCourierDeal(app) ?: return@post
                        val request = call.receive<HandoffSubmitRequest>()
                        when (submitter.applyHandoff(deal.dealId, request.recordHex, request.gps, courierId)) {
                            HandoffApply.APPLIED -> call.respond(HttpStatusCode.OK, ErrorDto("handoff recorded"))
                            HandoffApply.DUPLICATE -> call.respond(HttpStatusCode.OK, ErrorDto("handoff already on file"))
                            HandoffApply.REJECTED ->
                                call.respond(HttpStatusCode.UnprocessableEntity, ErrorDto("handoff record rejected"))
                        }
                    }
                    post("/panic") {
                        val (deal, _) = call.authenticateCourierDeal(app) ?: return@post
                        val request = call.receive<PanicRequest>()
                        app.store.updateDeal(deal.dealId) { it.copy(panicFlag = true) }
                        val now = Instant.now()
                        app.bus.publish(BackendEvent.CourierPanicked(deal.dealId, request.reason, now))
                        app.bus.publish(BackendEvent.Escalated(deal.dealId, "courier panic: ${request.reason}", now))
                        call.respond(HttpStatusCode.OK, ErrorDto("panic recorded — operator paged"))
                    }
                    post("/geo-confirm") {
                        val (deal, _) = call.authenticateCourierDeal(app) ?: return@post
                        val request = call.receive<GeoConfirmRequest>()
                        val ref = "${request.lat},${request.lon}" +
                            (request.overrideReason?.let { "|override:$it" } ?: "")
                        app.store.updateDeal(deal.dealId) { it.copy(handoffGpsRef = ref) }
                        call.respond(HttpStatusCode.OK, ErrorDto("geo confirmation recorded"))
                    }
                }
            }

            // ------------------------------------------------------ dashboard
            get("/lane") {
                call.authenticateOperator(app) ?: return@get
                val lanes = app.lane().mapValues { (_, deals) ->
                    deals.map { d ->
                        LaneCardDto(
                            dealId = d.dealId, amount = d.amount, collateralTokenId = d.collateralTokenIdHex,
                            createdAtEpochMs = d.createdAt.toEpochMilli(),
                            fundedAtEpochMs = d.fundedAt?.toEpochMilli(),
                            reclaimDeadlineEpochMs = d.fundedAt?.plus(ProtocolConstants.RECLAIM_TIMEOUT)?.toEpochMilli(),
                            claimMaturesAtEpochMs = d.proofTimestamp?.plus(ProtocolConstants.CLAIM_MATURATION)?.toEpochMilli(),
                            contested = d.contested, hasHandoffRecord = d.handoffRecordHex != null,
                            vaultBoxId = d.vaultBoxId, exitPath = BackendApp.exitPath(d),
                        )
                    }
                }
                call.respond(LaneDto(lanes.mapKeys { it.key.name }))
            }
            get("/pool") {
                call.authenticateOperator(app) ?: return@get
                val p = app.poolView()
                call.respond(PoolDto(p.mixReady, p.locked, p.free, p.utilizationPct, p.openDeals))
            }
            post("/vaults/{id}/reclaim") {
                call.authenticateOperator(app) ?: return@post
                val id = call.dealId()
                when (val outcome = app.vaultManager.reclaimDeal(id)) {
                    is VaultManager.Outcome.Submitted ->
                        call.respond(ReclaimResponse(true, outcome.txId))
                    is VaultManager.Outcome.Rejected ->
                        call.respond(HttpStatusCode.Conflict, ErrorDto(outcome.reason))
                }
            }
            get("/quotes/current") {
                call.authenticateOperator(app) ?: return@get
                call.respond(QuoteFeedDto(app.quotes.current()?.let(::quoteDto)))
            }
            put("/quotes/current") {
                call.authenticateOperator(app) ?: return@put
                val request = call.receive<PutQuoteRequest>()
                when (val outcome = app.quotes.publish(request.spreadBps, request.etaMinutes, request.maxAmount)) {
                    is QuotePublisher.PublishOutcome.Published ->
                        call.respond(PublishQuoteResponse(true, quoteDto(outcome.quote)))
                    is QuotePublisher.PublishOutcome.Rejected ->
                        call.respond(HttpStatusCode.Conflict, PublishQuoteResponse(false, null, outcome.reason))
                }
            }
            post("/aml/check") {
                call.authenticateOperator(app) ?: return@post
                val request = call.receive<AmlCheckRequest>()
                val decision = try {
                    app.riskScorer.score(Hex.decode(request.address), request.chainId)
                } catch (e: p2pgate.backend.aml.RiskScorerException) {
                    call.respond(HttpStatusCode.ServiceUnavailable, ErrorDto("scorer unreachable (fail-closed)"))
                    return@post
                }
                // Decision-only recording (§6): accept/reject + scorer id + timestamp — never the report.
                call.respond(AmlCheckResponse(decision.name, app.riskScorer.scorerId, Instant.now().toEpochMilli()))
            }
            get("/disputes") {
                call.authenticateOperator(app) ?: return@get
                call.respond(app.inbox.rows().map(::disputeRowDto))
            }
            post("/disputes/{id}/{action}") {
                call.authenticateOperator(app) ?: return@post
                val id = call.dealId()
                val outcome = when (call.parameters["action"]) {
                    "contest" -> app.inbox.contest(id)
                    "accept" -> app.inbox.accept(id)
                    "investigate" -> app.inbox.investigate(id)
                    else -> {
                        call.respond(HttpStatusCode.NotFound, ErrorDto("unknown action"))
                        return@post
                    }
                }
                when (outcome) {
                    DisputeInbox.ActionOutcome.Ok -> call.respond(HttpStatusCode.OK, ErrorDto("action recorded"))
                    is DisputeInbox.ActionOutcome.Rejected ->
                        call.respond(HttpStatusCode.Conflict, ErrorDto(outcome.reason))
                }
            }
            post("/couriers/{id}/revoke") {
                call.authenticateOperator(app) ?: return@post
                val courierId = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorDto("missing courier id"))
                app.store.flagCourier(courierId)
                app.bus.publish(BackendEvent.CourierCredentialFlagged(courierId, Instant.now()))
                call.respond(HttpStatusCode.OK, ErrorDto("courier credential revoked"))
            }
            get("/infra") {
                call.authenticateOperator(app) ?: return@get
                call.respond(
                    InfraDto(
                        paused = app.infra.paused,
                        signals = app.infra.allSignals().map { InfraSignalDto(it.signal.name, it.healthy, it.detail) },
                        pauseHistory = app.infra.pauseHistory.map {
                            PauseRecordDto(it.cause, it.startedAt.toEpochMilli(), it.endedAt?.toEpochMilli())
                        },
                    ),
                )
            }
            webSocket("/events") {
                val provided = call.request.queryParameters["token"] ?: call.bearer()
                val ok = app.config.operatorKey == null || (provided != null && MessageDigest.isEqual(
                    provided.toByteArray(),
                    app.config.operatorKey.toByteArray(),
                ))
                if (!ok) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "invalid operator token"))
                    return@webSocket
                }
                val channel = Channel<BackendEvent>(Channel.BUFFERED)
                val unsubscribe = app.bus.subscribe { e -> channel.trySend(e) }
                try {
                    for (event in channel) send(Frame.Text(json.encodeToString(eventDto(event))))
                } finally {
                    unsubscribe()
                }
            }
        }
    }
}

/** Courier-side handoff application (shared by POST handoff and the offline sync endpoint). */
private class CourierOps(private val app: BackendApp) {
    private fun skewOk(record: HandoffRecord, now: Instant): Boolean =
        Duration.between(Instant.ofEpochSecond(record.timestamp), now).abs() <= ProtocolConstants.COURIER_CLOCK_SKEW

    fun applyHandoff(dealId: String, recordHex: String, gps: String?, courierId: String): HandoffApply {
        val deal = app.store.getDeal(dealId) ?: return HandoffApply.REJECTED
        if (deal.courierId != courierId) return HandoffApply.REJECTED
        if (deal.handoffRecordHex == recordHex) return HandoffApply.DUPLICATE
        val record = try {
            HandoffRecord.decode(Hex.decode(recordHex))
        } catch (e: IllegalArgumentException) {
            return HandoffApply.REJECTED
        }
        if (!record.dealId.contentEquals(deal.terms().dealId)) return HandoffApply.REJECTED
        val now = Instant.now()
        if (!skewOk(record, now)) return HandoffApply.REJECTED
        app.store.updateDeal(dealId) { it.copy(handoffRecordHex = recordHex, handoffGpsRef = gps ?: it.handoffGpsRef) }
        return when (
            app.engine.apply(
                dealId,
                DealEvent.CashCollected(Instant.ofEpochSecond(record.timestamp), now),
                now,
            )
        ) {
            is DealEngine.Result.Advanced -> HandoffApply.APPLIED
            else -> HandoffApply.REJECTED
        }
    }
}

private fun ApplicationCall.dealId(): String =
    parameters["id"] ?: parameters["dealId"] ?: throw IllegalArgumentException("missing id")

private suspend fun ApplicationCall.authenticateOperator(app: BackendApp): Unit? {
    val key = app.config.operatorKey
    val provided = bearer()
    if (key == null) return Unit
    val ok = provided != null && MessageDigest.isEqual(
        provided.toByteArray(),
        key.toByteArray(),
    )
    return if (ok) Unit else {
        respond(HttpStatusCode.Unauthorized, ErrorDto("invalid operator key"))
        null
    }
}

/** Courier auth for the job-list endpoints: any valid courier token identifies the courier. */
private suspend fun ApplicationCall.authenticateCourierAny(app: BackendApp): String? {
    val token = bearer() ?: run {
        respond(HttpStatusCode.Unauthorized, ErrorDto("missing bearer token"))
        return null
    }
    val entry = app.store.allDeals().firstOrNull { d -> app.tokens.verifyCourier(token, d.dealId, d.courierId) }
    return if (entry == null) {
        respond(HttpStatusCode.Forbidden, ErrorDto("invalid courier token"))
        null
    } else {
        entry.courierId
    }
}

/** Courier auth for deal-scoped endpoints: the token must belong to this deal's courier. */
private suspend fun ApplicationCall.authenticateCourierDeal(app: BackendApp): Pair<DealRecord, String>? {
    val id = dealId()
    val token = bearer() ?: run {
        respond(HttpStatusCode.Unauthorized, ErrorDto("missing bearer token"))
        return null
    }
    val deal = app.store.getDeal(id) ?: run {
        respond(HttpStatusCode.NotFound, ErrorDto("unknown deal"))
        return null
    }
    return if (app.tokens.verifyCourier(token, id, deal.courierId)) {
        Pair(deal, deal.courierId)
    } else {
        respond(HttpStatusCode.Forbidden, ErrorDto("invalid courier token"))
        null
    }
}

/** The observable funded box for the claim guide, when the chain still serves it. */
private fun BackendApp.vaultManagerBox(dealId: String): ChainBox? =
    store.getDeal(dealId)?.vaultBoxId?.let { boxId -> chain.getBox(boxId) }

// ---------------------------------------------------------------------------
// Dev wiring (no-arg module): HOCON `application.conf` + environment overrides.
// ---------------------------------------------------------------------------

/**
 * Builds the M3 dev composition: in-memory store, DevOracle, explorer chain
 * source, config-driven AML stub, embedded vault signer. Funding boxes are
 * empty until the operator loads them — vault funding then fails loudly with
 * "no funding inputs", which is the honest dev-mode behavior.
 */
fun Application.module() {
    val backend = environment.config.config("backend")
    fun env(name: String): String? = System.getenv(name)

    val networkPrefix: Byte = env("P2P_NETWORK")?.let { if (it == "testnet") 0x10.toByte() else 0x00.toByte() }
        ?: backend.tryGetString("network")?.let { if (it == "testnet") 0x10.toByte() else 0x00.toByte() }
        ?: 0x00.toByte()
    val networkType = if (networkPrefix == 0x00.toByte()) {
        org.ergoplatform.appkit.NetworkType.MAINNET
    } else {
        org.ergoplatform.appkit.NetworkType.TESTNET
    }

    val treasurySecret = env("P2P_TREASURY_SECRET")?.let { BigInteger(it, 16) } ?: BigInteger.valueOf(0x5151)
    val treasuryTree = SigmaTrees.p2pkTree(
        p2pgate.backend.util.Secp256k1.publicKeyCompressed(treasurySecret),
    )
    val treasuryHash = p2pgate.backend.util.Crypto.blake2b256(treasuryTree.bytes())
    val trees = ErgoContracts.compile(
        oracleNftId = ErgoContracts.DUMMY_ORACLE_NFT_ID,
        treasuryScriptHash = treasuryHash,
        networkPrefix = networkPrefix,
    )

    val oracleSecret = env("P2P_ORACLE_SECRET")?.let { BigInteger(it, 16) } ?: BigInteger.valueOf(0x9999)
    val oracle = DevOracleClient(DevOracle(oracleSecret, oracleNftId = trees.oracleNftId, networkPrefix = networkPrefix))

    val explorerUrl = env("P2P_EXPLORER_URL")
        ?: if (networkType == org.ergoplatform.appkit.NetworkType.MAINNET) {
            ExplorerChainSource.MAINNET_BASE_URL
        } else {
            ExplorerChainSource.TESTNET_BASE_URL
        }
    val chain = ExplorerChainSource(explorerUrl)

    val vaultSecret = env("P2P_VAULT_SECRET")?.let { BigInteger(it, 16) } ?: BigInteger.valueOf(0x1111)
    val vaultSigner = EmbeddedVaultSigner(
        secret = vaultSecret,
        networkType = networkType,
        changeAddress = SigmaTrees.p2pkAddress(p2pgate.backend.util.Secp256k1.publicKeyCompressed(vaultSecret), networkType),
    )

    val config = BackendConfig(
        operatorKey = env("P2P_OPERATOR_KEY") ?: backend.tryGetString("operator-key"),
        webhookUrl = env("P2P_ESCALATION_WEBHOOK") ?: backend.tryGetString("webhook-url"),
        mixReadyCollateral = env("P2P_MIX_READY")?.toLongOrNull()
            ?: backend.tryGetString("mix-ready")?.toLongOrNull() ?: 0,
        feeBps = env("P2P_FEE_BPS")?.toIntOrNull() ?: backend.tryGetString("fee-bps")?.toIntOrNull() ?: 0,
        collateralTokenIdHex = env("P2P_COLLATERAL_TOKEN_ID")
            ?: backend.tryGetString("collateral-token-id") ?: "",
        courierId = env("P2P_COURIER_ID") ?: backend.tryGetString("courier-id") ?: "courier-dev-1",
        courierPubKeyHex = env("P2P_COURIER_PUBKEY") ?: backend.tryGetString("courier-pubkey") ?: "",
        pollDelayMs = env("P2P_POLL_DELAY_MS")?.toLongOrNull()
            ?: backend.tryGetString("poll-delay-ms")?.toLongOrNull() ?: 30_000,
    )

    val store = InMemoryDealStore()
    val bus = EventBus()
    val engine = DealEngine(store, bus)
    val infra = InfraMonitor(bus)
    val pool = CollateralPool(config.mixReadyCollateral)
    val riskScorer = p2pgate.backend.aml.ConfigRiskScorer()
    val vaultManager = VaultManager(
        trees = trees,
        treasuryTree = treasuryTree,
        signer = vaultSigner,
        chain = chain,
        submitter = NoOpTxSubmitter(),
        engine = engine,
        store = store,
        bus = bus,
        infra = infra,
        oracle = oracle,
        riskScorer = riskScorer,
        feeBps = config.feeBps,
        reclaimTimeoutBlocks = config.reclaimTimeoutBlocks,
    )
    val watcher = ChainWatcher(chain, trees, engine, store)
    val quotes = QuotePublisher(
        store = store,
        infra = infra,
        freeCollateral = { pool.free(store) },
        bus = bus,
        protocolFeeBps = config.protocolFeeBps,
        costFloorBps = config.costFloorBps,
        ttl = config.quoteTtl,
    )
    val escalation = config.webhookUrl?.let { url ->
        WebhookEscalation(url) { target, body ->
            java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create(target))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                java.net.http.HttpResponse.BodyHandlers.discarding(),
            )
        }
    } ?: p2pgate.backend.disputes.NoOpEscalation
    val inbox = DisputeInbox(store, vaultManager, oracle, escalation, bus, config.escalationLeadTime)

    val app = BackendApp(
        store = store, bus = bus, engine = engine, watcher = watcher, vaultManager = vaultManager,
        quotes = quotes, oracle = oracle, riskScorer = riskScorer, infra = infra, inbox = inbox,
        tokens = TokenService(), pool = pool, trees = trees, chain = chain, config = config,
    )

    module(app)

    // Scheduler loops (the manual tick() methods stay thread-free for tests).
    var lastHeight = -1
    var staleTicks = 0
    launch {
        while (isActive) {
            delay(config.pollDelayMs)
            runCatching {
                app.tick()
                app.pollOracle()
                val height = chain.getCurrentHeight()
                if (height == lastHeight) staleTicks++ else { staleTicks = 0; lastHeight = height }
                infra.report(InfraSignal.EXPLORER_SYNC, staleTicks < 10, "tip height $height")
            }.onFailure { log.warn("scheduler tick failed: ${it.message}") }
        }
    }
}
