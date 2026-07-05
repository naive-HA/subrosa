package acab.naiveha.subrosa.ui.openpgp

import android.net.Uri
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import acab.naiveha.subrosa.ui.YubiKeyViewModel
import com.yubico.yubikit.android.transport.nfc.NfcYubiKeyDevice
import com.yubico.yubikit.android.transport.usb.UsbYubiKeyDevice
import com.yubico.yubikit.android.transport.usb.UsbDeviceManager
import com.yubico.yubikit.core.YubiKeyDevice
import com.yubico.yubikit.core.YubiKeyType
import com.yubico.yubikit.core.application.ApplicationNotAvailableException
import com.yubico.yubikit.core.smartcard.SmartCardConnection
import com.yubico.yubikit.openpgp.OpenPgpSession

class OpenPgpViewModel : YubiKeyViewModel<OpenPgpSession>() {
    private companion object {
        const val TAG = "OpenPgpViewModel"
    }
    private val _status = MutableLiveData<String?>()
    val status: LiveData<String?> = _status
    private val _pendingImportUri = MutableLiveData<Uri?>(null)
    val pendingImportUri: LiveData<Uri?> = _pendingImportUri
    fun onImportIntent(uri: Uri) {
        _pendingImportUri.value = uri
    }
    fun consumeImportUri() {
        _pendingImportUri.value = null
    }
    @Volatile
    var currentDeviceType: PgpDeviceType = PgpDeviceType.UNKNOWN
        private set
    @Volatile
    var currentFirmwareVersion: String? = null
        private set
    private val _connectedDevice = MutableLiveData<PgpDeviceType>()
    val connectedDevice: LiveData<PgpDeviceType> = _connectedDevice
    private val _cardInfo = MutableLiveData<OpenPgpCardInfo?>(null)
    val cardInfo: LiveData<OpenPgpCardInfo?> = _cardInfo

    private val _writeStatus = MutableLiveData<String>("")
    val writeStatus: LiveData<String> = _writeStatus
    fun postWriteStatus(message: String) {
        _writeStatus.postValue(message)
    }

    private val _readStatus = MutableLiveData<String>("")
    val readStatus: LiveData<String> = _readStatus
    fun postReadStatus(message: String) {
        _readStatus.postValue(message)
    }

    private val _wipeStatus = MutableLiveData<String>("")
    val wipeStatus: LiveData<String> = _wipeStatus
    fun postWipeStatus(message: String) {
        _wipeStatus.postValue(message)
    }

    private val _importedKeyInfo = MutableLiveData<OpenPgpKeyInfo?>(null)
    val importedKeyInfo: LiveData<OpenPgpKeyInfo?> = _importedKeyInfo

    private val _importedKeyArmor = MutableLiveData<String?>(null)
    val importedKeyArmor: LiveData<String?> = _importedKeyArmor

    fun onImportedKey(armor: String, info: OpenPgpKeyInfo) {
        Log.i(TAG, "onImportedKey: user='${info.userId}' keys=${info.keyCount}")
        _importedKeyArmor.value = armor
        _importedKeyInfo.value = info
    }

    fun clearImportedKey() {
        _importedKeyArmor.value = null
        _importedKeyInfo.value = null
    }

    fun onCardRead(info: OpenPgpCardInfo) {
        _cardInfo.postValue(info)
    }
    fun clearCardInfo() {
        _cardInfo.postValue(null)
    }
    override fun getSession(device: YubiKeyDevice, onError: (Throwable) -> Unit, callback: (OpenPgpSession) -> Unit) {
        if (device is NfcYubiKeyDevice && pendingAction.value == null) return
        if (device is UsbYubiKeyDevice) {
            val type = detectUsbDeviceType(device)
            currentDeviceType = type
            _connectedDevice.postValue(type)
            Log.i(TAG, "USB device: $type " +
                       "(vendorId=0x${device.usbDevice.vendorId.toString(16)} " +
                       "pid=${device.pid})")
        }
        if (device is NfcYubiKeyDevice) {
            Log.d(TAG, "NFC tap — device type will be resolved after session opens")
        }
        if (!device.supportsConnection(SmartCardConnection::class.java)) {
            onError(ApplicationNotAvailableException("OpenPGP requires SmartCardConnection, not supported by this device."))
            return
        }
        device.requestConnection(SmartCardConnection::class.java) { result ->
            try {
                currentFirmwareVersion = if (device is UsbYubiKeyDevice && currentDeviceType == PgpDeviceType.NITROKEY) {
                    NitrokeyAdminVersion.query(result.value).also {
                        Log.d(TAG, "Nitrokey admin firmware version: $it")
                    }
                } else {
                    null
                }
                val session = OpenPgpSession(result.value)
                Log.d(TAG, "OpenPgpSession opened — version=${session.version}")
                if (device is NfcYubiKeyDevice) {
                    val isNitrokey = !session.version.isAtLeast(1, 0, 0)
                    val type = if (isNitrokey) PgpDeviceType.NITROKEY else PgpDeviceType.YUBIKEY
                    Log.i(TAG, "NFC device type from session version " +
                               "(${session.version}): $type")
                    currentDeviceType = type
                    _connectedDevice.postValue(type)
                }
                callback(session)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to open OpenPgpSession: ${e::class.simpleName}: ${e.message}", e)
                onError(e)
            }
        }
    }
    override fun OpenPgpSession.updateState() {
        val versionLabel = if (!version.isAtLeast(1, 0, 0))
            "unknown (non-YubiKey device)"
        else
            version.toString()
        _status.postValue("OpenPGP version: $versionLabel")
    }
    private fun detectUsbDeviceType(device: UsbYubiKeyDevice): PgpDeviceType {
        val isNitrokey = device.pid.type == YubiKeyType.NK3 || device.usbDevice.vendorId == UsbDeviceManager.NITROKEY_VENDOR_ID
        Log.d(TAG, "USB detectDeviceType: vendorId=0x${device.usbDevice.vendorId.toString(16)} pid=${device.pid} → isNitrokey=$isNitrokey")
        return if (isNitrokey) PgpDeviceType.NITROKEY else PgpDeviceType.YUBIKEY
    }
}
