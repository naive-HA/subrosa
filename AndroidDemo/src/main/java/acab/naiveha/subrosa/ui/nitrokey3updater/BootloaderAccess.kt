package acab.naiveha.subrosa.ui.nitrokey3updater

import android.content.Context
import android.hardware.usb.UsbDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

@Throws(FlashException::class)
internal suspend fun requireBootloaderAccess(connector: UsbBootloaderConnector, device: BootloaderDevice): UsbDevice {
    val usbDevice = connector.requireSame(device)
    if (!connector.ensurePermission(usbDevice)) {
        throw FlashException("USB access was not granted. Allow sub rosa to use the Nitrokey 3 and try again.")
    }
    return connector.requireSame(device)
}

@Throws(FlashException::class)
internal suspend fun <T> withBootloaderTransport(
    context: Context,
    device: BootloaderDevice,
    block: (UsbHidMbootTransport) -> T,
): T {
    val connector = UsbBootloaderConnector(context)
    val usbDevice = requireBootloaderAccess(connector, device)
    return withContext(Dispatchers.IO) {
        val transport = try {
            connector.open(usbDevice)
        } catch (e: IOException) {
            throw FlashException(e.message ?: "The bootloader could not be opened.", e)
        }
        transport.use(block)
    }
}
