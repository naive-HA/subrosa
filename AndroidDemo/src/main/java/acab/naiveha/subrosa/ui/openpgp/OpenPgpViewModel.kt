package acab.naiveha.subrosa.ui.openpgp

import android.net.Uri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import acab.naiveha.subrosa.ui.PgpDeviceType
import acab.naiveha.subrosa.ui.StatusChannel
import acab.naiveha.subrosa.ui.YubiKeyViewModel
import com.yubico.yubikit.android.transport.nfc.NfcYubiKeyDevice
import com.yubico.yubikit.android.transport.usb.UsbYubiKeyDevice
import com.yubico.yubikit.core.YubiKeyDevice
import com.yubico.yubikit.core.application.ApplicationNotAvailableException
import com.yubico.yubikit.core.smartcard.ApduException
import com.yubico.yubikit.core.smartcard.SmartCardConnection
import com.yubico.yubikit.openpgp.OpenPgpSession
import org.slf4j.LoggerFactory
import java.io.IOException

class OpenPgpViewModel : YubiKeyViewModel<OpenPgpSession>() {
    companion object {
        const val PIN_CHANGE_COMPLETE_STATUS = "PIN changed"

        const val PIN_RESET_COMPLETE_STATUS = "User PIN reset"
    }

    private val logger = LoggerFactory.getLogger(OpenPgpViewModel::class.java)

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

    private val _connectedDevice = MutableLiveData(ConnectedPgpDevice.NONE)
    val connectedDevice: LiveData<ConnectedPgpDevice> = _connectedDevice

    @Volatile
    var currentDeviceFirmwareVersion: String? = null
        private set

    val currentOperation = MutableLiveData(OpenPgpOperation.NONE)

    private val _cardInfo = MutableLiveData<OpenPgpCardInfo?>(null)
    val cardInfo: LiveData<OpenPgpCardInfo?> = _cardInfo
    fun onCardRead(info: OpenPgpCardInfo) {
        _cardInfo.postValue(info)
    }
    fun clearCardInfo() {
        _cardInfo.postValue(null)
    }
    fun requestClearUi() {
        clearCardInfo()
        clearImportedKey()
        clearResult()
    }
    private val _importedKeyInfo = MutableLiveData<OpenPgpKeyInfo?>(null)
    val importedKeyInfo: LiveData<OpenPgpKeyInfo?> = _importedKeyInfo
    private val _importedKeyArmor = MutableLiveData<String?>(null)
    val importedKeyArmor: LiveData<String?> = _importedKeyArmor
    fun onImportedKey(armor: String, info: OpenPgpKeyInfo) {
        logger.info("onImportedKey: user='${info.userId}' keys=${info.keyCount}")
        _importedKeyArmor.value = armor
        _importedKeyInfo.value = info
    }
    fun clearImportedKey() {
        _importedKeyArmor.value = null
        _importedKeyInfo.value = null
    }

    private val writeStatusChannel = StatusChannel()
    val writeStatus: LiveData<String> = writeStatusChannel.value
    fun postWriteStatus(message: String) = writeStatusChannel.post(message)

    private val readStatusChannel = StatusChannel()
    val readStatus: LiveData<String> = readStatusChannel.value
    fun postReadStatus(message: String) = readStatusChannel.post(message)

    private val wipeStatusChannel = StatusChannel()
    val wipeStatus: LiveData<String> = wipeStatusChannel.value
    fun postWipeStatus(message: String) = wipeStatusChannel.post(message)

    private val pinChangeStatusChannel = StatusChannel()
    val pinChangeStatus: LiveData<String> = pinChangeStatusChannel.value
    fun postPinChangeStatus(message: String) = pinChangeStatusChannel.post(message)

    override fun getSession(device: YubiKeyDevice, onError: (Throwable) -> Unit, callback: (OpenPgpSession) -> Unit) {
        if (shouldIgnoreTap(device)) return
        reportPreliminaryUsbType(device)
        if (device is NfcYubiKeyDevice) {
            logger.debug("NFC tap — checking for Nitrokey (unsupported over NFC) before attempting OpenPGP")
        }

        if (!device.supportsConnection(SmartCardConnection::class.java)) {
            onError(ApplicationNotAvailableException("OpenPGP requires SmartCardConnection, not supported by this device."))
            return
        }

        device.requestConnection(SmartCardConnection::class.java) { result ->
            if (!result.isSuccess) {
                val err = result.error
                logger.error("requestConnection(SmartCardConnection) FAILED for " +
                    "${transportLabel(device)} device: ${err?.let { it::class.simpleName }}: ${err?.message}", err)
                onError(err ?: IOException("requestConnection failed with no exception"))
                return@requestConnection
            }
            val connection = result.value

            if (device is NfcYubiKeyDevice) {
                logNfcConnectionOpened(connection)

                val nitrokeyVersion = NitrokeyAdminVersion.query(connection)
                if (nitrokeyVersion != null) {
                    logger.info("Nitrokey detected over NFC (admin firmware $nitrokeyVersion) — " +
                        "OpenPGP applet is not reachable over this transport, not attempting it")
                    _connectedDevice.postValue(ConnectedPgpDevice(PgpDeviceType.NITROKEY, nitrokeyVersion))
                    onError(IOException(NitrokeyAdminVersion.NFC_NOT_SUPPORTED_MESSAGE))
                    return@requestConnection
                }
            }

            openSessionAndDispatch(device, connection, onError, callback)
        }
    }

