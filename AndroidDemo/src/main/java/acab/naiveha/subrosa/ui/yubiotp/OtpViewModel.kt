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

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import acab.naiveha.subrosa.ui.PgpDeviceType
import acab.naiveha.subrosa.ui.StatusChannel
import acab.naiveha.subrosa.ui.YubiKeyViewModel
import com.yubico.yubikit.android.transport.nfc.NfcYubiKeyDevice
import com.yubico.yubikit.android.transport.usb.UsbYubiKeyDevice
import com.yubico.yubikit.core.YubiKeyDevice
import com.yubico.yubikit.core.application.ApplicationNotAvailableException
import com.yubico.yubikit.yubiotp.Slot
import com.yubico.yubikit.yubiotp.YubiOtpSession
import org.bouncycastle.util.encoders.Hex
import org.slf4j.LoggerFactory


class OtpViewModel : YubiKeyViewModel<YubiOtpSession>() {
    private val logger = LoggerFactory.getLogger(OtpViewModel::class.java)

    private val _uiState = MutableLiveData<OtpUiState?>()
    val uiState: LiveData<OtpUiState?> = _uiState

    private val _clearUiTrigger = MutableLiveData<Boolean>(false)
    val clearUiTrigger: LiveData<Boolean> = _clearUiTrigger

    private val saveStatusChannel = StatusChannel()
    val saveStatus: LiveData<String> = saveStatusChannel.value
    fun postSaveStatus(message: String) = saveStatusChannel.post(message)

    private val readStatusChannel = StatusChannel()
    val readStatus: LiveData<String> = readStatusChannel.value
    fun postReadStatus(message: String) = readStatusChannel.post(message)

    private val deleteStatusChannel = StatusChannel()
    val deleteStatus: LiveData<String> = deleteStatusChannel.value
    fun postDeleteStatus(message: String) = deleteStatusChannel.post(message)

    fun requestClearUi() {
        _clearUiTrigger.value = true
        _clearUiTrigger.value = false
        _uiState.postValue(null)
        postSaveStatus("")
        postReadStatus("")
        postDeleteStatus("")
    }

    override fun onDeviceDisconnected() {
        logger.debug("onDeviceDisconnected — clearing slot status")
        _uiState.postValue(null)
    }

    override fun getSession(
        device: YubiKeyDevice,
        onError: (Throwable) -> Unit,
        callback: (YubiOtpSession) -> Unit
    ) {
        if (isUsbNitrokey(device)) {
            logger.info("USB Nitrokey detected — YubiOTP is not supported on this device")
            onError(ApplicationNotAvailableException(NITROKEY_NOT_SUPPORTED_MESSAGE))
            return
        }

        if (device is NfcYubiKeyDevice && pendingAction.value == null) {
            logger.debug("NFC tag detected but no pendingAction queued — ignoring tap " +
                "(press Save/Read/Delete first, then tap)")
            return
        }

        logger.debug("Opening YubiOtpSession over ${transportLabel(device)}")
        YubiOtpSession.create(device) { result ->
            try {
                val session = result.value
                logger.debug("YubiOtpSession opened over ${transportLabel(device)}")
                callback(session)
            } catch (e: ApplicationNotAvailableException) {
                logger.info("YubiOTP application not available over ${transportLabel(device)}: ${e.message}")
                onError(ApplicationNotAvailableException(NITROKEY_NOT_SUPPORTED_MESSAGE))
            } catch (e: Throwable) {
                logger.error("Failed to open YubiOtpSession over ${transportLabel(device)}: ${e.message}", e)
                onError(e)
            }
        }
    }

    override fun YubiOtpSession.updateState() {
        val state = OtpUiState(
            slotOneProgrammed = configurationState.isConfigured(Slot.ONE),
            slotTwoProgrammed = configurationState.isConfigured(Slot.TWO),
        )
        logger.debug("updateState — slotOne=${state.slotOneProgrammed} slotTwo=${state.slotTwoProgrammed}")
        _uiState.postValue(state)
    }

    private fun transportLabel(device: YubiKeyDevice): String =
        if (device is NfcYubiKeyDevice) "NFC" else "USB"

    companion object {
        const val NITROKEY_NOT_SUPPORTED_MESSAGE =
            "Nitrokey does not support static passwords"

        const val READ_COMPLETE_STATUS = "Read complete"

        fun slotProgrammedStatus(slot: Slot): String = "Slot $slot programmed"

        fun slotResetStatus(slot: Slot): String = "Slot $slot reset"

        fun calculatedResponseStatus(response: ByteArray): String = "Calculated response: ${String(Hex.encode(response))}"

        fun isUsbNitrokey(device: YubiKeyDevice?): Boolean =
            device is UsbYubiKeyDevice && PgpDeviceType.fromUsbDescriptor(device) == PgpDeviceType.NITROKEY
    }
}
