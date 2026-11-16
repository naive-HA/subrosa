package acab.naiveha.subrosa.ui.nitrokey3updater

import acab.naiveha.subrosa.ui.openpgp.Nitrokey3AdminVersion
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.yubico.yubikit.android.transport.usb.UsbYubiKeyDevice
import com.yubico.yubikit.android.transport.usb.UsbYubiKeyManager
import com.yubico.yubikit.core.smartcard.SmartCardConnection
import java.io.IOException

internal object AdminAppletAccess {
    private val lock = Any()
    fun <T> serialized(block: () -> T): T = synchronized(lock, block)
}

internal class UsbApplicationModeProbe(context: Context) : ApplicationModeProbe {

    private val appContext: Context = context.applicationContext
    private val usbManager: UsbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager

    init {
        UsbYubiKeyManager(appContext)
    }

    override fun readVersion(): VersionRead {
        val device = usbManager.deviceList.values.firstOrNull {
            it.vendorId == Nk3UsbIds.VID_NITROKEY && it.productId == Nk3UsbIds.PID_APP
        } ?: return VersionRead.NoDevice
        if (!usbManager.hasPermission(device)) return VersionRead.NoPermission
        return AdminAppletAccess.serialized { query(device) }
    }

    private fun query(device: UsbDevice): VersionRead {
        val key = try {
            UsbYubiKeyDevice(usbManager, device)
        } catch (e: IllegalArgumentException) {
            return VersionRead.Failed("not accepted as a Nitrokey: ${e.message}")
        }
        return try {
            if (!key.supportsConnection(SmartCardConnection::class.java)) {
                VersionRead.Failed("the key has no smart card interface")
            } else {
                key.openConnection(SmartCardConnection::class.java).use { connection ->
                    val text = Nitrokey3AdminVersion.query(connection)
                    if (text != null) VersionRead.Version(text) else VersionRead.Failed("the admin applet did not answer")
                }
            }
        } catch (e: IOException) {
            VersionRead.Failed("${e.javaClass.simpleName}: ${e.message}")
        } catch (e: IllegalStateException) {
            VersionRead.Failed("${e.javaClass.simpleName}: ${e.message}")
        } finally {
            key.close()
        }
    }
}