    override fun OpenPgpSession.updateState() {
        val versionLabel = if (!version.isAtLeast(1, 0, 0))
            "unknown (non-YubiKey device)"
        else
            version.toString()
        _status.postValue("OpenPGP version: $versionLabel")
    }

    private fun shouldIgnoreTap(device: YubiKeyDevice): Boolean {
        if (device is NfcYubiKeyDevice && pendingAction.value == null) {
            logger.debug("NFC tag detected but no pendingAction queued — ignoring tap " +
                "(press Read/Save/Wipe first, then tap)")
            return true
        }
        return false
    }

    private fun reportPreliminaryUsbType(device: YubiKeyDevice) {
        if (device !is UsbYubiKeyDevice) return
        val type = PgpDeviceType.fromUsbDescriptor(device)
        _connectedDevice.postValue(ConnectedPgpDevice(type, firmwareVersion = null))
        logger.info("USB device (preliminary): $type " +
            "(vendorId=0x${device.usbDevice.vendorId.toString(16)} pid=${device.pid})")
    }

    private fun logNfcConnectionOpened(connection: SmartCardConnection) {
        val atr = connection.atr
        logger.debug("NFC SmartCardConnection open — " +
            "extendedLengthApduSupported=${connection.isExtendedLengthApduSupported} " +
            "atr/historicalBytes=${atr?.joinToString(" ") { "%02X".format(it) } ?: "null"}")
    }

    private fun openSessionAndDispatch(
        device: YubiKeyDevice,
        connection: SmartCardConnection,
        onError: (Throwable) -> Unit,
        callback: (OpenPgpSession) -> Unit,
    ) {
        try {
            val session = OpenPgpSession(connection)
            logger.debug("OpenPgpSession opened — version=${session.version}")

            val type = resolveDeviceType(device, session)

            val firmwareVersion = if (type == PgpDeviceType.NITROKEY) {
                val fw = NitrokeyAdminVersion.query(connection)
                logger.debug("Nitrokey admin firmware version: $fw")
                runCatching { session.reselect() }
                    .onFailure { logger.warn("Failed to re-select OpenPGP applet after admin query: ${it.message}") }
                fw
            } else {
                null
            }

            currentDeviceFirmwareVersion = firmwareVersion
            _connectedDevice.postValue(ConnectedPgpDevice(type, firmwareVersion))
            callback(session)
        } catch (e: Throwable) {
            logger.error("Failed to open OpenPgpSession over ${transportLabel(device)}: ${e.describeChain()}", e)
            onError(e)
        }
    }

    private fun resolveDeviceType(device: YubiKeyDevice, session: OpenPgpSession): PgpDeviceType {
        val manufacturerId = session.aid.manufacturer
        val type = PgpDeviceType.detect(device, manufacturerId, session.version)
        logger.info("AID manufacturer=0x${"%04X".format(manufacturerId)} → $type")

        if (PgpDeviceType.fromManufacturerId(manufacturerId) == PgpDeviceType.UNKNOWN) {
            val fallback = if (device is UsbYubiKeyDevice) "VID/PID" else "version heuristic"
            logger.warn("Unrecognized manufacturer over ${transportLabel(device)} — falling back to $fallback")
        } else if (device is UsbYubiKeyDevice) {
            val usbGuess = PgpDeviceType.fromUsbDescriptor(device)
            if (usbGuess != type) {
                logger.warn("USB VID/PID suggested $usbGuess but AID manufacturer says $type — " +
                    "trusting the AID (spec-authoritative)")
            }
        }
        return type
    }

    private fun transportLabel(device: YubiKeyDevice): String =
        if (device is NfcYubiKeyDevice) "NFC" else "USB"

    private fun Throwable.describeChain(maxDepth: Int = 8): String {
        val chain = StringBuilder()
        var cause: Throwable? = this
        var depth = 0
        while (cause != null && depth < maxDepth) {
            chain.append("\n  [$depth] ${cause::class.simpleName}: ${cause.message}")
            if (cause is ApduException) {
                chain.append(" (SW=0x${"%04X".format(cause.sw)})")
            }
            cause = cause.cause
            depth++
        }
        return chain.toString()
    }
}
