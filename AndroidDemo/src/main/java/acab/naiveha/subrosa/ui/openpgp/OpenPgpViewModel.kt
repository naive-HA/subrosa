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
import com.yubico.yubikit.core.application.InvalidPinException
import com.yubico.yubikit.core.smartcard.SmartCardConnection
import com.yubico.yubikit.openpgp.OpenPgpSession
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class OpenPgpViewModel : YubiKeyViewModel<OpenPgpSession>() {
    companion object {
        const val PIN_CHANGE_COMPLETE_STATUS = "PIN changed"

        const val PIN_RESET_COMPLETE_STATUS = "User PIN reset"

        private const val TAG = "OpenPgpViewModel"
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
                pinRetries = null,
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

    fun updatePinRetries(user: Int? = null, admin: Int? = null) {
        updateUi { s ->
            val current = s.pinRetries ?: return@updateUi s
            s.copy(pinRetries = PinRetries(user = user ?: current.user, admin = admin ?: current.admin))
        }
    }

    fun refreshPinRetries(session: OpenPgpSession) {
        try {
            val pw = session.pinStatus
            updateUi { it.copy(pinRetries = PinRetries(user = pw.attemptsUser, admin = pw.attemptsAdmin)) }
        } catch (e: Exception) {
            logger.debug("refreshPinRetries failed: ${e.message}")
        }
    }

    private class QueuedPins(private val pins: List<CharArray>) {
        private val state = AtomicInteger(PENDING)

        fun start(): Boolean = state.compareAndSet(PENDING, RUNNING)

        fun cancel() {
            if (state.compareAndSet(PENDING, CANCELLED)) wipe()
        }

        fun wipe() {
            pins.forEach { it.fill('\u0000') }
        }

        private companion object {
            const val PENDING = 0
            const val RUNNING = 1
            const val CANCELLED = 2
        }
    }

    private val queuedPins = AtomicReference<QueuedPins?>()

    @Volatile
    var lastQueuedActionSucceeded: Boolean? = null
        private set

    fun queueWithPins(
        operation: OpenPgpOperation,
        pins: List<CharArray>,
        action: OpenPgpSession.() -> String?,
    ) {
        val queued = QueuedPins(pins)
        queuedPins.getAndSet(queued)?.cancel()
        setCurrentOperation(operation)
        lastQueuedActionSucceeded = null

        val task: OpenPgpSession.() -> String? = task@{
            if (!queued.start()) return@task null
            try {
                action().also { lastQueuedActionSucceeded = true }
            } catch (e: Throwable) {
                lastQueuedActionSucceeded = false
                throw e
            } finally {
                queued.wipe()
                queuedPins.compareAndSet(queued, null)
            }
        }
        pendingAction.value = task
    }

    fun changePin(isAdmin: Boolean, currentPin: CharArray, newPin: CharArray) {
        val label = if (isAdmin) "Admin" else "User"
        queuePinOperation(
            operation = if (isAdmin) OpenPgpOperation.CHANGE_ADMIN_PIN else OpenPgpOperation.CHANGE_USER_PIN,
            logLabel = "Change $label PIN",
            pins = listOf(currentPin, newPin),
            completeStatus = PIN_CHANGE_COMPLETE_STATUS,
            failureFallback = "Failed to change $label PIN",
            wrongPinMessage = "$label PIN change failed",
            onWrongPin = { attemptsRemaining ->
                if (isAdmin) updatePinRetries(admin = attemptsRemaining)
                else updatePinRetries(user = attemptsRemaining)
            },
        ) { session ->
            if (isAdmin) {
                OpenPgpWriterUtils.changeAdminPin(session, currentPin, newPin, TAG, status = this::postPinChangeStatus)
            } else {
                OpenPgpWriterUtils.changeUserPin(session, currentPin, newPin, TAG, status = this::postPinChangeStatus)
            }
        }
    }

    fun resetBlockedUserPin(adminPin: CharArray, newUserPin: CharArray) {
        queuePinOperation(
            operation = OpenPgpOperation.RESET_USER_PIN,
            logLabel = "Reset User PIN",
            pins = listOf(adminPin, newUserPin),
            completeStatus = PIN_RESET_COMPLETE_STATUS,
            failureFallback = "Failed to reset User PIN",
            wrongPinMessage = "Reset User PIN failed: wrong Admin PIN",
            onWrongPin = { attemptsRemaining -> updatePinRetries(admin = attemptsRemaining) },
        ) { session ->
            OpenPgpWriterUtils.resetBlockedUserPin(session, adminPin, newUserPin, TAG, status = this::postPinChangeStatus)
        }
    }

    fun resetOpenPgpApplet() {
        setCurrentOperation(OpenPgpOperation.RESET_ADMIN_PIN)
        pendingAction.value = {
            try {
                val writer = currentState.connectedDevice.type.writer()
                logger.info("pendingAction — resetting OpenPGP applet, writer=${writer::class.simpleName}")
                writer.wipe(this, status = this@OpenPgpViewModel::postPinChangeStatus)
                updatePinRetries(user = 3, admin = 3)
                null
            } catch (e: Exception) {
                logger.warn("Reset OpenPGP applet failed: ${e.message}")
                postPinChangeStatus(e.message ?: "Failed to reset OpenPGP applet")
                null
            } finally {
                setCurrentOperation(OpenPgpOperation.NONE)
            }
        }
    }

    fun refreshPinCounters() {
        if (pendingAction.value != null) return
        pendingAction.value = {
            refreshPinRetries(this)
            null
        }
    }

    private fun queuePinOperation(
        operation: OpenPgpOperation,
        logLabel: String,
        pins: List<CharArray>,
        completeStatus: String,
        failureFallback: String,
        wrongPinMessage: String,
        onWrongPin: (attemptsRemaining: Int) -> Unit,
        execute: (OpenPgpSession) -> Unit,
    ) {
        queueWithPins(operation, pins) {
            try {
                logger.info("pendingAction — $logLabel")
                execute(this)
                postPinChangeStatus(completeStatus)
                refreshPinRetries(this)
                null
            } catch (e: Exception) {
                logger.warn("$logLabel failed: ${e.message}")
                if (e is InvalidPinException) {
                    onWrongPin(e.attemptsRemaining)
                }
                postPinChangeStatus("")
                // Reported through result: the screen's own observer shows the toast and vibrates.
                throw IOException(if (e is InvalidPinException) wrongPinMessage else (e.message ?: failureFallback), e)
            } finally {
                setCurrentOperation(OpenPgpOperation.NONE)
            }
        }
    }

    override fun onPendingActionDropped() {
        queuedPins.get()?.cancel()
    }

    override fun onDeviceDisconnected() {
        updateUi { it.copy(pinRetries = null) }
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
        refreshPinRetries(this)
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
