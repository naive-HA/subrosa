package acab.naiveha.subrosa.ui.nitrokey3updater

internal data class UsbDeviceInfo(val name: String, val vendorId: Int, val productId: Int) {
    val ids: String get() = Nk3UsbIds.format(vendorId, productId)

    val description: String get() = Nk3UsbIds.describe(vendorId, productId)
}

internal fun interface UsbDeviceSource {
    fun attached(): List<UsbDeviceInfo>
}

internal sealed interface BootloaderScan {
    object None : BootloaderScan

    class Single(val device: UsbDeviceInfo) : BootloaderScan

    class Multiple(val devices: List<UsbDeviceInfo>) : BootloaderScan
}

internal sealed interface IdentityCheck {
    class Match(val device: UsbDeviceInfo) : IdentityCheck

    object Missing : IdentityCheck

    class Changed(val expected: BootloaderDevice, val found: UsbDeviceInfo) : IdentityCheck

    class Ambiguous(val candidates: List<UsbDeviceInfo>) : IdentityCheck
}

internal object BootloaderIdentity {

    const val NOT_CONNECTED = "The Nitrokey 3 bootloader is no longer connected."

    fun candidates(attached: List<UsbDeviceInfo>): List<UsbDeviceInfo> =
        attached.filter { Nk3UsbIds.isBootloaderCandidate(it.vendorId, it.productId) }.sortedBy { it.name }

    fun scan(attached: List<UsbDeviceInfo>): BootloaderScan {
        val found = candidates(attached)
        return when (found.size) {
            0 -> BootloaderScan.None
            1 -> BootloaderScan.Single(found[0])
            else -> BootloaderScan.Multiple(found)
        }
    }

    fun verify(expected: BootloaderDevice, source: UsbDeviceSource): IdentityCheck =
        verify(expected, source.attached())

    fun verify(expected: BootloaderDevice, attached: List<UsbDeviceInfo>): IdentityCheck {
        val found = attached.firstOrNull { it.name == expected.usbName } ?: return IdentityCheck.Missing
        if (found.vendorId != expected.vendorId || found.productId != expected.productId) {
            return IdentityCheck.Changed(expected, found)
        }
        val candidates = candidates(attached)
        if (candidates.size > 1) return IdentityCheck.Ambiguous(candidates)
        return IdentityCheck.Match(found)
    }

    fun refusal(check: IdentityCheck): String? = when (check) {
        is IdentityCheck.Match -> null
        IdentityCheck.Missing -> NOT_CONNECTED
        is IdentityCheck.Changed ->
            "The USB device changed. The bootloader that was tested was " +
                "${Nk3UsbIds.describe(check.expected.vendorId, check.expected.productId)}, " +
                "but the device at that address is now ${check.found.description}. Nothing was sent to it. " +
                "Unplug your Nitrokey 3, plug it in again and start over."
        is IdentityCheck.Ambiguous ->
            "More than one bootloader device is connected (${check.candidates.joinToString { it.ids }}). " +
                "Nothing was sent. Leave only your Nitrokey 3 connected and start over."
    }
}
