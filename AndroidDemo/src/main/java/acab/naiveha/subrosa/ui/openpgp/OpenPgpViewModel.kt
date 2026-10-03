package acab.naiveha.subrosa.ui.openpgp

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import acab.naiveha.subrosa.ui.PgpDeviceType
import acab.naiveha.subrosa.ui.StatusChannel
import acab.naiveha.subrosa.ui.YubiKeyViewModel
import acab.naiveha.subrosa.ui.describeChain
import com.yubico.yubikit.android.transport.nfc.NfcYubiKeyDevice
import com.yubico.yubikit.android.transport.usb.UsbYubiKeyDevice
import com.yubico.yubikit.core.YubiKeyDevice
import com.yubico.yubikit.core.application.ApplicationNotAvailableException
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

    @Volatile
    private var state = OpenPgpUiState()

    private val _uiState = MutableLiveData(state)
    val uiState: LiveData<OpenPgpUiState> = _uiState

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun updateUi(update: (OpenPgpUiState) -> OpenPgpUiState) {
        synchronized(this) {
            state = update(state)
        }
        if (Looper.getMainLooper().thread == Thread.currentThread()) {
            _uiState.value = state
        } else {
            mainHandler.post { _uiState.value = state }
        }
    }

    val currentState: OpenPgpUiState
        get() = state

    fun onImportIntent(uri: Uri) {
        updateUi { it.copy(pendingImportUri = uri) }
    }
    fun consumeImportUri() {
        updateUi { it.copy(pendingImportUri = null) }
    }

    fun onCardRead(info: OpenPgpCardInfo) {
        updateUi { it.copy(cardInfo = info) }
    }
    fun clearCardInfo() {
        updateUi { it.copy(cardInfo = null) }
    }
    fun requestClearUi() {
        updateUi {
            it.copy(
                cardInfo = null,
                importedKeyInfo = null
            )
        }
        clearResult()
    }
    fun onImportedKey(armor: String, info: OpenPgpKeyInfo) {
        logger.info("onImportedKey: hasUserId=${info.userId.isNotBlank()} keys=${info.keyCount}")
        updateUi { it.copy(importedKeyInfo = info) }
    }
    fun clearImportedKey() {
        updateUi { it.copy(importedKeyInfo = null) }
    }

    fun setCurrentOperation(op: OpenPgpOperation) {
        updateUi { it.copy(currentOperation = op) }
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
                    updateUi { it.copy(connectedDevice = ConnectedPgpDevice(PgpDeviceType.NITROKEY, nitrokeyVersion)) }
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
        updateUi { it.copy(status = "OpenPGP version: $versionLabel") }
    }

    private fun reportPreliminaryUsbType(device: YubiKeyDevice) {
        if (device !is UsbYubiKeyDevice) return
        val type = PgpDeviceType.fromUsbDescriptor(device)
        updateUi { it.copy(connectedDevice = ConnectedPgpDevice(type, firmwareVersion = null)) }
        logger.info("USB device (preliminary): $type " +
            "(vendorId=0x${device.usbDevice.vendorId.toString(16)} pid=${device.pid})")
    }

    private fun logNfcConnectionOpened(connection: SmartCardConnection) {
        val atr = connection.atr
        logger.debug("NFC SmartCardConnection open — " +
            "extendedLengthApduSupported=${connection.isExtendedLengthApduSupported} " +
            "atr/historicalBytes=${atr.joinToString(" ") { "%02X".format(it) } ?: "null"}")
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

            val firmwareVersion = when (type) {
                PgpDeviceType.NITROKEY -> {
                    val fw = NitrokeyAdminVersion.query(connection)
                    logger.debug("Nitrokey admin firmware version: $fw")
                    runCatching { session.reselect() }
                        .onFailure { logger.warn("Failed to re-select OpenPGP applet after admin query: ${it.message}") }
                    fw
                }
                PgpDeviceType.GNUK -> session.gnukVersionLabel()
                else -> null
            }

            updateUi { it.copy(connectedDevice = it.connectedDevice.copy(type = type, firmwareVersion = firmwareVersion)) }
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
}
