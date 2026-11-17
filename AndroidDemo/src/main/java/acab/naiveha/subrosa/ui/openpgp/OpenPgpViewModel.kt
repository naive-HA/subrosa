package acab.naiveha.subrosa.ui.openpgp

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import acab.naiveha.subrosa.ui.EventChannel
import acab.naiveha.subrosa.ui.PgpDeviceType
import acab.naiveha.subrosa.ui.QueuedSecrets
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

class OpenPgpViewModel : YubiKeyViewModel<OpenPgpSession>() {
    companion object {
        const val PIN_CHANGE_COMPLETE_STATUS = "PIN changed"

        const val PIN_RESET_COMPLETE_STATUS = "User PIN reset"

        const val READY_TO_WRITE_STATUS = "Ready to write"

        const val PIN_RETRIES_REFRESHED_STATUS = "PIN retries refreshed"

        private val WRITE_TRANSIENT = setOf(OpenPgpWriter.WRITE_COMPLETE_STATUS, READY_TO_WRITE_STATUS)
        private val READ_TRANSIENT = setOf(OpenPgpReader.READ_COMPLETE_STATUS)
        private val WIPE_TRANSIENT = setOf(OpenPgpWriter.WIPE_COMPLETE_STATUS)
        private val PIN_CHANGE_TRANSIENT = setOf(
            PIN_CHANGE_COMPLETE_STATUS,
            PIN_RESET_COMPLETE_STATUS,
            OpenPgpWriter.WIPE_COMPLETE_STATUS,
            PIN_RETRIES_REFRESHED_STATUS,
        )

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

    private val importEvents = EventChannel.latestOnly<Uri>()
    val importRequests: Flow<Uri> = importEvents.flow

    fun onImportIntent(uri: Uri) {
        importEvents.post(uri)
    }

    fun onCardRead(info: OpenPgpCardInfo) {
        updateUi { it.copy(
            cardInfo = info,
            pinRetries = PinRetries(user = info.userPinRetries, admin = info.adminPinRetries)
        ) }
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
        clearStatuses()
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

    @Volatile
    var lastQueuedActionSucceeded: Boolean? = null
        private set

    fun queueWithPins(
        operation: OpenPgpOperation,
        pins: List<CharArray>,
        action: OpenPgpSession.() -> String?,
    ) {
        setCurrentOperation(operation)
        lastQueuedActionSucceeded = null
        queueWithSecrets(QueuedSecrets(chars = pins)) {
            try {
                action().also { lastQueuedActionSucceeded = true }
            } catch (e: Throwable) {
                lastQueuedActionSucceeded = false
                throw e
            }
        }
    }

    private val preflightOutcome = AtomicReference<CompletableDeferred<Boolean>?>()

    suspend fun preflightWrite(bundle: ImportBundle): Boolean {
        if (pendingAction.value != null) {
            logger.warn("preflightWrite ignored — another action is pending")
            return false
        }
        val outcome = CompletableDeferred<Boolean>()
        preflightOutcome.set(outcome)
        setCurrentOperation(OpenPgpOperation.PREFLIGHT)
        postWriteStatus("Checking key compatibility…")
        setPendingAction {
            var supported = false
            try {
                logger.info("pendingAction — pre-flight check of the key against the device")
                currentState.connectedDevice.type.writer().requireValid(this, bundle)
                supported = true
            } finally {
                outcome.complete(supported)
            }
            null
        }
        try {
            return outcome.await()
        } finally {
            preflightOutcome.compareAndSet(outcome, null)
            postWriteStatus("")
            updateUi {
                if (it.currentOperation == OpenPgpOperation.PREFLIGHT) it.copy(currentOperation = OpenPgpOperation.NONE) else it
            }
        }
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
        setPendingAction {
            try {
                val writer = currentState.connectedDevice.type.writer()
                logger.info("pendingAction — resetting OpenPGP applet, writer=${writer::class.simpleName}")
                writer.wipe(this, status = this@OpenPgpViewModel::postPinChangeStatus)
                clearCardInfo()
                updatePinRetries(user = 3, admin = 3)
                null
            } catch (e: Exception) {
                logger.warn("Reset OpenPGP applet failed: ${e.message}")
                if (e is WipeInterruptedException) {
                    postPinChangeStatus("")
                    throw e
                }
                postPinChangeStatus(e.message ?: "Failed to reset OpenPGP applet")
                null
            } finally {
                setCurrentOperation(OpenPgpOperation.NONE)
            }
        }
    }

    fun clearPinRetries() {
        updateUi { it.copy(pinRetries = null) }
    }

    fun refreshPinCounters() {
        if (pendingAction.value != null) return
        postPinChangeStatus("Refreshing PIN retries…")
        setPendingAction {
            try {
                postPinChangeStatus("Reading PIN status…")
                refreshPinRetries(this)
                postPinChangeStatus(PIN_RETRIES_REFRESHED_STATUS)
            } catch (e: Exception) {
                logger.warn("refreshPinCounters failed: ${e.message}")
                postPinChangeStatus("Failed to refresh PIN retries: ${e.message ?: "Unknown error"}")
            }
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
                throw IOException(if (e is InvalidPinException) wrongPinMessage else (e.message ?: failureFallback), e)
            } finally {
                setCurrentOperation(OpenPgpOperation.NONE)
            }
        }
    }

    override fun onPendingActionDropped() {
        preflightOutcome.getAndSet(null)?.complete(false)
        setCurrentOperation(OpenPgpOperation.NONE)
        clearStatuses()
    }

    override fun onDeviceDisconnected() {
        updateUi { OpenPgpUiState(importedKeyInfo = it.importedKeyInfo) }
        setCurrentOperation(OpenPgpOperation.NONE)
        clearStatuses()
    }

    override fun onPendingActionFailed(error: Throwable) {
        postWriteStatus("")
        postWipeStatus("")
    }

    private fun clearStatuses() {
        postReadStatus("")
        postWriteStatus("")
        postWipeStatus("")
        postPinChangeStatus("")
    }

    private val writeStatusChannel = StatusChannel(viewModelScope)
    val writeStatus: LiveData<String> = writeStatusChannel.value
    fun postWriteStatus(message: String) =
        writeStatusChannel.post(message, transient = message in WRITE_TRANSIENT)

    private val readStatusChannel = StatusChannel(viewModelScope)
    val readStatus: LiveData<String> = readStatusChannel.value
    fun postReadStatus(message: String) =
        readStatusChannel.post(message, transient = message in READ_TRANSIENT)

    private val wipeStatusChannel = StatusChannel(
        viewModelScope,
        onExpired = {
            clearCardInfo()
            clearPinRetries()
        },
    )
    val wipeStatus: LiveData<String> = wipeStatusChannel.value
    fun postWipeStatus(message: String) =
        wipeStatusChannel.post(message, transient = message in WIPE_TRANSIENT)

    private val pinChangeStatusChannel = StatusChannel(viewModelScope)
    val pinChangeStatus: LiveData<String> = pinChangeStatusChannel.value
    fun postPinChangeStatus(message: String) =
        pinChangeStatusChannel.post(message, transient = message in PIN_CHANGE_TRANSIENT)

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
                if (device is UsbYubiKeyDevice && !device.hasPermission()) {
                    onDeviceDisconnected()
                }
                onError(err ?: IOException("requestConnection failed with no exception"))
                return@requestConnection
            }
            val connection = result.value

