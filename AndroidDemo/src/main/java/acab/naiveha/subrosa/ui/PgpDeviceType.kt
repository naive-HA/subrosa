package acab.naiveha.subrosa.ui

import com.yubico.yubikit.android.transport.usb.UsbDeviceManager
import com.yubico.yubikit.android.transport.usb.UsbYubiKeyDevice
import com.yubico.yubikit.core.Version
import com.yubico.yubikit.core.YubiKeyDevice
import com.yubico.yubikit.core.YubiKeyType

enum class PgpDeviceType {
    YUBIKEY,
    NITROKEY,
    GNUK,
    UNKNOWN;

    companion object {
        private const val MANUFACTURER_YUBICO: Short = 0x0006
        private const val MANUFACTURER_NITROKEY: Short = 0x000F
        private const val MANUFACTURER_GNUK: Short = 0x0005

        fun fromManufacturerId(id: Short): PgpDeviceType = when (id) {
            MANUFACTURER_YUBICO   -> YUBIKEY
            MANUFACTURER_NITROKEY -> NITROKEY
            MANUFACTURER_GNUK     -> GNUK
            else                  -> UNKNOWN
        }

        fun fromUsbDescriptor(device: UsbYubiKeyDevice): PgpDeviceType {
            val vendorId = device.usbDevice.vendorId
            return when {
                device.pid?.type == YubiKeyType.NK3 ||
                    vendorId == UsbDeviceManager.NITROKEY_VENDOR_ID -> NITROKEY
                vendorId == UsbDeviceManager.PURISM_VENDOR_ID       -> GNUK
                vendorId == UsbDeviceManager.YUBICO_VENDOR_ID       -> YUBIKEY
                else                                                -> UNKNOWN
            }
        }

        fun isUsbNitrokey(device: YubiKeyDevice?): Boolean =
            device is UsbYubiKeyDevice && fromUsbDescriptor(device) == NITROKEY

        fun isUsbGnuk(device: YubiKeyDevice?): Boolean =
            device is UsbYubiKeyDevice && fromUsbDescriptor(device) == GNUK

        fun detect(device: YubiKeyDevice, manufacturerId: Short, version: Version): PgpDeviceType {
            val byManufacturer = fromManufacturerId(manufacturerId)
            if (byManufacturer != UNKNOWN) return byManufacturer

            return if (device is UsbYubiKeyDevice) {
                fromUsbDescriptor(device)
            } else {
                if (!version.isAtLeast(1, 0, 0)) NITROKEY else YUBIKEY
            }
        }
    }
}
