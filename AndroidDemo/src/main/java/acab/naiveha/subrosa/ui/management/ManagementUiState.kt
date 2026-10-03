package acab.naiveha.subrosa.ui.management

import acab.naiveha.subrosa.ui.PgpDeviceType
import acab.naiveha.subrosa.ui.openpgp.OpenPgpCardInfo
import com.yubico.yubikit.management.DeviceInfo

data class ConnectedDeviceInfo(
    val deviceInfo: DeviceInfo?,
    val type: PgpDeviceType,
    val atr: String,
    val isNfc: Boolean,
    val infoText: String,
)

sealed class PgpStatus {
    data class YubiKey(val programmed: Boolean) : PgpStatus()

    data class Nitrokey(val programmed: Boolean?, val nfcUnsupported: Boolean) : PgpStatus()

    data class OtherDevice(val programmed: Boolean, val staticPasswordSupported: Boolean? = null) : PgpStatus()

    object AwaitingSecondTap : PgpStatus()

    object None : PgpStatus()
}

data class PinRetries(val user: Int, val admin: Int)

data class ManagementUiState(
    val connectedDevice: ConnectedDeviceInfo? = null,
    val pgpCardInfo: OpenPgpCardInfo? = null,
    val loading: Boolean = false,
    val errorInfo: String? = null,
) {
    val isDeviceConnected: Boolean
        get() = connectedDevice != null

    val infoText: String
        get() = connectedDevice?.infoText ?: ""

    val showManagementActions: Boolean
        get() = connectedDevice != null &&
            !(connectedDevice.type == PgpDeviceType.NITROKEY && connectedDevice.isNfc)

    val pgpStatus: PgpStatus
        get() {
            val connected = connectedDevice ?: return PgpStatus.None
            return when (connected.type) {
                PgpDeviceType.YUBIKEY -> PgpStatus.YubiKey(programmed = pgpCardInfo.isProgrammed())
                PgpDeviceType.NITROKEY -> PgpStatus.Nitrokey(
                    programmed = pgpCardInfo?.isProgrammed(),
                    nfcUnsupported = pgpCardInfo == null && connected.isNfc,
                )
                PgpDeviceType.GNUK -> PgpStatus.OtherDevice(
                    programmed = pgpCardInfo.isProgrammed(),
                    staticPasswordSupported = false,
                )
                PgpDeviceType.UNKNOWN -> when {
                    pgpCardInfo != null -> PgpStatus.OtherDevice(programmed = pgpCardInfo.isProgrammed())
                    connected.isNfc -> PgpStatus.AwaitingSecondTap
                    else -> PgpStatus.None
                }
            }
        }

    val pinRetries: PinRetries?
        get() = pgpCardInfo?.let { PinRetries(user = it.userPinRetries, admin = it.adminPinRetries) }

    private fun OpenPgpCardInfo?.isProgrammed(): Boolean = this?.slots?.any { it.hasKey } == true
}
