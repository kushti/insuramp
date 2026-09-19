package p2pgate.app.handoff

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import p2pgate.app.R
import p2pgate.app.data.DealRepository
import p2pgate.app.net.BackendClient
import p2pgate.app.net.HandoffSubmitRequest
import p2pgate.app.verify.HandoffRecordVerifier
import p2pgate.app.verify.Hex
import p2pgate.dealprotocol.HandoffRecord
import p2pgate.dealprotocol.QrPayload
import java.time.Instant

/** The meeting-gate outcome — the "safe to leave" decision is ONLY [Verified]. */
sealed interface GateOutcome {
    data object Pending : GateOutcome
    data object Verified : GateOutcome
    /** Record or signature rejected (decode/binding/freshness/signature). */
    data class Rejected(@StringRes val reasonRes: Int) : GateOutcome
    /** Seller key not present in the deal data — cannot verify, stay put. */
    data object MissingSellerKey : GateOutcome
}

data class HandoffUiState(
    val record: HandoffRecord? = null,
    val recordHex: String? = null,
    val sigAHex: String? = null,
    val sigZHex: String? = null,
    val gate: GateOutcome = GateOutcome.Pending,
    val persisted: Boolean = false,
    val uploaded: Boolean = false,
    val uploadError: String? = null,
) {
    val safeToLeave: Boolean get() = gate is GateOutcome.Verified && persisted && uploaded
}

/**
 * The meeting screen's ViewModel (`onramp-ux.md` §2.3 — the one hard moment).
 *
 * The scanned QR carries the 52-byte record (`p2pgate://handoff?m=...`, pinned
 * by `specs/deal-protocol.md` §3.3 — the signature never rides in it), so after
 * the seller signs, the buyer obtains the two signature halves `(a, z)` from
 * the seller's screen and enters them here (scan or paste). The gate:
 * decode → deal binding (dealId == operator-issued id) → amount/currency vs
 * the deal → freshness/clock-skew → Schnorr under the seller key — then
 * persist, upload (duplicate upload = recognized 2xx no-op), and only then
 * "safe to leave". Everything else says plainly: leaving means no verified
 * dispute record.
 */
class HandoffViewModel(
    private val dealId: String,
    private val repository: DealRepository,
    private val backend: BackendClient,
    private val clock: () -> Instant = Instant::now,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HandoffUiState())
    val uiState: StateFlow<HandoffUiState> = _uiState.asStateFlow()

    /** Step 1: the seller's QR, decoded via the :core:dealprotocol codec. */
    fun onQrScanned(payload: String) {
        try {
            val record = QrPayload.decodeHandoff(payload)
            _uiState.value = _uiState.value.copy(
                record = record,
                recordHex = Hex.encode(record.encode()),
                gate = GateOutcome.Pending,
            )
        } catch (e: IllegalArgumentException) {
            _uiState.value = _uiState.value.copy(
                record = null, recordHex = null,
                gate = GateOutcome.Rejected(R.string.handoff_reject_not_handoff_qr),
            )
        }
        reevaluate()
    }

    /** Step 2: the seller's signature halves, as hex from the seller's screen. */
    fun onSignature(aHex: String, zHex: String) {
        _uiState.value = _uiState.value.copy(sigAHex = aHex.trim(), sigZHex = zHex.trim())
        reevaluate()
    }

    fun reevaluate() {
        val s = _uiState.value
        val record = s.record ?: return
        if (s.sigAHex.isNullOrEmpty() || s.sigZHex.isNullOrEmpty()) {
            _uiState.value = s.copy(gate = GateOutcome.Pending)
            return
        }
        viewModelScope.launch {
            val snapshot = repository.get(dealId) ?: return@launch
            val sellerKey = snapshot.sellerPubKeyHex
            if (sellerKey == null) {
                _uiState.value = _uiState.value.copy(gate = GateOutcome.MissingSellerKey)
                return@launch
            }
            val ok = try {
                HandoffRecordVerifier.verify(
                    recordBytes = record.encode(),
                    a = Hex.decode(s.sigAHex!!),
                    z = Hex.decode(s.sigZHex!!),
                    sellerPubKey = Hex.decode(sellerKey),
                    expectedDealId = Hex.decode(snapshot.dealId),
                    expectedFiatAmount = snapshot.fiatAmount,
                    expectedFiatCurrency = snapshot.fiatCurrency.toByteArray(Charsets.US_ASCII),
                    now = clock(),
                )
            } catch (e: IllegalArgumentException) {
                false
            }
            if (ok) {
                // Verified → persist FIRST (the absolute rule), then upload.
                repository.markVerifiedRecord(dealId, s.recordHex!!, s.sigAHex!!, s.sigZHex!!)
                _uiState.value = _uiState.value.copy(gate = GateOutcome.Verified, persisted = true)
                upload()
            } else {
                _uiState.value = _uiState.value.copy(
                    gate = GateOutcome.Rejected(R.string.handoff_reject_invalid),
                )
            }
        }
    }

    /** Relay to the backend — the deal engine's CashCollected evidence. */
    fun upload() {
        val s = _uiState.value
        val recordHex = s.recordHex ?: return
        viewModelScope.launch {
            val snapshot = repository.get(dealId) ?: return@launch
            try {
                backend.submitHandoff(dealId, snapshot.token, HandoffSubmitRequest(recordHex))
                // 2xx — including the recognized duplicate no-op.
                _uiState.value = _uiState.value.copy(uploaded = true, uploadError = null)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(uploaded = false, uploadError = e.message)
            }
        }
    }
}
