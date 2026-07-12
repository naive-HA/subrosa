package acab.naiveha.subrosa.ui.openpgp

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

data class ConnectedPgpDevice(
    val type: PgpDeviceType,
    val firmwareVersion: String?,
) {
    companion object {
        val NONE = ConnectedPgpDevice(PgpDeviceType.UNKNOWN, firmwareVersion = null)
    }
}

enum class OpenPgpOperation {
    NONE,
    SAVE,
    READ,
    WIPE,
    CHANGE_ADMIN_PIN,
    RESET_ADMIN_PIN,
    CHANGE_USER_PIN,
    RESET_USER_PIN,
}

class StatusChannel {
    private val _value = MutableLiveData("")
    val value: LiveData<String> = _value
    fun post(message: String) = _value.postValue(message)
}
