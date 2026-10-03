package p2pgate.tui.buyer

import java.io.File
import java.math.BigInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.ergoplatform.appkit.NetworkType
import org.ergoplatform.appkit.SignedTransaction
import p2pgate.dealprotocol.FiatAmounts
import p2pgate.dealprotocol.HandoffRecord
import p2pgate.dealprotocol.TronAddress
import p2pgate.ergo.ChainBox
import p2pgate.ergo.ChainSource
import p2pgate.ergo.ClaimTxBuilder
import p2pgate.ergo.DealTxSigner
import p2pgate.ergo.DevOracle
import p2pgate.ergo.SchnorrVerifier
import p2pgate.tui.common.BackendClient
import p2pgate.tui.common.Format
import p2pgate.tui.common.wire.CreateDealRequest
import p2pgate.tui.common.wire.HandoffSubmitRequest

/** Broadcast seam, mirroring the backend's `TxSubmitter` — a fake in tests. */
fun interface TxSubmitter {
    fun submit(tx: SignedTransaction): String
}

/**
 * The buyer console's behaviour. The screen is a pure function of [state]; every
 * effect goes through an injected seam (`BackendClient`, `ChainSource`,
 * `TxSubmitter`, [KeyVault]) so the whole flow is testable without a server, a
 * node, or a terminal.
 *
 * **Trust posture.** The vault box is read from the *chain*, not from the
 * backend's copy of it, and the seller's public key comes from that box's R5.
 * A backend that lied about either would be ignored — which is what
 * `specs/README.md`'s open backlog says the Android app still gets wrong
 * ("`sellerPubKey` is server-supplied and never bound to the chain"). The
 * backend remains a convenience: it knows the deal token, the fiat terms and the
 * operator's intent.
 */
