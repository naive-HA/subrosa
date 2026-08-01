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

import android.os.Looper
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import acab.naiveha.subrosa.ui.PgpDeviceType
import acab.naiveha.subrosa.ui.StatusChannel
import acab.naiveha.subrosa.ui.YubiKeyViewModel
import com.yubico.yubikit.core.YubiKeyDevice
import com.yubico.yubikit.core.application.ApplicationNotAvailableException
import com.yubico.yubikit.yubiotp.Slot
import com.yubico.yubikit.yubiotp.YubiOtpSession
import org.slf4j.LoggerFactory


class YubiOtpViewModel : YubiKeyViewModel<YubiOtpSession>() {
    private val logger = LoggerFactory.getLogger(YubiOtpViewModel::class.java)

    @Volatile
    private var state = YubiOtpUiState(false, false)

    private val _uiState = MutableLiveData<YubiOtpUiState?>(state)
    val uiState: LiveData<YubiOtpUiState?> = _uiState

    @Synchronized
    private fun updateUi(update: (YubiOtpUiState) -> YubiOtpUiState) {
        val newState = update(state)
        state = newState
        if (Looper.getMainLooper().thread == Thread.currentThread()) {
            _uiState.value = newState
        } else {
            _uiState.postValue(newState)
        }
    }

    private val _clearUiTrigger = MutableLiveData<Boolean>(false)
    val clearUiTrigger: LiveData<Boolean> = _clearUiTrigger

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
        _clearUiTrigger.postValue(true)
        _clearUiTrigger.postValue(false)
        updateUi { YubiOtpUiState(false, false) }
        postWriteStatus("")
        postReadStatus("")
        postResetStatus("")
    }

    fun setCurrentOperation(op: OtpOperation) {
        updateUi { it.copy(currentOperation = op) }
    }

    override fun onDeviceDisconnected() {
        logger.debug("onDeviceDisconnected — clearing slot status")
        updateUi { YubiOtpUiState(false, false) }
    }

    override fun getSession(
        device: YubiKeyDevice,
        onError: (Throwable) -> Unit,
        callback: (YubiOtpSession) -> Unit
    ) {
        if (PgpDeviceType.isUsbNitrokey(device)) {
            logger.info("USB Nitrokey detected — YubiOTP is not supported on this device")
            onError(ApplicationNotAvailableException(STATIC_PASSWORDS_NOT_SUPPORTED))
            return
        }

        if (shouldIgnoreTap(device)) {
            logger.debug("NFC tag detected but no pendingAction queued — ignoring tap " +
                "(press Write/Read/Reset first, then tap)")
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
                onError(ApplicationNotAvailableException(STATIC_PASSWORDS_NOT_SUPPORTED))
            } catch (e: Throwable) {
                logger.error("Failed to open YubiOtpSession over ${transportLabel(device)}: ${e.message}", e)
                onError(e)
            }
        }
    }

    override fun YubiOtpSession.updateState() {
        val previousSlotOne = state.slotOneProgrammed
        val previousSlotTwo = state.slotTwoProgrammed
        val slotOne = configurationState.isConfigured(Slot.ONE)
        val slotTwo = configurationState.isConfigured(Slot.TWO)
        logger.debug(
            "updateState — device reports slotOne=$slotOne slotTwo=$slotTwo " +
                "(cached UI state before this read was slotOne=$previousSlotOne slotTwo=$previousSlotTwo)"
        )
        updateUi {
            it.copy(
                slotOneProgrammed = slotOne,
                slotTwoProgrammed = slotTwo,
            )
        }
    }

    companion object {
        const val STATIC_PASSWORDS_NOT_SUPPORTED =
            "Static passwords are not supported on this device"

        const val SLOT_NOT_PROGRAMMED = "Slot not programmed"

        const val READ_COMPLETE_STATUS = "Read complete"

        fun slotProgrammedStatus(slot: Slot): String = "Slot $slot programmed"

        fun slotResetStatus(slot: Slot): String = "Slot $slot reset"
    }
}
