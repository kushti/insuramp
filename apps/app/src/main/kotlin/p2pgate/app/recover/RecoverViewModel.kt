package p2pgate.app.recover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import p2pgate.app.data.DealLinkParser
import p2pgate.app.data.DealRepository

/**
 * Recovery failure, surfaced as a localized message by the screen. [InvalidLink]
 * is the parse failure (no deal id / no # token); [Failed] wraps a sync error
 * whose technical detail is shown untranslated.
 */
sealed interface RecoverError {
    data object InvalidLink : RecoverError
    data class Failed(val detail: String?) : RecoverError
}

/** Deal recovery by link token (`specs/android-app.md` §6.3): paste → restore. */
data class RecoverUiState(
    val input: String = "",
    val recovering: Boolean = false,
    val recoveredDealId: String? = null,
    val error: RecoverError? = null,
)

class RecoverViewModel(private val repository: DealRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(RecoverUiState())
    val uiState: StateFlow<RecoverUiState> = _uiState.asStateFlow()

    fun onInputChange(value: String) {
        _uiState.value = _uiState.value.copy(input = value, error = null)
    }

    fun recover() {
        val input = _uiState.value.input
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(recovering = true, error = null)
            try {
                val link = DealLinkParser.parse(input)
                val snapshot = repository.recover(link)
                _uiState.value = _uiState.value.copy(
                    recovering = false,
                    recoveredDealId = snapshot.dealId,
                )
            } catch (e: IllegalArgumentException) {
                _uiState.value = _uiState.value.copy(recovering = false, error = RecoverError.InvalidLink)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(recovering = false, error = RecoverError.Failed(e.message))
            }
        }
    }
}
