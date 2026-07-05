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
import acab.naiveha.subrosa.ui.YubiKeyViewModel
import com.yubico.yubikit.android.transport.nfc.NfcYubiKeyDevice
import com.yubico.yubikit.android.transport.usb.UsbDeviceManager
import com.yubico.yubikit.android.transport.usb.UsbYubiKeyDevice
import com.yubico.yubikit.core.YubiKeyDevice
import com.yubico.yubikit.core.YubiKeyType
import com.yubico.yubikit.core.application.ApplicationNotAvailableException
import com.yubico.yubikit.yubiotp.ConfigurationState
import com.yubico.yubikit.yubiotp.YubiOtpSession


class OtpViewModel : YubiKeyViewModel<YubiOtpSession>() {
    private val _slotStatus = MutableLiveData<ConfigurationState?>()
    val slotConfigurationState = _slotStatus

    private val _clearUiTrigger = MutableLiveData<Boolean>(false)
    val clearUiTrigger: LiveData<Boolean> = _clearUiTrigger

    private val _saveStatus = MutableLiveData<String>()
    val saveStatus: LiveData<String> = _saveStatus

    private val _readStatus = MutableLiveData<String>()
    val readStatus: LiveData<String> = _readStatus

    private val _deleteStatus = MutableLiveData<String>()
    val deleteStatus: LiveData<String> = _deleteStatus

    fun postSaveStatus(message: String) {
        _saveStatus.postValue(message)
    }

    fun postReadStatus(message: String) {
        _readStatus.postValue(message)
    }

    fun postDeleteStatus(message: String) {
        _deleteStatus.postValue(message)
    }

    fun requestClearUi() {
        _clearUiTrigger.value = true
        _clearUiTrigger.value = false
        _saveStatus.value = ""
        _readStatus.value = ""
        _deleteStatus.value = ""
    }

    override fun getSession(
        device: YubiKeyDevice,
        onError: (Throwable) -> Unit,
        callback: (YubiOtpSession) -> Unit
    ) {
        if (isUsbNitrokey(device)) {
            onError(ApplicationNotAvailableException(NITROKEY_NOT_SUPPORTED_MESSAGE))
            return
        }

        if (device is NfcYubiKeyDevice && pendingAction.value == null) {
            return
        }

        YubiOtpSession.create(device) { result ->
            try {
                callback(result.value)
            } catch (e: ApplicationNotAvailableException) {
                onError(ApplicationNotAvailableException(NITROKEY_NOT_SUPPORTED_MESSAGE))
            } catch (e: Throwable) {
                onError(e)
            }
        }
    }

    override fun YubiOtpSession.updateState() {
        _slotStatus.postValue(configurationState)
    }

    companion object {
        const val NITROKEY_NOT_SUPPORTED_MESSAGE =
            "Nitrokey does not support static passwords"

        fun isUsbNitrokey(device: YubiKeyDevice?): Boolean {
            val usbPid = (device as? UsbYubiKeyDevice)?.pid
            return usbPid?.type == YubiKeyType.NK3 ||
                (device as? UsbYubiKeyDevice)?.usbDevice?.vendorId == UsbDeviceManager.NITROKEY_VENDOR_ID
        }
    }
}
