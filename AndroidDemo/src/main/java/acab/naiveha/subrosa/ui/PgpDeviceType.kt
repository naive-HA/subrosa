package acab.naiveha.subrosa.ui

import com.yubico.yubikit.android.transport.usb.UsbDeviceManager
import com.yubico.yubikit.android.transport.usb.UsbYubiKeyDevice
import com.yubico.yubikit.core.Version
import com.yubico.yubikit.core.YubiKeyDevice
import com.yubico.yubikit.core.YubiKeyType

enum class PgpDeviceType {
    YUBIKEY,
    NITROKEY,
    UNKNOWN;

    companion object {
        private const val MANUFACTURER_YUBICO: Short = 0x0006
        private const val MANUFACTURER_NITROKEY: Short = 0x000F

        fun fromManufacturerId(id: Short): PgpDeviceType = when (id) {
            MANUFACTURER_YUBICO   -> YUBIKEY
            MANUFACTURER_NITROKEY -> NITROKEY
            else                  -> UNKNOWN
        }

        fun fromUsbDescriptor(device: UsbYubiKeyDevice): PgpDeviceType {
            val isNitrokey = device.pid.type == YubiKeyType.NK3 ||
                device.usbDevice.vendorId == UsbDeviceManager.NITROKEY_VENDOR_ID
            return if (isNitrokey) NITROKEY else YUBIKEY
        }

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
