package acab.naiveha.subrosa.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

class StatusChannel {
    private val _value = MutableLiveData("")
    val value: LiveData<String> = _value

    private var pinned: String? = null

    fun post(message: String) {
        synchronized(this) {
            _value.postValue(if (message.isBlank()) pinned ?: message else message)
        }
    }

    fun pin(message: String) {
        require(message.isNotBlank()) { "A pinned notice must not be blank" }
        synchronized(this) {
            pinned = message
            _value.postValue(message)
        }
    }

    fun unpin() {
        synchronized(this) {
            if (pinned != null) {
                pinned = null
                _value.postValue("")
            }
        }
    }
}
