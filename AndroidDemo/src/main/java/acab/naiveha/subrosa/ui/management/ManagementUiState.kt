package acab.naiveha.subrosa.ui.management

import acab.naiveha.subrosa.ui.PgpDeviceType
import com.yubico.yubikit.management.DeviceInfo

internal data class ConnectedDeviceInfo(
    val deviceInfo: DeviceInfo?,
    val type: PgpDeviceType,
    val atr: String,
    val isNfc: Boolean,
    val infoText: String,
)

sealed class PgpStatus {
    data class YubiKey(val programmed: Boolean) : PgpStatus()

    data class Nitrokey(val programmed: Boolean?, val nfcUnsupported: Boolean) : PgpStatus()

    data class OtherDevice(val programmed: Boolean) : PgpStatus()

    object AwaitingSecondTap : PgpStatus()

    object None : PgpStatus()
}

data class PinRetries(val user: Int, val admin: Int)

data class ManagementUiState(
    val infoText: String,
    val showManagementActions: Boolean,
    val pgpStatus: PgpStatus,
    val pinRetries: PinRetries?,
)
