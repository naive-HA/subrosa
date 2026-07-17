package acab.naiveha.subrosa.ui.openpgp

import acab.naiveha.subrosa.ui.PgpDeviceType

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
