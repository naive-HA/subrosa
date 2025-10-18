/*
 * Copyright (C) 2022-2023 Yubico.
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

package acab.naiveha.subrosa.ui.management

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

import acab.naiveha.subrosa.ui.YubiKeyViewModel
import com.yubico.yubikit.android.transport.usb.UsbYubiKeyDevice
import com.yubico.yubikit.core.YubiKeyConnection
import com.yubico.yubikit.core.YubiKeyDevice
import com.yubico.yubikit.core.YubiKeyType
import com.yubico.yubikit.core.application.ApplicationNotAvailableException
import com.yubico.yubikit.core.fido.FidoConnection
import com.yubico.yubikit.core.otp.OtpConnection
import com.yubico.yubikit.core.smartcard.SmartCardConnection
import com.yubico.yubikit.core.util.StringUtils
import com.yubico.yubikit.management.DeviceInfo
import com.yubico.yubikit.management.ManagementSession
import com.yubico.yubikit.support.DeviceUtil

import org.slf4j.LoggerFactory

import java.io.IOException

data class ConnectedDeviceInfo(
    val deviceInfo: DeviceInfo,
    val type: YubiKeyType?,
    val atr: String
)

class ManagementViewModel : YubiKeyViewModel<ManagementSession>() {

    private val logger = LoggerFactory.getLogger(ManagementViewModel::class.java)

    private val _deviceInfo = MutableLiveData<ConnectedDeviceInfo?>()
    val deviceInfo: LiveData<ConnectedDeviceInfo?> = _deviceInfo

    private val _errorInfo = MutableLiveData<String?>()
    val errorInfo: LiveData<String?> = _errorInfo

    private fun readDeviceInfo(device: YubiKeyDevice) {

        val usbPid = if (device is UsbYubiKeyDevice) {
                device.pid
        } else null

        val readInfo: (YubiKeyConnection) -> Unit = {
            try {
                val atr = StringUtils.bytesToHex((it as? SmartCardConnection)?.atr ?: byteArrayOf())

                _deviceInfo.postValue(
                    ConnectedDeviceInfo(DeviceUtil.readInfo(it, usbPid), usbPid?.type, atr)
                )
            } catch (illegalArgumentException: IllegalArgumentException) {
                _errorInfo.postValue("Failed to identify device, is it a YubiKey?")
                _deviceInfo.postValue(null)
                throw illegalArgumentException
            } catch (e: Exception) {
                _errorInfo.postValue("Caught ${e.message} when reading device info")
                _deviceInfo.postValue(null)
                logger.debug("Caught {} when reading device info", e.message)
                throw e
            }
        }

        when {
            device.supportsConnection(SmartCardConnection::class.java) -> {
                device.requestConnection(SmartCardConnection::class.java) {
                    if (it.isSuccess) {
                        logger.debug("readInfo on SmartCardConnection")
                        readInfo(it.value)
                    } else {
                        logger.debug("cannot readInfo on SmartCardConnection because requesting connection failed")
                    }
                }
            }
            device.supportsConnection(OtpConnection::class.java) -> {
                device.requestConnection(OtpConnection::class.java) {
                    if (it.isSuccess) {
                        logger.debug("readInfo on OtpConnection")
                        readInfo(it.value)
                    } else {
                        logger.debug("cannot readInfo on OtpConnection because requesting connection failed")
                    }
                }
            }
            device.supportsConnection(FidoConnection::class.java) -> {
                device.requestConnection(FidoConnection::class.java) {
                    if (it.isSuccess) {
                        logger.debug("readInfo on FidoConnection")
                        readInfo(it.value)
                    } else {
                        logger.debug("cannot readInfo on FidoConnection because requesting connection failed")
                    }
                }
            }
            else -> throw ApplicationNotAvailableException("Cannot read device info")
        }
    }

    override fun getSession(
        device: YubiKeyDevice,
        onError: (Throwable) -> Unit,
        callback: (ManagementSession) -> Unit
    ) {

        try {
            readDeviceInfo(device)
        } catch (ignored: ApplicationNotAvailableException) {
            // cannot read the device info
        }

        ManagementSession.create(device) {
            try {
                callback(it.value)
            } catch (e: ApplicationNotAvailableException) {
                onError(e)
            } catch (e: IOException) {
                onError(e)
            }
        }
    }

    override fun ManagementSession.updateState() {

    }
}