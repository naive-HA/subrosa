package acab.naiveha.subrosa.ui.openpgp

import android.graphics.Bitmap
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

data class PasswordOcrUiState(
    val bitmap: Bitmap? = null,
    val ocrState: PasswordOcrViewModel.OcrUiState = PasswordOcrViewModel.OcrUiState.AwaitingSelection,
)

class PasswordOcrViewModel : ViewModel() {

    private val _uiState = MutableLiveData(PasswordOcrUiState())
    val uiState: LiveData<PasswordOcrUiState> = _uiState

    fun setImportedBitmap(bitmap: Bitmap?) {
        _uiState.value = _uiState.value?.copy(bitmap = bitmap, ocrState = OcrUiState.AwaitingSelection)
    }

    fun setState(state: OcrUiState) {
        _uiState.value = _uiState.value?.copy(ocrState = state)
    }

    sealed class OcrUiState {
        object AwaitingSelection : OcrUiState()
        object Recognizing : OcrUiState()
        data class Recognized(val text: String, val confidence: Float) : OcrUiState()
        data class Error(val message: String) : OcrUiState()
    }

}
