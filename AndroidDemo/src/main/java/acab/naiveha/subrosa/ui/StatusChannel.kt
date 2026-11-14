package acab.naiveha.subrosa.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class StatusChannel(
    private val scope: CoroutineScope,
    private val expireAfter: Duration = EXPIRE_AFTER,
    private val onExpired: (String) -> Unit = {},
    private val state: MutableLiveData<String> = MutableLiveData(""),
) {
    val value: LiveData<String> = state

    private var pinned: String? = null
    private var generation = 0L
    private var expiry: Job? = null

    fun post(message: String, transient: Boolean = false) {
        synchronized(this) {
            val text = if (message.isBlank()) pinned ?: message else message
            show(text, expires = transient && text.isNotBlank() && text != pinned)
        }
    }

    fun pin(message: String) {
        require(message.isNotBlank()) { "A pinned notice must not be blank" }
        synchronized(this) {
            pinned = message
            show(message, expires = false)
        }
    }

    fun unpin() {
        synchronized(this) {
            if (pinned != null) {
                pinned = null
                show("", expires = false)
            }
        }
    }

    private fun show(text: String, expires: Boolean) {
        expiry?.cancel()
        expiry = null
        val mine = ++generation
        state.postValue(text)
        if (expires) {
            expiry = scope.launch {
                delay(expireAfter)
                expire(mine, text)
            }
        }
    }

    private fun expire(mine: Long, text: String) {
        synchronized(this) {
            if (mine != generation) return
            expiry = null
            state.postValue(pinned ?: "")
        }
        onExpired(text)
    }

    companion object {
        val EXPIRE_AFTER: Duration = 2.seconds
    }
}
