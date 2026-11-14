package acab.naiveha.subrosa.ui.nitrokey3updater

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class FlashRequest(
    val device: BootloaderDevice,
    val image: FirmwareImage,
)

internal object FlashSession {

    private val lock = Any()
    private val mutableState = MutableStateFlow<FlashState>(FlashState.Idle)

    private var pending: FlashRequest? = null
    private var lastPublished: FlashProgress? = null

    val state: StateFlow<FlashState> = mutableState.asStateFlow()

    val isRunning: Boolean get() = mutableState.value is FlashState.Running

    fun begin(request: FlashRequest): Boolean = synchronized(lock) {
        if (mutableState.value is FlashState.Running) {
            false
        } else {
            pending = request
            lastPublished = null
            mutableState.value = FlashState.Running(FlashProgress(FlashPhase.CONNECTING))
            true
        }
    }

    fun takeRequest(): FlashRequest? = synchronized(lock) {
        val request = pending
        pending = null
        request
    }

    fun publishProgress(progress: FlashProgress) {
        synchronized(lock) {
            if (mutableState.value !is FlashState.Running) return
            val last = lastPublished
            if (last != null && last.phase == progress.phase && last.percent == progress.percent) return
            lastPublished = progress
            mutableState.value = FlashState.Running(progress)
        }
    }

    fun lastProgress(): FlashProgress? = synchronized(lock) { lastPublished }

    fun finish(outcome: FlashState) {
        synchronized(lock) {
            pending = null
            lastPublished = null
            mutableState.value = outcome
        }
    }

    fun failIfUnclaimed(request: FlashRequest, outcome: FlashState.Failed): Boolean = synchronized(lock) {
        if (pending === request) {
            pending = null
            lastPublished = null
            mutableState.value = outcome
            true
        } else {
            false
        }
    }

    fun abandon(request: FlashRequest) {
        synchronized(lock) {
            if (pending === request) {
                pending = null
                lastPublished = null
                mutableState.value = FlashState.Idle
            }
        }
    }

    fun clearResult() {
        synchronized(lock) {
            val current = mutableState.value
            if (current is FlashState.Succeeded || current is FlashState.Failed) {
                mutableState.value = FlashState.Idle
            }
        }
    }
}
