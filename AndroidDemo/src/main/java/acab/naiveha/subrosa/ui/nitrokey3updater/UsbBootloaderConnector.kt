package acab.naiveha.subrosa.ui.nitrokey3updater

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import kotlin.coroutines.resume

internal class UsbBootloaderConnector(context: Context) : UsbDeviceSource {
    private val appContext: Context = context.applicationContext
    private val usbManager: UsbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager

    override fun attached(): List<UsbDeviceInfo> = usbManager.deviceList.values.map(::infoOf)

    @Throws(FlashException::class)
    fun requireSame(expected: BootloaderDevice): UsbDevice {
        val devices = usbManager.deviceList
        val check = BootloaderIdentity.verify(expected, devices.values.map(::infoOf))
        BootloaderIdentity.refusal(check)?.let { throw FlashException(it) }
        return devices.getValue(expected.usbName)
    }

    suspend fun ensurePermission(device: UsbDevice): Boolean {
        if (usbManager.hasPermission(device)) return true
        return suspendCancellableCoroutine { continuation ->
            val action = "${appContext.packageName}.NITROKEY3_UPDATER_USB_PERMISSION"
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val answeredFor = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    if (answeredFor != null && answeredFor.deviceName != device.deviceName) return
                    unregister(this)
                    val granted = usbManager.hasPermission(device)
                    if (granted != intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        Log.w(TAG, "USB permission broadcast disagreed with the system; using the system's answer (granted=$granted)")
                    }
                    if (continuation.isActive) {
                        continuation.resume(granted)
                    }
                }
            }
            ContextCompat.registerReceiver(appContext, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
            continuation.invokeOnCancellation { unregister(receiver) }

            try {
                val intent = Intent(action).setPackage(appContext.packageName)
                val pendingIntent = PendingIntent.getBroadcast(appContext, 0, intent, PendingIntent.FLAG_MUTABLE)
                usbManager.requestPermission(device, pendingIntent)
            } catch (e: Exception) {
                unregister(receiver)
                throw e
            }
        }
    }

    @Throws(IOException::class)
    fun open(device: UsbDevice): UsbHidMbootTransport = UsbHidMbootTransport.open(usbManager, device)

    private fun infoOf(device: UsbDevice) = UsbDeviceInfo(device.deviceName, device.vendorId, device.productId)

    private fun unregister(receiver: BroadcastReceiver) {
        try {
            appContext.unregisterReceiver(receiver)
        } catch (e: IllegalArgumentException) {
            Log.d(TAG, "USB permission receiver was already unregistered")
        }
    }

    private companion object {
        const val TAG = "UsbBootloaderConnector"
    }
}