            if (device is NfcYubiKeyDevice) {
                logNfcConnectionOpened(connection)

                val nitrokeyVersion = Nitrokey3AdminVersion.query(connection)
                if (nitrokeyVersion != null) {
                    logger.info("Nitrokey detected over NFC (admin firmware $nitrokeyVersion) — " +
                        "OpenPGP applet is not reachable over this transport, not attempting it")
                    updateUi { it.copy(connectedDevice = ConnectedPgpDevice(PgpDeviceType.NITROKEY3, nitrokeyVersion)) }
                    onError(IOException(Nitrokey3AdminVersion.NFC_NOT_SUPPORTED_MESSAGE))
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
                PgpDeviceType.NITROKEY3 -> {
                    val fw = Nitrokey3AdminVersion.query(connection)
                    logger.debug("Nitrokey admin firmware version: $fw")
                    runCatching { session.reselect() }
                        .onFailure { logger.warn("Failed to re-select OpenPGP applet after admin query: ${it.message}") }
                    fw
                }
                PgpDeviceType.NITROKEY2 -> session.nitrokey2VersionLabel()
                else -> null
            }

            updateUi { it.copy(connectedDevice = it.connectedDevice.copy(type = type, firmwareVersion = firmwareVersion)) }
            callback(session)
        } catch (e: Throwable) {
            logger.error("Failed to open OpenPgpSession over ${transportLabel(device)}: ${e.describeChain()}", e)
            if (device is UsbYubiKeyDevice && !device.hasPermission()) {
                onDeviceDisconnected()
            }
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
