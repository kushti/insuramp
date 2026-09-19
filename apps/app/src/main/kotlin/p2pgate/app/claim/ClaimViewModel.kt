package p2pgate.app.claim

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import p2pgate.app.data.DealRepository
import p2pgate.app.net.BackendClient
import p2pgate.app.net.ClaimGuideDto
import p2pgate.dealprotocol.DealState

/** The calm, first-class dispute path (`onramp-ux.md` §2.2, `specs/android-app.md` §3.4). */
data class ClaimUiState(
    val dealState: String = "",
    val maturesAtEpochMs: Long? = null,
    val contested: Boolean = false,
    val claimable: Boolean = false,
    val loading: Boolean = false,
    val guide: ClaimGuideDto? = null,
    val error: String? = null,
)

class ClaimViewModel(
    private val dealId: String,
    private val repository: DealRepository,
    private val backend: BackendClient,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ClaimUiState())
    val uiState: StateFlow<ClaimUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val snapshot = repository.refresh(dealId)
            val state = DealState.valueOf(snapshot.state)
            _uiState.value = ClaimUiState(
                dealState = snapshot.state,
                maturesAtEpochMs = snapshot.claimMaturesAtEpochMs,
                contested = snapshot.contested,
                claimable = state == DealState.PAYMENT_PENDING || state == DealState.PAYMENT_CONFIRMED,
            )
        }
    }

    /** POST /v1/deals/{id}/claim — the backend hands back the claim guide. */
    fun startClaim() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true, error = null)
            try {
                val snapshot = repository.get(dealId) ?: return@launch
                val guide = backend.claim(dealId, snapshot.token)
                _uiState.value = _uiState.value.copy(loading = false, guide = guide)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(loading = false, error = e.message)
            }
        }
    }
}
