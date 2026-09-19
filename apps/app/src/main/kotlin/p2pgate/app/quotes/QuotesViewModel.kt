package p2pgate.app.quotes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import p2pgate.app.net.BackendClient
import p2pgate.app.net.QuoteDto

/** Quote presentation: the list is the default; the map shows seller pins. */
enum class QuoteViewMode { LIST, MAP }

/** Quote discovery state: the operator's active quotes, live via WS. */
data class QuotesUiState(
    val quotes: List<QuoteDto> = emptyList(),
    val refreshing: Boolean = false,
    /** Last fetch failed — show the stale-data marker (§6.6). */
    val stale: Boolean = false,
    val viewMode: QuoteViewMode = QuoteViewMode.LIST,
) {
    val mapModel: QuoteMapModel get() = quoteMapModel(quotes)
}

/** List-view order: best first — lowest ETA at the top, stable on ties. */
fun bestFirst(quotes: List<QuoteDto>): List<QuoteDto> = quotes.sortedBy { it.etaMinutes }

class QuotesViewModel(private val backend: BackendClient) : ViewModel() {

    private val _uiState = MutableStateFlow(QuotesUiState())
    val uiState: StateFlow<QuotesUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { refresh() }
        viewModelScope.launch { watchStream() }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(refreshing = true)
            try {
                val feed = backend.quotes()
                _uiState.value = QuotesUiState(
                    quotes = feed.quotes,
                    refreshing = false,
                    viewMode = _uiState.value.viewMode,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(refreshing = false, stale = true)
            }
        }
    }

    fun setViewMode(mode: QuoteViewMode) {
        _uiState.value = _uiState.value.copy(viewMode = mode)
    }

    private suspend fun watchStream() {
        try {
            backend.quotesStream().collect { refresh() }
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(stale = true)
        }
    }
}
