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
import com.yubico.yubikit.android.transport.nfc.NfcYubiKeyDevice
import com.yubico.yubikit.android.transport.usb.UsbDeviceManager
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
import com.yubico.yubikit.fido.ctap.Ctap2Session
import com.yubico.yubikit.management.DeviceInfo
import com.yubico.yubikit.management.ManagementSession
import com.yubico.yubikit.support.DeviceUtil

import org.slf4j.LoggerFactory

import java.io.IOException

data class ConnectedDeviceInfo(
    val deviceInfo: DeviceInfo?,
    val type: YubiKeyType?,
    val atr: String
)

class ManagementViewModel : YubiKeyViewModel<ManagementSession>() {

    private val logger = LoggerFactory.getLogger(ManagementViewModel::class.java)

    private val _deviceInfo = MutableLiveData<ConnectedDeviceInfo?>()
    val deviceInfo: LiveData<ConnectedDeviceInfo?> = _deviceInfo

    private val _errorInfo = MutableLiveData<String?>()
    val errorInfo: LiveData<String?> = _errorInfo

    override fun getSession(
        device: YubiKeyDevice,
        onError: (Throwable) -> Unit,
        callback: (ManagementSession) -> Unit
    ) {
        val isNitrokey = try {
            readDeviceInfo(device)
        } catch (ignored: ApplicationNotAvailableException) {
            false
        }

        if (isNitrokey) return

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
        // Nothing to refresh automatically on the management screen.
    }

    private fun readDeviceInfo(device: YubiKeyDevice): Boolean {

        // ── USB: identify by vendor / product ID ─────────────────────────────
        val usbPid: UsbPid? = (device as? UsbYubiKeyDevice)?.pid

        val isUsbNitrokey = usbPid?.type == YubiKeyType.NK3
                || (device as? UsbYubiKeyDevice)
            ?.usbDevice?.vendorId == UsbDeviceManager.NITROKEY_VENDOR_ID

        if (isUsbNitrokey) {
            readNitrokeyInfoUsb(device)
            return true
        }

        if (device is NfcYubiKeyDevice && !device.isYubiKey()) {
            readNitrokeyInfoNfc(device)
            return true
        }

        readYubicoInfo(device, usbPid)
        return false
    }

    private fun readYubicoInfo(device: YubiKeyDevice, usbPid: UsbPid?) {
        val readInfo: (YubiKeyConnection) -> Unit = { conn ->
            try {
                val atr = StringUtils.bytesToHex(
                    (conn as? SmartCardConnection)?.atr ?: byteArrayOf()
                )
                _deviceInfo.postValue(
                    ConnectedDeviceInfo(
                        deviceInfo = DeviceUtil.readInfo(conn, usbPid),
                        type       = usbPid?.type,
                        atr        = atr
                    )
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
                        logger.debug("SmartCardConnection request failed")
                    }
                }
            }
            device.supportsConnection(OtpConnection::class.java) -> {
                device.requestConnection(OtpConnection::class.java) {
                    if (it.isSuccess) {
                        logger.debug("readInfo on OtpConnection")
                        readInfo(it.value)
                    } else {
                        logger.debug("OtpConnection request failed")
                    }
                }
            }
            device.supportsConnection(FidoConnection::class.java) -> {
                device.requestConnection(FidoConnection::class.java) {
                    if (it.isSuccess) {
                        logger.debug("readInfo on FidoConnection")
                        readInfo(it.value)
                    } else {
                        logger.debug("FidoConnection request failed")
                    }
                }
            }
            else -> throw ApplicationNotAvailableException("Cannot read device info")
        }
    }

    // ── Nitrokey info readers ─────────────────────────────────────────────────

    /**
     * Reads Nitrokey 3 info over USB via CTAPHID → authenticatorGetInfo.
     *
     * [Ctap2Session] wraps [FidoConnection] with a [FidoProtocol] and sends
     * the CTAP2 getInfo command (0x04) via CTAPHID CBOR (0x90).
     */
    private fun readNitrokeyInfoUsb(device: YubiKeyDevice) {
        device.requestConnection(FidoConnection::class.java) { result ->
            if (!result.isSuccess) {
                _errorInfo.postValue("Could not open FIDO connection to Nitrokey")
                _deviceInfo.postValue(null)
                return@requestConnection
            }
            try {
                val session = Ctap2Session(result.value)
                _deviceInfo.postValue(
                    buildNitrokeyInfo(session.cachedInfo, transport = "USB HID/FIDO2")
                )
            } catch (e: Exception) {
                _errorInfo.postValue("Nitrokey USB info failed: ${e.message}")
                _deviceInfo.postValue(null)
            }
        }
    }

    /**
     * Reads Nitrokey 3 info over NFC via ISO-DEP → FIDO2 AID → authenticatorGetInfo.
     *
     * [Ctap2Session] selects AID A0000006472F0001 and sends the CTAP2 getInfo
     * command wrapped as APDU 80 10 00 00 01 04 00.
     */
    private fun readNitrokeyInfoNfc(device: YubiKeyDevice) {
        device.requestConnection(SmartCardConnection::class.java) { result ->
            if (!result.isSuccess) {
                _errorInfo.postValue("Could not open ISO-DEP connection to Nitrokey")
                _deviceInfo.postValue(null)
                return@requestConnection
            }
            try {
                val session = Ctap2Session(result.value)
                _deviceInfo.postValue(
                    buildNitrokeyInfo(session.cachedInfo, transport = "NFC/ISO-DEP/FIDO2")
                )
            } catch (e: Exception) {
                _errorInfo.postValue("Nitrokey NFC info failed: ${e.message}")
                _deviceInfo.postValue(null)
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Builds a [ConnectedDeviceInfo] from a CTAP2 [Ctap2Session.InfoData].
     * Used for both USB and NFC Nitrokey paths; [transport] differentiates them
     * in the displayed text.
     */
    private fun buildNitrokeyInfo(
        info: Ctap2Session.InfoData,
        transport: String
    ): ConnectedDeviceInfo {
        val aaguid    = StringUtils.bytesToHex(info.getAaguid())
        val fwVersion = info.firmwareVersion?.let { decodeFirmwareVersion(it) } ?: "unknown"
        val fido      = info.getVersions().joinToString(", ")

        return ConnectedDeviceInfo(
            deviceInfo = null,   // Nitrokey does not expose the Yubico Management application
            type       = YubiKeyType.NK3,
            atr        = "$transport – Nitrokey 3\n" +
                    "AAGUID: $aaguid\n" +
                    "Firmware: $fwVersion\n" +
                    "FIDO: $fido"
        )
    }

    /**
     * Decodes the packed firmware version integer reported by Nitrokey 3 in
     * the CTAP2 authenticatorGetInfo response (field 0x0E).
     *
     * Nitrokey 3 encodes the version as:
     *   major * 1_000_000 + minor * 1_000 + patch
     * e.g. firmware 1.7.2 → 1_007_002
     *
     * The raw hex value is included so the display stays correct if a future
     * firmware revision changes the encoding.
     */
    private fun decodeFirmwareVersion(fw: Int): String {
        val major = fw / 1_000_000
        val minor = (fw % 1_000_000) / 1_000
        val patch = fw % 1_000
        return "$major.$minor.$patch (raw 0x${fw.toString(16).uppercase()})"
    }
}