package p2pgate.app.deals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import p2pgate.app.data.DealDataController
import p2pgate.app.data.DealSnapshot
import p2pgate.dealprotocol.DealState

/** One row of the deal list: a snapshot plus its canonical state, if it parses. */
data class DealListItem(
    val snapshot: DealSnapshot,
    val canonicalState: DealState?,
    /** The deal closed and the buyer has nothing left to do here. */
    val finished: Boolean,
)

/**
 * "Your deals" (`specs/android-app.md` §5): the local store is the only index —
 * the app holds no account, so this is what stands in for one. Without it a
 * buyer whose app was killed mid-deal has no route back into the deal except a
 * pasted recovery link, and the deal token lives only here.
 */
data class DealListUiState(
    val items: List<DealListItem> = emptyList(),
    val loading: Boolean = true,
    val lastDeletedId: String? = null,
)

class DealListViewModel(private val data: DealDataController) : ViewModel() {

    private val _uiState = MutableStateFlow(DealListUiState())
    val uiState: StateFlow<DealListUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val items = data.repository.all()
                .map { DealListItem(it, it.canonicalState, it.terminal) }
                // Open deals first, then most recently touched.
                .sortedWith(compareBy({ it.finished }, { -it.snapshot.updatedAtEpochMs }))
            _uiState.value = _uiState.value.copy(items = items, loading = false)
        }
    }

    /** Remove one deal from the device. The deal key dies with it (nothing else holds it). */
    fun delete(dealId: String) {
        viewModelScope.launch {
            data.delete(dealId)
            _uiState.value = _uiState.value.copy(lastDeletedId = dealId)
            refresh()
        }
    }
}

/** The canonical state, or null when the snapshot's string does not parse. */
private val DealSnapshot.canonicalState: DealState?
    get() = runCatching { DealState.valueOf(state) }.getOrNull()
