package p2pgate.app.deal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import p2pgate.app.data.DealRepository
import p2pgate.app.data.DealSnapshot
import p2pgate.app.net.BackendClient
import p2pgate.dealprotocol.DealState

/** UI projection of one deal: the persisted snapshot + timeline + staleness. */
data class DealUiState(
    val snapshot: DealSnapshot,
    val timeline: List<TimelineRow>,
    /** Backend unreachable at last sync — evidence may be stale (§6.6). */
    val stale: Boolean = false,
) {
    val canonicalState: DealState? get() = runCatching { DealState.valueOf(snapshot.state) }.getOrNull()
}

class DealViewModel(
    private val dealId: String,
    private val repository: DealRepository,
    private val backend: BackendClient,
    /** Called when the deal WS drops — the screen schedules the poll fallback. */
    private val onStreamDrop: () -> Unit = {},
) : ViewModel() {

    private val _uiState = MutableStateFlow<DealUiState?>(null)
    val uiState: StateFlow<DealUiState?> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { refresh() }
        viewModelScope.launch { watchStream() }
    }

    suspend fun refresh() {
        try {
            val snapshot = repository.refresh(dealId)
            val state = runCatching { DealState.valueOf(snapshot.state) }.getOrNull()
            _uiState.value = DealUiState(snapshot, state?.let(DealTimeline::rowsFor) ?: emptyList())
        } catch (e: Exception) {
            // Backend down: keep the last state, mark it stale (§6.6).
            _uiState.value = _uiState.value?.copy(stale = true)
        }
    }

    private suspend fun watchStream() {
        val snapshot = repository.get(dealId) ?: return
        try {
            backend.dealStream(dealId, snapshot.token).collect {
                // Any deal event → re-sync from the authoritative GET.
                refresh()
            }
        } catch (e: Exception) {
            onStreamDropSafely()
        }
    }

    private fun onStreamDropSafely() {
        if (_uiState.value?.stale != true) onStreamDrop()
        _uiState.value = _uiState.value?.copy(stale = true)
    }
}
