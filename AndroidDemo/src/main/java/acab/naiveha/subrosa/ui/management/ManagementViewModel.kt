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
import com.yubico.yubikit.core.UsbPid
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
import com.yubico.yubikit.android.transport.usb.UsbDeviceManager
import com.yubico.yubikit.android.transport.nfc.NfcYubiKeyDevice
import com.yubico.yubikit.fido.ctap.Ctap2Session

import org.slf4j.LoggerFactory

import java.io.IOException

data class ConnectedDeviceInfo(
    val deviceInfo: DeviceInfo?,
    val type: YubiKeyType?,
    val atr: String
)

class ManagementViewModel : YubiKeyViewModel<ManagementSession>() {
    private var lastDeviceWasNitrokey = false

    private val logger = LoggerFactory.getLogger(ManagementViewModel::class.java)

    private val _deviceInfo = MutableLiveData<ConnectedDeviceInfo?>()
    val deviceInfo: LiveData<ConnectedDeviceInfo?> = _deviceInfo

    private val _errorInfo = MutableLiveData<String?>()
    val errorInfo: LiveData<String?> = _errorInfo

    // NOTE: readDeviceInfo is always called from singleDispatcher (background thread).
    // isYubiKey() is a blocking NFC I/O call and must never run on the main thread.
    private fun readDeviceInfo(device: YubiKeyDevice) {

        val usbPid: UsbPid? = (device as? UsbYubiKeyDevice)?.pid
        val isNitrokey = usbPid?.type == YubiKeyType.NK3
                || (device as? UsbYubiKeyDevice)
            ?.usbDevice?.vendorId == UsbDeviceManager.NITROKEY_VENDOR_ID

        if (isNitrokey) {
            lastDeviceWasNitrokey = true
            readNitrokeyInfoUsb(device)
            return
        }

        if (device is NfcYubiKeyDevice && !device.isYubiKey()) {
            lastDeviceWasNitrokey = true
            readNitrokeyInfoNfc(device)
            return
        }
        lastDeviceWasNitrokey = false

        val usbPidForYubico = usbPid
        val readInfo: (YubiKeyConnection) -> Unit = {
            try {
                val atr = StringUtils.bytesToHex(
                    (it as? SmartCardConnection)?.atr ?: byteArrayOf()
                )
                _deviceInfo.postValue(
                    ConnectedDeviceInfo(DeviceUtil.readInfo(it, usbPidForYubico), usbPidForYubico?.type, atr)
                )
            } catch (e: IllegalArgumentException) {
                _errorInfo.postValue("Failed to identify device. Is it a supported security key?")
                _deviceInfo.postValue(null)
                throw e
            } catch (e: Exception) {
                _errorInfo.postValue("Error reading device info: ${e.message}")
                _deviceInfo.postValue(null)
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

    private fun readNitrokeyInfoUsb(device: YubiKeyDevice) {
        device.requestConnection(FidoConnection::class.java) { result ->
            if (!result.isSuccess) {
                _errorInfo.postValue("Could not open FIDO connection to Nitrokey")
                _deviceInfo.postValue(null)
                return@requestConnection
            }
            try {
                // Ctap2Session(FidoConnection) sends authenticatorGetInfo via CTAPHID 0x90
                val session   = Ctap2Session(result.value)
                val info      = session.cachedInfo
                val aaguid    = StringUtils.bytesToHex(info.getAaguid())
                val fwVersion = info.firmwareVersion?.let { fw ->
                    // Nitrokey encodes firmware as a packed integer: MAJOR<<16|MINOR<<8|PATCH
                    "%d.%d.%d".format((fw shr 16) and 0xFF, (fw shr 8) and 0xFF, fw and 0xFF)
                } ?: "unknown"

                _deviceInfo.postValue(
                    ConnectedDeviceInfo(
                        deviceInfo = null,   // no Yubico management app on Nitrokey
                        type       = YubiKeyType.NK3,
                        atr        = "USB HID/FIDO2 – Nitrokey 3\nAAGUID: $aaguid\nFirmware: $fwVersion"
                    )
                )
            } catch (e: Exception) {
                _errorInfo.postValue("Nitrokey USB info failed: ${e.message}")
                _deviceInfo.postValue(null)
            }
        }
    }

    private fun readNitrokeyInfoNfc(device: YubiKeyDevice) {
        device.requestConnection(SmartCardConnection::class.java) { result ->
            if (!result.isSuccess) {
                _errorInfo.postValue("Could not open ISO-DEP connection to Nitrokey")
                _deviceInfo.postValue(null)
                return@requestConnection
            }
            try {
                val session   = Ctap2Session(result.value)   // selects FIDO AID, calls getInfo
                val info      = session.cachedInfo
                val aaguid    = StringUtils.bytesToHex(info.aaguid)
                val fwVersion = info.firmwareVersion?.let { fw ->
                    // NK3 packs as (major<<22)|(minor<<6)|patch
                    val major = (fw ushr 22) and 0x3FF
                    val minor = (fw ushr 6)  and 0xFFFF
                    val patch =  fw           and 0x3F
                    "$major.$minor.$patch (raw: 0x${fw.toString(16)})"
                } ?: "unknown"

                val fidoVersions = info.getVersions().joinToString(", ")

                _deviceInfo.postValue(
                    ConnectedDeviceInfo(
                        deviceInfo = null,
                        type       = YubiKeyType.NK3,
                        atr        = "USB HID/FIDO2 – Nitrokey 3\n" +
                                     "AAGUID: $aaguid\n" +
                                     "Firmware: $fwVersion\n" +
                                     "FIDO versions: $fidoVersions"
                    )
                )
            } catch (e: Exception) {
                _errorInfo.postValue("Nitrokey NFC info failed: ${e.message}")
                _deviceInfo.postValue(null)
            }
        }
    }

    override fun getSession(
        device: YubiKeyDevice,
        onError: (Throwable) -> Unit,
        callback: (ManagementSession) -> Unit
    ) {
        if (lastDeviceWasNitrokey) return

        try {
            readDeviceInfo(device)
        } catch (ignored: ApplicationNotAvailableException) {
            // cannot read the device info
        }

        val usbPid: UsbPid? = (device as? UsbYubiKeyDevice)?.pid
        val isNitrokey = usbPid?.type == YubiKeyType.NK3
                || (device as? UsbYubiKeyDevice)?.usbDevice?.vendorId == UsbDeviceManager.NITROKEY_VENDOR_ID
                || (device is NfcYubiKeyDevice && !device.isYubiKey())
        if (isNitrokey) return   // readNitrokeyInfo already posted the info; no session needed

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