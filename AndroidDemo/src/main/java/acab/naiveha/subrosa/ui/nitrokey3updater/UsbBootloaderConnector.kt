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

internal class UsbBootloaderConnector(context: Context) {
    private val appContext: Context = context.applicationContext
    private val usbManager: UsbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager

    fun findDevice(deviceName: String): UsbDevice? = usbManager.deviceList[deviceName]

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
