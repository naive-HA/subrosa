package acab.naiveha.subrosa.ui.nitrokey3updater

internal object Nk3UsbIds {
    const val VID_NITROKEY = 0x20A0
    const val VID_NXP = 0x1FC9
    const val VID_NORDIC = 0x1915

    const val PID_APP = 0x42B2

    const val PID_BOOTLOADER_LPC55 = 0x42DD

    const val PID_BOOTLOADER_NRF52 = 0x42E8

    const val PID_NXP_DFU = 0x0021

    fun bootloaderVariant(vendorId: Int, productId: Int): Nk3Variant? {
        return when {
            vendorId == VID_NITROKEY && productId == PID_BOOTLOADER_LPC55 -> Nk3Variant.LPC55
            vendorId == VID_NITROKEY && productId == PID_BOOTLOADER_NRF52 -> Nk3Variant.NRF52
            vendorId == VID_NXP && productId == PID_NXP_DFU -> Nk3Variant.LPC55
            vendorId == VID_NORDIC -> Nk3Variant.NRF52
            else -> null
        }
    }

    fun isBootloaderCandidate(vendorId: Int, productId: Int): Boolean =
        bootloaderVariant(vendorId, productId) != null

    fun isGenericLpc55(vendorId: Int, productId: Int): Boolean =
        bootloaderVariant(vendorId, productId) == Nk3Variant.LPC55 &&
            !(vendorId == VID_NITROKEY && productId == PID_BOOTLOADER_LPC55)

    fun format(vendorId: Int, productId: Int): String = "%04X:%04X".format(vendorId, productId)

    fun describe(vendorId: Int, productId: Int): String {
        val name = when {
            vendorId == VID_NITROKEY && productId == PID_BOOTLOADER_LPC55 -> "Nitrokey 3 bootloader (LPC55)"
            vendorId == VID_NITROKEY && productId == PID_BOOTLOADER_NRF52 -> "Nitrokey 3 bootloader (nRF52)"
            vendorId == VID_NXP && productId == PID_NXP_DFU -> "NXP LPC55 ISP bootloader"
            vendorId == VID_NORDIC -> "Nordic nRF52 bootloader"
            vendorId == VID_NITROKEY && productId == PID_APP -> "Nitrokey 3"
            else -> "USB device"
        }
        return "$name (USB ${format(vendorId, productId)})"
    }
}
