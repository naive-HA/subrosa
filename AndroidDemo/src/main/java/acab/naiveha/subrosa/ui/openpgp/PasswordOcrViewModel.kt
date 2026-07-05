package acab.naiveha.subrosa.ui.openpgp

import android.graphics.Bitmap
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class PasswordOcrViewModel : ViewModel() {

    private val _importedBitmap = MutableLiveData<Bitmap?>()
    val importedBitmap: LiveData<Bitmap?> = _importedBitmap

    private val _ocrState = MutableLiveData<OcrUiState>(OcrUiState.AwaitingSelection)
    val ocrState: LiveData<OcrUiState> = _ocrState

    var pendingAction: (() -> Unit)? = null

    fun setImportedBitmap(bitmap: Bitmap?) {
        _importedBitmap.value = bitmap
        _ocrState.value = OcrUiState.AwaitingSelection
    }

    fun setState(state: OcrUiState) {
        _ocrState.value = state
    }

    sealed class OcrUiState {
        object AwaitingSelection : OcrUiState()
        object Recognizing : OcrUiState()
        data class Recognized(val text: String, val confidence: Float) : OcrUiState()
        data class Error(val message: String) : OcrUiState()
    }

}