class BuyerController(
    private val client: BackendClient,
    private val chain: ChainSource,
    private val submitter: TxSubmitter,
    private val txBuilder: ClaimTxBuilder,
    private val keyFile: File,
    private val handoffFile: File,
    private val networkType: NetworkType,
    /**
     * The deal key, already unlocked for this session, or `null` for a keyless
     * console.
     *
     * Unlocking happens **once, before Mosaic starts** (see `Main`), because
     * Mosaic owns stdin: a passphrase prompt issued from inside the event loop
     * renders its prompt and then never sees the keystrokes. Holding the secret
     * for the session is the standard unlocked-wallet exposure model, and the
     * file at rest stays encrypted either way.
     */
    private val sessionSecret: ByteArray? = null,
    /** The buyer's TRON USDT address (`P2P_PAYOUT_ADDRESS`), or `null` if unset. */
    private val payoutAddress: String? = null,
) {

    private val _state = MutableStateFlow(BuyerState())
    val state: StateFlow<BuyerState> = _state.asStateFlow()

    private val handoffs = HandoffStore(handoffFile)

    /** Polls the backend and the chain; the console has no other timer. */
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /** The deal token the backend minted — kept in memory only, never written. */
    internal var dealToken: String? = null

    /**
     * The deal this console is following, remembered independently of [state] so a
     * refresh works before the first successful load. [BuyerState.deal] is the
     * rendered copy; this is the id we ask the backend about.
     */
    private var attachedDealId: String? = null

    init {
        _state.update {
            it.copy(
                key = keyStatus(),
                handoff = handoffs.read(),
                payoutAddress = payoutAddress,
                status = when {
                    payoutAddress == null ->
                        "no USDT payout address — set P2P_PAYOUT_ADDRESS (a TRON 'T…' address)"
                    !TronAddress.isValid(payoutAddress) ->
                        "P2P_PAYOUT_ADDRESS is not a valid TRON address"
                    else -> "ready — pick a quote, or press r to refresh"
                },
            )
        }
    }

    // ------------------------------------------------------------------ reads

    suspend fun refreshQuotes() = guarded {
        val feed = client.quotes()
        val at = nowMs()
        _state.update {
            it.copy(
                quotes = feed.quotes,
                lastSyncedAt = at,
                // The poll reports only when no action is mid-sentence; an action's
                // outcome outranks a count of quotes.
                status = if (it.statusPinned(at, actionStatusMs)) it.status
                else "${feed.quotes.size} quotes",
            )
        }
    }

    /**
     * Refreshes the deal and the chain facts around it. The chain read is what
     * makes the seller's key trustworthy, so it is not optional.
     */
    suspend fun refreshDeal() = guarded {
        val id = attachedDealId
        val token = dealToken
        if (id == null || token == null) {
            say("no deal yet — pick a quote and press Enter to take it")
            return@guarded
        }
        val deal = client.deal(id, token)
        val height = chain.getCurrentHeight()
        val vaultBox = deal.vaultBoxId?.let { chain.getBox(it) }
        val provenBox = findProofBox(vaultBox)
        _state.update { current ->
            current.copy(
                deal = deal,
                chainHeight = height,
                vaultSellerPubKey = vaultBox?.registerBytes(5)?.toHex(),
                proofHeight = provenBox?.registerLong(7)?.toInt(),
                claimGuide = runCatching { client.claimGuide(id, token) }.getOrNull(),
                attestation = runCatching { client.attestation(id, token) }.getOrNull(),
                lastSyncedAt = nowMs(),
                // Only speak when nothing action-shaped is pending.
                status = if (current.statusPinned(nowMs(), actionStatusMs)) current.status
                else "${deal.state} · chain ${height}",
            )
        }
    }

    /**
     * The PAYMENT_PROVEN box for this deal. R7 = `proofHeight`, R4 = `dealId`;
     * R8 (the record id) is absent on a FUNDED box, so R7 plus the deal id is
     * what distinguishes the two.
     */
    private fun findProofBox(vaultBox: ChainBox?): ChainBox? {
        val dealId = _state.value.deal?.dealId?.hexToBytes() ?: return null
        val proven = vaultBox?.takeIf { it.registerLong(7) != null } ?: return null
        return proven.takeIf { it.registerBytes(4)?.contentEquals(dealId) == true }
    }

    // ---------------------------------------------------------------- actions

    suspend fun createDeal(quoteId: String, amount: Long, receiveAddress: String) = guarded {
        val quote = _state.value.quotes.firstOrNull { it.id == quoteId }
            ?: throw IllegalStateException("that quote is no longer on the feed")
        // Publishing the buyer's public key needs the secret, so this is the one
        // place a deal cannot be created keylessly. Say which thing is missing:
        // "write a key file first" is wrong and unhelpful when the file exists and
        // only the passphrase did not arrive.
        val secret = when {
            !KeyVault.exists(keyFile) ->
                throw IllegalStateException("no key file at ${keyFile.path} — press k to create one")
            else -> readSecret()
                ?: throw IllegalStateException(
                    "could not unlock ${keyFile.path} — the passphrase prompt was not answered",
                )
        }
        val publicKey = BuyerKeys.publicKeyCompressed(secret)
        require(amount in quote.minAmount..quote.maxAmount) {
            "amount $amount is outside the quote's range ${quote.minAmount}..${quote.maxAmount}"
        }
        // The cash leg is derived from the quote's rate, and the backend
        // re-derives it and rejects a mismatch — so a console cannot quote the
        // buyer a cash figure the operator does not agree to.
        val fiat = FiatAmounts.cashFor(amount, quote.fiatPerUsdtMicros)
        val created = client.createDeal(
            CreateDealRequest(
                quoteId = quote.id,
                amount = amount,
                receiveAddress = receiveAddress,
                buyerPubKey = publicKey.toHex(),
                fiatCurrency = quote.fiatCurrency,
                fiatAmount = fiat,
            ),
        )
        dealToken = created.dealToken
        attachedDealId = created.dealId
        _state.update {
            it.copy(
                deal = null,
                status = "deal ${Format.shortId(created.dealId)} created — the seller has your offer",
                events = listOf("created ${Format.shortId(created.dealId)}"),
            )
        }
        refreshDeal()
    }

    /** Writes a key file for the current deal. Refuses once the deal is funded. */
    fun createKey(passphrase: CharArray, overwrite: Boolean = false) {
        val facts = _state.value.facts
        BuyerFlow.blockedReason(BuyerFlow.Action.CREATE_KEY, facts)?.let { say(it); return }
        val secret = BuyerKeys.generateSecret()
        val address = BuyerKeys.address(secret, networkType)
        try {
            KeyVault.write(
                file = keyFile,
                passphrase = passphrase,
                secret = secret,
                address = address,
                dealId = _state.value.deal?.dealId,
                overwrite = overwrite,
            )
            KeyVault.writePublicInfo(keyFile, address, fingerprintOf(secret))
            say("wrote a key file for $address — ${fingerprintOf(secret)}")
            _state.update { it.copy(key = keyStatus(address = address, fingerprint = fingerprintOf(secret))) }
        } catch (e: KeyVault.UnlockFailed) {
            say(e.message ?: "could not write the key file")
        } finally {
            secret.fill(0)
        }
    }

    /**
     * Captures and verifies a handoff payload from the meeting, then stores it.
     *
     * Verification binds the record to the vault's R5 **read from the chain**, so
     * "safe to leave" means the signature checks against the key the contract
     * itself pins — not against whatever the backend says the seller key is.
     */
    fun captureHandoff(qrPayload: String, signatureA: String, signatureZ: String) {
        val stored = try {
            splitHandoffPayload(qrPayload, signatureA, signatureZ)
        } catch (e: IllegalArgumentException) {
            say(e.message ?: "that is not a usable handoff record"); return
        }
        val box = _state.value.deal?.vaultBoxId?.let { chain.getBox(it) }
        val sellerPubKey = box?.registerBytes(5)
        if (sellerPubKey == null) {
            say("no vault box observed yet — refresh before verifying, the seller key comes from the chain")
            return
        }
        val recordBytes = stored.recordHex.hexToBytes()
        val vaultDealId = box.registerBytes(4)
        val recordDealId = runCatching {
            HandoffRecord.decode(recordBytes).dealId
        }.getOrNull()
        if (vaultDealId == null || recordDealId == null || !vaultDealId.contentEquals(recordDealId)) {
            say("that record is for a different deal — do not accept it")
            return
        }
        // Two chain-pinned checks, both necessary:
        //   R4 — the record is for *this* vault, so it cannot be replayed from
        //        another deal;
        //   R5 — the signature verifies under the seller key the contract itself
        //        pins, so a backend that substituted its own key (and a matching
        //        signature) is ignored. This is the check the Android app cannot
        //        make today (`specs/README.md` open backlog).
        //
        // The fiat amount is deliberately NOT checked here: it is not on-chain,
        // so verifying it would only compare two backend-supplied values and
        // prove nothing. The deal terms are hashed into the deal id, which R4
        // pins, so the amount is bound indirectly rather than checked directly.
        val verified = runCatching {
            SchnorrVerifier.verify(recordBytes, stored.signatureA.hexToBytes(), stored.signatureZ.hexToBytes(), sellerPubKey)
        }.getOrDefault(false)
        if (!verified) {
            say("signature does not verify against the vault's seller key — do not leave with the cash")
            return
        }
        val fresh = stored.capturedAtEpochMs.let { it == 0L || System.currentTimeMillis() - it <= FRESHNESS_MS }
        if (!fresh) {
            say("that record was captured over ${FRESHNESS_MS / 3_600_000}h ago — ask the seller for a fresh one")
            return
        }
        handoffs.write(stored)
        _state.update {
            it.copy(handoff = stored, status = "handoff record verified — it is safe to leave")
        }
    }

    /** Uploads the captured record to the backend, so it can drive the state machine. */
    suspend fun uploadHandoff() = guarded {
        val action = BuyerFlow.Action.UPLOAD_HANDOFF
        _state.value.facts.let { facts ->
            BuyerFlow.blockedReason(action, facts)?.let { say(it); return@guarded }
        }
        val id = _state.value.deal?.dealId ?: return@guarded say("no deal")
        val token = dealToken ?: return@guarded say("no deal token")
        val stored = _state.value.handoff ?: return@guarded say("no handoff record captured")
        client.submitHandoff(id, token, HandoffSubmitRequest(recordHex = stored.recordHex))
        say("handoff record uploaded — the deal is now waiting on the seller's USDT")
    }

    /**
     * Builds, signs and broadcasts the claim transaction.
     *
     * [payout] selects the path: `false` is path B (claim-open, `sigmaProp`-only,
     * no key read), `true` is path D (the payout, which needs the deal key). The
     * key is read *after* the path is chosen and validated, so a keyless claim
     * never prompts for a passphrase.
     */
    suspend fun buildAndBroadcastClaim(payout: Boolean) = guarded {
        val state = _state.value
        val deal = state.deal ?: return@guarded say("no deal")
        val token = dealToken ?: return@guarded say("no deal token")
        val stored = state.handoff ?: return@guarded say("no handoff record captured")
        val boxId = deal.vaultBoxId ?: return@guarded say("the backend has not reported a vault box")
        val box = chain.getBox(boxId) ?: return@guarded say("vault box $boxId not found on chain")

        val height = chain.getCurrentHeight()
        val changeAddress = state.key.address ?: BuyerKeys.address(
            requireNotNull(readSecret()) { "no key file — the payout needs the deal key" },
            networkType,
        )
        val signer = signerFor(payout, changeAddress)

        val signed = if (payout) {
            val proofHeight = state.proofHeight
                ?: return@guarded say("no PAYMENT_PROVEN box observed — the claim-open has not landed")
            val remaining = BuyerFlow.blocksRemaining(proofHeight, height, BuyerState.CLAIM_MATURATION_BLOCKS)
            if (remaining != 0) {
                return@guarded say("the claim has not matured — $remaining blocks to go")
            }
            txBuilder.buildClaimPayout(
                provenBox = box,
                feeInputs = feeInputs(changeAddress),
                currentHeight = height,
                buyerPayoutAddress = changeAddress,
                changeAddress = changeAddress,
                signer = signer,
            )
        } else {
            txBuilder.buildClaimOpen(
                fundedBox = box,
                feeInputs = feeInputs(changeAddress),
                record = HandoffRecord.decode(stored.recordHex.hexToBytes()),
                a = stored.signatureA.hexToBytes(),
                z = stored.signatureZ.hexToBytes(),
                currentHeight = height,
                txTimestampMs = System.currentTimeMillis(),
                changeAddress = changeAddress,
                signer = signer,
            )
        }
        val txId = submitter.submit(signed)
        say(
            if (payout) "payout broadcast — ${Format.shortId(txId)}"
            else "claim opened and broadcast — ${Format.shortId(txId)}",
        )
        _state.update { it.copy(events = (listOf("${if (payout) "payout" else "claim-open"} ${Format.shortId(txId)}") + it.events).take(8)) }
    }

    /**
     * Reattaches this console to a deal it is already following — after a restart,
     * or when a second console picks up the same deal. [dealToken] is the
     * backend's per-deal bearer token; it is held in memory only, which means a
     * restart loses it and the deal has to be re-attached. (Persisting it is
     * deliberately not done here: a deal token on disk is a credential, and
     * `specs/README.md` already flags the Android app's plaintext token storage
     * as an open item.)
     */
    suspend fun attachTo(dealId: String, dealToken: String) = guarded {
        this.dealToken = dealToken
        attachedDealId = dealId
        _state.update { it.copy(status = "attaching to ${Format.shortId(dealId)}…") }
        // A token that does not match this deal raises, and the status line says so.
        refreshDeal()
    }

    fun forgetDeal() {
        dealToken = null
        attachedDealId = null
        _state.update {
            it.copy(deal = null, claimGuide = null, attestation = null, proofHeight = null, status = "deal forgotten")
        }
    }

    /** Drops the stored handoff record — the buyer asked to give up the evidence. */
    fun forgetHandoff() {
        handoffs.delete()
        _state.update { it.copy(handoff = null, status = "handoff record deleted") }
    }

    // ------------------------------------------------------- test seams
    //
    // Package-internal, and named as the debug hooks they are. The screen tests
    // drive the real controller (so the real state assembly is covered) and need
    // to place it in a state that otherwise needs a signature or a file.

    /** Seeds a captured handoff without running verification. */
    internal fun debugStoreHandoff(stored: StoredHandoff) {
        _state.update { it.copy(handoff = stored) }
    }

    /** Seeds the USDT payout address (a TRON address). */
    internal fun debugPayoutAddress(address: String?) = _state.update { it.copy(payoutAddress = address) }

    /** Seeds the key status line. */
    internal fun debugNoteKey(path: String, address: String?, fingerprint: String?) {
        _state.update {
            it.copy(key = KeyStatus(path = path, present = address != null, fingerprint = fingerprint, address = address))
        }
    }

    // ------------------------------------------------------------- internals

    /**
     * The signer for the chosen path. Path B carries no secret, so it signs with
     * an empty prover — the transaction is valid without the buyer's key, which
     * is the property that lets a claim be opened with nothing on disk. Path D
     * reads the key file, and prompts only now.
     */
    private fun signerFor(payout: Boolean, changeAddress: String): DealTxSigner {
        val secrets: List<BigInteger> = if (payout) {
            listOf(BuyerKeys.secretScalar(requireNotNull(readSecret()) { "no key file" }))
        } else {
            emptyList()
        }
        return DealTxSigner { tx ->
            org.ergoplatform.appkit.ColdErgoClient(networkType, DevOracle.coldParameters(networkType))
                .execute { ctx ->
                    val builder = ctx.newProverBuilder()
                    secrets.forEach { builder.withDLogSecret(it) }
                    builder.build().sign(tx)
                }
        }
    }

    /** Fee inputs from the buyer's own address, or an empty list if none are funded. */
    private fun feeInputs(address: String): List<ChainBox> = chain.getUnspentBoxes(address)

    /**
     * The deal key, or `null` when it cannot be read right now. [why] receives the
     * reason so callers can report something true instead of guessing.
     */
    private fun readSecret(why: (String) -> Unit = {}): ByteArray? {
        sessionSecret?.let { return it.copyOf() }
        if (!KeyVault.exists(keyFile)) return null
        // No prompting here: Mosaic owns stdin, so a prompt would render and then
        // hang. `Main` unlocks before the terminal loop and passes the secret in.
        why("the key file is locked — restart the console to unlock it")
        return null
    }

    /**
     * The key status line. Reads the public sidecar rather than unlocking the
     * file: a restarted console must know its own address to publish it as
     * `receiveAddress`, and that must not cost a passphrase prompt. Without the
     * sidecar the console knows a key exists but not what it is — reported as
     * such, rather than pretending it has no key.
     */
    private fun keyStatus(
        address: String? = null,
        fingerprint: String? = null,
    ): KeyStatus {
        val present = KeyVault.exists(keyFile)
        val info = if (present) KeyVault.readPublicInfo(keyFile) else null
        return KeyStatus(
            path = keyFile.path,
            present = present,
            fingerprint = fingerprint ?: info?.fingerprint,
            address = address ?: info?.address,
        )
    }

    // ------------------------------------------------- interactive entry points
    //
    // Mosaic owns the screen, so a passphrase prompt cannot be a widget. These
    // read from the system console (or `P2P_KEY_PASSPHRASE`) before the terminal
    // loop starts -- see `readPassphrase`. They are the only places the console
    // touches the key file's secret outside a tx build.

    /** First fetch, then the recurring poll of the backend view and chain facts. */
    fun start() {
        scope.launch {
            while (true) {
                refreshAll()
                delay(POLL_MS)
            }
        }
    }

    suspend fun refreshAll() {
        refreshQuotes()
        refreshDeal()
    }

    /** Moves the quote cursor, clamped to the feed. */
    fun selectQuote(index: Int) = _state.update {
        if (it.quotes.isEmpty()) it else it.copy(selectedQuote = index.coerceIn(0, it.quotes.lastIndex))
    }

    /** Writes a key file, prompting for the passphrase. */
    fun createKeyInteractively(overwrite: Boolean = false) {
        val passphrase = runCatching { readPassphrase("Passphrase for the new key file: ") }
            .getOrElse { say(it.message ?: "could not read a passphrase"); return }
        createKey(passphrase, overwrite)
        passphrase.fill('\u0000')
    }

    /**
     * Verifies and stores a handoff record pasted at the meeting. Verification
     * reads the vault box from the chain -- see [captureHandoff].
     */
    fun captureHandoffInteractively(draft: HandoffDraft) {
        if (!draft.complete) {
            say(
                "incomplete: payload ${if (draft.payload.isBlank()) "missing" else "ok"}, " +
                    "a is ${draft.a.length}/66 chars, z is ${draft.z.length}/64 chars",
            )
            return
        }
        captureHandoff(draft.payload, draft.a, draft.z)
    }

    /**
     * Takes the highlighted quote: creates the deal at its **minimum** size with
     * the key file's own address as the payout address.
     *
     * The minimum is deliberate rather than lazy. Amount entry needs a text field,
     * and Mosaic has no modal input yet; taking the smallest deal the seller
     * offers exercises the whole path with no risk of committing the buyer to a
     * size they cannot see. The cash leg is derived from the quote's rate by
     * [FiatAmounts] and the backend re-derives and checks it, so a mismatch is
     * refused rather than silently accepted.
     */
    suspend fun takeSelectedQuote() {
        val state = _state.value
        val quote = state.quotes.getOrNull(state.selectedQuote)
            ?: return say("no quote selected")
        // The USDT leg is paid to a TRON address. The Ergo key is a different key on
        // a different chain; sending it here is what this console got wrong first.
        val payout = state.payoutAddress
            ?: return say("no USDT payout address — set P2P_PAYOUT_ADDRESS (a TRON 'T…' address)")
        if (!TronAddress.isValid(payout)) {
            return say("P2P_PAYOUT_ADDRESS is not a valid TRON address")
        }
        if (state.key.address == null) return say("no key file — press k to create one")
        createDeal(quote.id, quote.minAmount, payout)
    }

    /** The claim actions, as the keys the screen binds them to. */
    suspend fun openClaim() = buildAndBroadcastClaim(payout = false)

    suspend fun takePayout() = buildAndBroadcastClaim(payout = true)

    /**
     * Writes the status line. Everything here is an *action* outcome, so the line
     * is pinned against the 5s poll for [actionStatusMs] — otherwise "created deal
     * abc123" is gone before the operator can read it. Same fix as the seller
     * console's `say`, which is where the bug was found first.
     */
    private fun say(message: String) = _state.update {
        it.copy(status = message, statusFromActionAt = nowMs())
    }

    /** Epoch millis; the clock seam, so the pin is testable. */
    internal var nowMs: () -> Long = { System.currentTimeMillis() }

    /** How long an action's status message survives the poll. */
    internal val actionStatusMs: Long = 8_000L

    /** Runs a suspending action with `busy` set, turning any failure into a status line. */
    private suspend fun guarded(block: suspend () -> Unit) {
        _state.update { it.copy(busy = true) }
        try {
            block()
        } catch (e: CancellationMarker) {
            throw e
        } catch (e: Exception) {
            say(e.message ?: "action failed: ${e.javaClass.simpleName}")
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    /** Marker so [guarded] can tell a cancellation from a failure. */
    private object CancellationMarker : RuntimeException(null, null, false, false)

    private companion object {
        /**
         * How long a captured handoff record stays usable. Mirrors the contract's
         * own `HANDOFF_RECORD_MAX_AGE` freshness window — a record older than this
         * would fail the in-script check in [ClaimTxBuilder.buildClaimOpen], so
         * catching it here saves a doomed broadcast.
         */
        const val FRESHNESS_MS = 10 * 60 * 1000L

        /** How often the console re-reads the backend view and the chain. */
        const val POLL_MS = 5_000L
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

private fun String.hexToBytes(): ByteArray =
    chunked(2).map { it.toInt(16).toByte() }.toByteArray()