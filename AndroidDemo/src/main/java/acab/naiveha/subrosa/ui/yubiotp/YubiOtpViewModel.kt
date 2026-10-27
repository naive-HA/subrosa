/*
 * Copyright (C) 2022 Yubico.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package acab.naiveha.subrosa.ui.yubiotp

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import acab.naiveha.subrosa.ui.PgpDeviceType
import acab.naiveha.subrosa.ui.StatusChannel
import acab.naiveha.subrosa.ui.YubiKeyViewModel
import com.yubico.yubikit.android.transport.nfc.NfcYubiKeyDevice
import com.yubico.yubikit.android.transport.usb.UsbYubiKeyDevice
import com.yubico.yubikit.core.UsbPid
import com.yubico.yubikit.core.YubiKeyConnection
import com.yubico.yubikit.core.YubiKeyDevice
import com.yubico.yubikit.core.application.ApplicationNotAvailableException
import com.yubico.yubikit.core.fido.FidoConnection
import com.yubico.yubikit.core.otp.OtpConnection
import com.yubico.yubikit.core.smartcard.SmartCardConnection
import com.yubico.yubikit.yubiotp.Slot
import com.yubico.yubikit.yubiotp.YubiOtpSession
import org.slf4j.LoggerFactory
import java.io.IOException


class YubiOtpViewModel : YubiKeyViewModel<YubiOtpSession>() {
    private val logger = LoggerFactory.getLogger(YubiOtpViewModel::class.java)

    @Volatile
    private var state = YubiOtpUiState()

    private val _uiState = MutableLiveData<YubiOtpUiState?>(state)
    val uiState: LiveData<YubiOtpUiState?> = _uiState

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun updateUi(update: (YubiOtpUiState) -> YubiOtpUiState) {
        synchronized(this) {
            state = update(state)
        }
        if (Looper.getMainLooper().thread == Thread.currentThread()) {
            _uiState.value = state
        } else {
            mainHandler.post { _uiState.value = state }
        }
    }

    private val _clearUiTrigger = MutableLiveData<Unit>()
    val clearUiTrigger: LiveData<Unit> = _clearUiTrigger

    private val writeStatusChannel = StatusChannel()
    val writeStatus: LiveData<String> = writeStatusChannel.value
    fun postWriteStatus(message: String) = writeStatusChannel.post(message)

    private val readStatusChannel = StatusChannel()
    val readStatus: LiveData<String> = readStatusChannel.value
    fun postReadStatus(message: String) = readStatusChannel.post(message)

    private val resetStatusChannel = StatusChannel()
    val resetStatus: LiveData<String> = resetStatusChannel.value
    fun postResetStatus(message: String) = resetStatusChannel.post(message)

    fun requestClearUi() {
        if (Looper.getMainLooper().thread == Thread.currentThread()) {
            _clearUiTrigger.value = Unit
        } else {
            mainHandler.post {
                _clearUiTrigger.value = Unit
            }
        }
        updateUi { YubiOtpUiState() }
        postWriteStatus("")
        postReadStatus("")
        postResetStatus("")
    }

    fun setCurrentOperation(op: OtpOperation) {
        updateUi { it.copy(currentOperation = op) }
    }

    @Volatile
    private var mgmtTabActive = false

    fun setMgmtTabActive(active: Boolean) {
        mgmtTabActive = active
    }

    private val nfcSwitchMarker: YubiOtpSession.() -> String? = { null }

    fun queueSetOtpOverNfc(enabled: Boolean) {
        logger.debug("queueSetOtpOverNfc — switch moved to ${if (enabled) "on" else "off"}, waiting for a key")
        updateUi { it.copy(nfcRequested = enabled) }
        pendingAction.value = nfcSwitchMarker
    }

    override fun onPendingActionDropped() {
        if (state.nfcRequested != null) {
            logger.debug("onPendingActionDropped — dropping the queued NFC switch change")
            updateUi { YubiOtpUiState(nfc = null, nfcRequested = null) }
        }
    }

    override fun onDeviceDisconnected() {
        if (pendingAction.value != null || state.nfcRequested != null) {
            logger.debug("onDeviceDisconnected — action is pending, keeping UI state")
            return
        }
        logger.debug("onDeviceDisconnected — clearing slot status and NFC info to UNKNOWN")
        updateUi { YubiOtpUiState(nfc = null, nfcRequested = null) }
    }

    override fun getSession(
        device: YubiKeyDevice,
        onError: (Throwable) -> Unit,
        callback: (YubiOtpSession) -> Unit
    ) {
        if (PgpDeviceType.isUsbNitrokey3(device) || PgpDeviceType.isUsbNitrokey2(device)) {
            logger.info("USB Nitrokey device detected — YubiOTP is not supported on this device")
            updateUi { it.copy(nfc = OtpNfcInfo.NOT_APPLICABLE) }
            onError(ApplicationNotAvailableException(OtpNfcConfig.staticPasswordsNotSupported()))
            return
        }

        val requested = state.nfcRequested
        if (requested != null && pendingAction.value != null) {
            applyOtpOverNfc(device, requested)
            return
        }

        if (shouldIgnoreTap(device)) {
            if (mgmtTabActive) {
                readOtpOverNfcOnTap(device)
            } else {
                logger.debug("NFC tag detected but no pendingAction queued — ignoring tap " +
                    "(press Write/Read/Reset first, then tap)")
            }
            return
        }

        logger.debug("Opening YubiOtpSession over ${transportLabel(device)}")
        YubiOtpSession.create(device) { result ->
            try {
                val session = result.getValue()
                logger.debug("YubiOtpSession opened over ${transportLabel(device)}")
                callback(session)
            } catch (e: ApplicationNotAvailableException) {
                logger.info("YubiOTP application not available over ${transportLabel(device)}: ${e.message}")
                onError(ApplicationNotAvailableException(OtpNfcConfig.staticPasswordsNotSupported()))
            } catch (e: IOException) {
                logger.info("Failed to open YubiOtpSession over ${transportLabel(device)}: ${e.message}")
                onError(ApplicationNotAvailableException(OtpNfcConfig.staticPasswordsNotSupported()))
            } catch (e: Throwable) {
                logger.error("Failed to open YubiOtpSession over ${transportLabel(device)}: ${e.message}", e)
                onError(e)
            }
        }

        if (device is UsbYubiKeyDevice) {
            readOtpOverNfcUsb(device)
        }
    }

    override fun YubiOtpSession.updateState() {
        val previousSlotOne = state.slotOne
        val previousSlotTwo = state.slotTwo
        val slotOne = if (configurationState.isConfigured(Slot.ONE)) SlotState.PROGRAMMED else SlotState.EMPTY
        val slotTwo = if (configurationState.isConfigured(Slot.TWO)) SlotState.PROGRAMMED else SlotState.EMPTY
        logger.debug(
            "updateState — device reports slotOne=$slotOne slotTwo=$slotTwo " +
                "(cached UI state before this read was slotOne=$previousSlotOne slotTwo=$previousSlotTwo)"
        )
        updateUi {
            it.copy(
                slotOne = slotOne,
                slotTwo = slotTwo,
            )
        }
    }

    private fun readOtpOverNfcUsb(device: UsbYubiKeyDevice) {
        if (PgpDeviceType.fromUsbDescriptor(device) != PgpDeviceType.YUBIKEY) {
            logger.debug("readOtpOverNfcUsb — not a YubiKey, there is no NFC setting to read")
            updateUi { it.copy(nfc = OtpNfcInfo.NOT_APPLICABLE) }
            return
        }
        val pid: UsbPid? = device.pid
        withManagementConnection(device, onFailure = { e ->
            logger.debug("readOtpOverNfcUsb — failed: ${e::class.simpleName}: ${e.message}")
            updateUi { it.copy(nfc = null) }
        }) { connection ->
            val info = OtpNfcConfig.read(connection, pid, viaNfc = false)
            updateUi { it.copy(nfc = info) }
        }
    }

    private fun readOtpOverNfcOnTap(device: YubiKeyDevice) {
        logger.debug("readOtpOverNfcOnTap — NFC tap on the Management tab, reading the NFC setting")
        withManagementConnection(device, onFailure = { e ->
            logger.debug("readOtpOverNfcOnTap — failed: ${e::class.simpleName}: ${e.message}")
            postResult(Result.failure(describeNfcFailure(e, viaNfc = true)))
        }) { connection ->
            val info = OtpNfcConfig.read(connection, null, viaNfc = true)
            updateUi { it.copy(nfc = info) }
        }
    }

    private fun applyOtpOverNfc(device: YubiKeyDevice, enabled: Boolean) {
        logger.debug(
            "applyOtpOverNfc — setting YubiOTP over NFC to ${if (enabled) "enabled" else "disabled"} " +
                "over ${transportLabel(device)}"
        )
        val usb = device as? UsbYubiKeyDevice
        if (usb != null && PgpDeviceType.fromUsbDescriptor(usb) != PgpDeviceType.YUBIKEY) {
            finishOtpOverNfc(enabled, Result.failure(IllegalStateException(OtpNfcConfig.notSupported())))
            return
        }
        val pid: UsbPid? = usb?.pid
        val viaNfc = device is NfcYubiKeyDevice
        withManagementConnection(device, onFailure = { e ->
            finishOtpOverNfc(enabled, Result.failure(describeNfcFailure(e, viaNfc)))
        }) { connection ->
            finishOtpOverNfc(enabled, Result.success(OtpNfcConfig.setOtpOverNfc(connection, pid, enabled, viaNfc)))
        }
    }

    private fun finishOtpOverNfc(requested: Boolean, outcome: Result<OtpNfcInfo>) {
        outcome.fold(
            onSuccess = { info ->
                logger.debug(
                    "applyOtpOverNfc — the key reports ${info.otpOverNfc}, " +
                        "requested ${if (requested) "enabled" else "disabled"}"
                )
                updateUi { it.copy(nfc = info, nfcRequested = null) }
                val confirmed = info.otpOverNfc
                postResult(
                    when {
                        confirmed == OtpNfcState.UNKNOWN -> Result.success(OtpNfcConfig.otpNfcSent())
                        (confirmed == OtpNfcState.ENABLED) == requested -> Result.success(OtpNfcConfig.otpOverNfcStatus(requested))
                        else -> Result.failure(IllegalStateException(OtpNfcConfig.notApplied(requested)))
                    }
                )
            },
            onFailure = { e ->
                logger.debug("applyOtpOverNfc — failed: ${e::class.simpleName}: ${e.message}")
                updateUi { it.copy(nfcRequested = null) }
                postResult(Result.failure(e))
            },
        )
        pendingAction.postValue(null)
    }

    private fun withManagementConnection(
        device: YubiKeyDevice,
        onFailure: (Throwable) -> Unit,
        block: (YubiKeyConnection) -> Unit,
    ) {
        if (device is NfcYubiKeyDevice) {
            try {
                device.openConnection(SmartCardConnection::class.java).use { block(it) }
            } catch (e: Exception) {
                onFailure(e)
            }
            return
        }
        when {
            device.supportsConnection(OtpConnection::class.java) ->
                requestManagementConnection(device, OtpConnection::class.java, onFailure, block)
            device.supportsConnection(SmartCardConnection::class.java) ->
                requestManagementConnection(device, SmartCardConnection::class.java, onFailure, block)
            device.supportsConnection(FidoConnection::class.java) ->
                requestManagementConnection(device, FidoConnection::class.java, onFailure, block)
            else -> onFailure(ApplicationNotAvailableException("No connection the Management application can use"))
        }
    }

    private fun <C : YubiKeyConnection> requestManagementConnection(
        device: YubiKeyDevice,
        connectionType: Class<C>,
        onFailure: (Throwable) -> Unit,
        block: (YubiKeyConnection) -> Unit,
    ) {
        logger.debug("requestManagementConnection — Management over ${connectionType.simpleName} (${transportLabel(device)})")
        device.requestConnection(connectionType) { result ->
            if (result.isSuccess) {
                try {
                    block(result.value)
                } catch (e: Exception) {
                    onFailure(e)
                }
            } else {
                onFailure(result.error ?: IOException("${connectionType.simpleName} request failed"))
            }
        }
    }

    private fun describeNfcFailure(e: Throwable, viaNfc: Boolean): Throwable =
        if (viaNfc && (e is IOException || e is SecurityException)) Exception(OtpNfcConfig.nfcCommunicationFailed(), e) else e
}
