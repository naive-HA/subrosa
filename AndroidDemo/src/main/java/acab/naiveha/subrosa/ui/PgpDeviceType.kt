package acab.naiveha.subrosa.ui

import com.yubico.yubikit.android.transport.usb.UsbDeviceManager
import com.yubico.yubikit.android.transport.usb.UsbYubiKeyDevice
import com.yubico.yubikit.core.Version
import com.yubico.yubikit.core.YubiKeyDevice
import com.yubico.yubikit.core.YubiKeyType
import com.yubico.yubikit.core.UsbPid

enum class PgpDeviceType {
    YUBIKEY,
    NITROKEY3,
    NITROKEY2,
    UNKNOWN;

    companion object {
        private const val MANUFACTURER_YUBICO: Short = 0x0006
        private const val MANUFACTURER_NITROKEY3: Short = 0x000F
        private const val MANUFACTURER_NITROKEY2: Short = 0x0005

        fun fromManufacturerId(id: Short): PgpDeviceType = when (id) {
            MANUFACTURER_YUBICO      -> YUBIKEY
            MANUFACTURER_NITROKEY3   -> NITROKEY3
            MANUFACTURER_NITROKEY2   -> NITROKEY2
            else                     -> UNKNOWN
        }

        fun fromUsbDescriptor(device: UsbYubiKeyDevice): PgpDeviceType {
            val vendorId = device.usbDevice.vendorId
            val pid: UsbPid? = device.pid
            return when {
                pid?.type == YubiKeyType.NK3 ||
                    vendorId == UsbDeviceManager.NITROKEY3_VENDOR_ID -> NITROKEY3
                vendorId == UsbDeviceManager.NITROKEY2_VENDOR_ID     -> NITROKEY2
                vendorId == UsbDeviceManager.YUBICO_VENDOR_ID        -> YUBIKEY
                else                                                 -> UNKNOWN
            }
        }

        fun isUsbNitrokey3(device: YubiKeyDevice?): Boolean =
            device is UsbYubiKeyDevice && fromUsbDescriptor(device) == NITROKEY3

        fun isUsbNitrokey2(device: YubiKeyDevice?): Boolean =
            device is UsbYubiKeyDevice && fromUsbDescriptor(device) == NITROKEY2

        fun detect(device: YubiKeyDevice, manufacturerId: Short, version: Version): PgpDeviceType {
            val byManufacturer = fromManufacturerId(manufacturerId)
            if (byManufacturer != UNKNOWN) return byManufacturer

            return if (device is UsbYubiKeyDevice) {
                fromUsbDescriptor(device)
            } else {
                if (!version.isAtLeast(1, 0, 0)) NITROKEY3 else YUBIKEY
            }
        }
    }
}
