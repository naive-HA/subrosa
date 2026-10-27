package acab.naiveha.subrosa.ui.management

import acab.naiveha.subrosa.ui.PgpDeviceType
import acab.naiveha.subrosa.ui.openpgp.OpenPgpCardInfo
import com.yubico.yubikit.management.DeviceInfo
import com.yubico.yubikit.support.DeviceUtil

data class ConnectedDeviceInfo(
    val deviceInfo: DeviceInfo?,
    val type: PgpDeviceType,
    val atr: String,
    val isNfc: Boolean,
    val usbName: String? = null,
    val rawFirmware: String? = null,
    val aidManufacturerId: Short? = null,
    val note: String? = null,
    val rawInfoText: String? = null,
)

sealed class PgpStatus {
    data class YubiKey(val programmed: Boolean) : PgpStatus()

    data class Nitrokey(val programmed: Boolean?, val nfcUnsupported: Boolean) : PgpStatus()

    data class OtherDevice(val programmed: Boolean, val staticPasswordSupported: Boolean? = null) : PgpStatus()

    object AwaitingSecondTap : PgpStatus()

    object None : PgpStatus()
}

data class ManagementUiState(
    val connectedDevice: ConnectedDeviceInfo? = null,
    val pgpCardInfo: OpenPgpCardInfo? = null,
    val loading: Boolean = false,
    val errorInfo: String? = null,
) {
    val isDeviceConnected: Boolean
        get() = connectedDevice != null

    val resolvedDeviceNameResult: DeviceNameResult
        get() {
            val dev = connectedDevice ?: return DeviceNameResult("Unknown", DeviceNameResult.Source.UNKNOWN)
            val usbName = dev.usbName ?: dev.deviceInfo?.let { DeviceUtil.getName(it, null) }
            val aidMfg = dev.aidManufacturerId ?: pgpCardInfo?.aidManufacturerId
            return DeviceNameResolver.resolveDeviceNameWithSource(
                usbName = usbName,
                aidManufacturerId = aidMfg,
                atrHex = dev.atr,
            )
        }

    val resolvedDeviceName: String
        get() = resolvedDeviceNameResult.name

    val resolvedFirmware: String
        get() {
            val dev = connectedDevice ?: return "unknown"
            val rawFw = dev.rawFirmware ?: dev.deviceInfo?.version?.toString()
            val pgpVer = pgpCardInfo?.cardProtocolVersion ?: pgpCardInfo?.version
            return DeviceNameResolver.formatFirmware(
                rawFirmware = rawFw,
                openPgpVersion = pgpVer,
            )
        }

    val infoText: String
        get() {
            val dev = connectedDevice ?: return ""
            if (dev.rawInfoText != null && dev.rawInfoText.isNotBlank()) {
                return dev.rawInfoText
            }
            return buildString {
                append("Device: $resolvedDeviceName\n")
                append("Firmware: $resolvedFirmware")
                if (dev.deviceInfo?.isFips == true) append("\nFIPS-certified")
                if (dev.deviceInfo?.isLocked == true) append("\nConfiguration locked")
                if (dev.note != null) append("\n\n${dev.note}")
            }
        }

    val pgpStatus: PgpStatus
        get() {
            val connected = connectedDevice ?: return PgpStatus.None
            return when (connected.type) {
                PgpDeviceType.YUBIKEY -> PgpStatus.YubiKey(programmed = pgpCardInfo.isProgrammed())
                PgpDeviceType.NITROKEY3 -> PgpStatus.Nitrokey(
                    programmed = pgpCardInfo?.isProgrammed(),
                    nfcUnsupported = pgpCardInfo == null && connected.isNfc,
                )
                PgpDeviceType.NITROKEY2 -> PgpStatus.OtherDevice(
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

    private fun OpenPgpCardInfo?.isProgrammed(): Boolean = this?.slots?.any { it.hasKey } == true
}
