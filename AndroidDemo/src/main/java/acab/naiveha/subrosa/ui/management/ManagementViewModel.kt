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

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import acab.naiveha.subrosa.ui.YubiKeyViewModel
import acab.naiveha.subrosa.ui.openpgp.NitrokeyAdminVersion
import acab.naiveha.subrosa.ui.openpgp.OpenPgpCardInfo
import acab.naiveha.subrosa.ui.openpgp.toDisplayString
import acab.naiveha.subrosa.ui.openpgp.toFingerprintDisplay
import acab.naiveha.subrosa.ui.openpgp.toDateDisplay
import acab.naiveha.subrosa.ui.openpgp.fromNameDo
import com.yubico.yubikit.openpgp.Do
import com.yubico.yubikit.openpgp.KeyRef
import com.yubico.yubikit.openpgp.OpenPgpSession
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

    private val _pgpCardInfo = MutableLiveData<OpenPgpCardInfo?>(null)
    val pgpCardInfo: LiveData<OpenPgpCardInfo?> = _pgpCardInfo

    fun clearDeviceInfo() {
        _deviceInfo.postValue(null)
        _pgpCardInfo.postValue(null)
        _errorInfo.postValue(null)
    }

    override fun getSession(
        device: YubiKeyDevice,
        onError: (Throwable) -> Unit,
        callback: (ManagementSession) -> Unit
    ) {
        val isNitrokey = try {
            _pgpCardInfo.postValue(null)
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

        val usbPid: UsbPid? = (device as? UsbYubiKeyDevice)?.pid

        val isUsbNitrokey = usbPid?.type == YubiKeyType.NK3
                || (device as? UsbYubiKeyDevice)
            ?.usbDevice?.vendorId == UsbDeviceManager.NITROKEY_VENDOR_ID

        if (isUsbNitrokey) {
            readNitrokeyInfoUsb(device)
            return true
        }

        if (device is NfcYubiKeyDevice) {
            readNfcDevice(device)
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
                (conn as? SmartCardConnection)?.let { readPgpInfo(it) }
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

    private fun readNitrokeyInfoUsb(device: YubiKeyDevice) {
        var fwVersion: String? = null
        try {
            (device as? UsbYubiKeyDevice)
                ?.openConnection(SmartCardConnection::class.java)
                ?.use { sc ->
                    fwVersion = NitrokeyAdminVersion.query(sc)
                    readPgpInfo(sc, knownFirmwareVersion = fwVersion)
                }
        } catch (e: Exception) {
            logger.debug("readNitrokeyInfoUsb: SmartCard block failed: ${e.message}")
        }

        device.requestConnection(FidoConnection::class.java) { result ->
            if (!result.isSuccess) {
                _errorInfo.postValue("Could not open FIDO connection to Nitrokey")
                _deviceInfo.postValue(null)
                return@requestConnection
            }
            try {
                val ctapInfo = Ctap2Session(result.value).cachedInfo
                val fwLabel  = fwVersion ?: "unavailable"
                _deviceInfo.postValue(buildNitrokeyInfo(ctapInfo, fwLabel, "USB HID/FIDO2"))
            } catch (e: Exception) {
                _errorInfo.postValue("Nitrokey USB info failed: ${e.message}")
                _deviceInfo.postValue(null)
            }
        }
    }

    private fun readNfcDevice(device: NfcYubiKeyDevice) {
        try {
            device.openConnection(SmartCardConnection::class.java).use { conn ->
                val atr = StringUtils.bytesToHex(conn.atr ?: byteArrayOf())

                val pgpSession: OpenPgpSession? = try {
                    OpenPgpSession(conn)
                } catch (e: ApplicationNotAvailableException) {
                    logger.debug("readNfcDevice: OpenPGP applet absent: ${e.message}")
                    _errorInfo.postValue("OpenPGP applet not available on this NFC device.")
                    _deviceInfo.postValue(null)
                    return@use
                } catch (e: IOException) {
                    val msg = e.message ?: ""
                    if (msg.contains("not accessible", ignoreCase = true)) {
                        logger.debug("readNfcDevice: OpenPGP returned 6985, trying probe path")
                        null
                    } else {
                        throw e
                    }
                }

                if (pgpSession != null) {
                    val mfr        = pgpSession.aid.manufacturer
                    val isNitrokey = (mfr == 0x000F.toShort())
                    logger.debug("readNfcDevice: manufacturer=0x${"%04X".format(mfr)} " +
                                 "→ ${if (isNitrokey) "Nitrokey 3" else "YubiKey / other"}")

                    val pgpInfo = buildPgpCardInfo(pgpSession)

                    if (isNitrokey) {
                        val fwVersion = try {
                            NitrokeyAdminVersion.query(conn)
                        } catch (e: Exception) {
                            logger.debug("readNfcDevice: admin version failed: ${e.message}")
                            null
                        }

                        _pgpCardInfo.postValue(
                            pgpInfo?.let {
                                if (it.version == "0.0.0" && fwVersion != null)
                                    it.copy(version = fwVersion)
                                else it
                            }
                        )

                        val ctapInfo = try {
                            Ctap2Session(conn).cachedInfo
                        } catch (e: Exception) {
                            logger.debug("readNfcDevice: Ctap2Session failed: ${e.message}")
                            null
                        }

                        val fwLabel = fwVersion ?: readNfcNdefVersion(device) ?: "unavailable"
                        _deviceInfo.postValue(
                            if (ctapInfo != null) {
                                buildNitrokeyInfo(ctapInfo, fwLabel, "NFC/ISO-DEP")
                            } else {
                                ConnectedDeviceInfo(
                                    deviceInfo = null,
                                    type       = YubiKeyType.NK3,
                                    atr        = "Device: Nitrokey 3\n" +
                                                 "Firmware: $fwLabel\n" +
                                                 "Transport: NFC/ISO-DEP"
                                )
                            }
                        )
                    } else {
                        _pgpCardInfo.postValue(pgpInfo)
                        val devInfo = try {
                            DeviceUtil.readInfo(conn, null)
                        } catch (e: Exception) {
                            logger.debug("readNfcDevice: DeviceUtil.readInfo failed: ${e.message}")
                            null
                        }
                        _deviceInfo.postValue(
                            ConnectedDeviceInfo(deviceInfo = devInfo, type = null, atr = "NFC — $atr")
                        )
                    }

                } else {
                    logger.debug("readNfcDevice: OpenPGP unavailable (6985, likely SE050_ERROR or NFC_ERROR), probing other AIDs")
                    _pgpCardInfo.postValue(null)

                    val fwVersion = try {
                        NitrokeyAdminVersion.query(conn)
                    } catch (e: Exception) {
                        null
                    }

                    if (fwVersion != null) {
                        val ctapInfo = try { Ctap2Session(conn).cachedInfo } catch (e: Exception) { null }
                        val baseInfo = if (ctapInfo != null) {
                            buildNitrokeyInfo(ctapInfo, fwVersion, "NFC/ISO-DEP")
                        } else {
                            ConnectedDeviceInfo(null, YubiKeyType.NK3,
                                "Device: Nitrokey 3\nFirmware: $fwVersion\nTransport: NFC/ISO-DEP")
                        }
                        _deviceInfo.postValue(
                            baseInfo.copy(
                                atr = baseInfo.atr + "\n\nOpenPGP: tap again to read key info"
                            )
                        )
                    } else {
                        val devInfo = try { DeviceUtil.readInfo(conn, null) } catch (e: Exception) { null }
                        _deviceInfo.postValue(ConnectedDeviceInfo(devInfo, null, "NFC — $atr"))
                        if (devInfo == null) {
                            _errorInfo.postValue(
                                "OpenPGP applet not accessible. " +
                                "Remove the key and tap again."
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.debug("readNfcDevice failed: ${e::class.simpleName}: ${e.message}")
            val msg = e.message ?: ""
            val userMessage = when {
                e is SecurityException
                || msg.contains("out of date", ignoreCase = true)
                || msg.contains("Tag was lost", ignoreCase = true) ->
                    "NFC connection lost. Hold the device still over the reader and try again."
                msg.contains("tap again", ignoreCase = true)
                || msg.contains("not accessible", ignoreCase = true) ->
                    msg
                else ->
                    "NFC read failed: $msg"
            }
            _errorInfo.postValue(userMessage)
            _deviceInfo.postValue(null)
        }
    }

    private fun readPgpInfo(conn: SmartCardConnection, knownFirmwareVersion: String? = null) {
        _pgpCardInfo.postValue(
            try {
                buildPgpCardInfo(OpenPgpSession(conn), knownFirmwareVersion)
            } catch (e: Exception) {
                logger.debug("OpenPGP info not available: ${e::class.simpleName}: ${e.message}")
                null
            }
        )
    }

    private fun buildPgpCardInfo(
        session: OpenPgpSession,
        knownFirmwareVersion: String? = null,
    ): OpenPgpCardInfo? {
        return try {
            val disc = session.cachedApplicationRelatedData.discretionary
            val pw   = disc.pwStatus

            val name = runCatching {
                String(session.cardholderRelatedData.name, Charsets.UTF_8).trim()
            }.recoverCatching {
                String(session.getData(Do.NAME), Charsets.UTF_8).trim()
            }.getOrDefault("")

            val login = runCatching {
                String(session.getData(Do.LOGIN_DATA), Charsets.UTF_8).trim()
            }.getOrDefault("")

            val slots = listOf(KeyRef.SIG, KeyRef.DEC, KeyRef.AUT).map { ref ->
                val attr   = runCatching { disc.getAlgorithmAttributes(ref) }.getOrNull()
                val fp     = runCatching { disc.getFingerprint(ref) }.getOrNull()
                val gt     = runCatching { disc.getGenerationTime(ref) }.getOrDefault(0)
                val hasKey = fp?.any { it != 0.toByte() } == true
                OpenPgpCardInfo.SlotInfo(
                    ref         = ref,
                    algorithm   = attr?.toDisplayString() ?: "—",
                    fingerprint = if (hasKey) fp.toFingerprintDisplay() else "—",
                    created     = if (gt != 0) gt.toDateDisplay() else "—",
                    hasKey      = hasKey,
                )
            }

            val sessionVersion = session.version.toString()
            val displayVersion = if (sessionVersion == "0.0.0" && knownFirmwareVersion != null) {
                knownFirmwareVersion
            } else sessionVersion

            logger.debug("buildPgpCardInfo: v=$displayVersion, ${slots.count { it.hasKey }} key(s)")
            OpenPgpCardInfo(
                version         = displayVersion,
                cardholderName  = name.fromNameDo(),
                login           = login.ifBlank { "—" },
                slots           = slots,
                userPinRetries  = pw.attemptsUser,
                resetRetries    = pw.attemptsReset,
                adminPinRetries = pw.attemptsAdmin,
            )
        } catch (e: Exception) {
            logger.debug("buildPgpCardInfo failed: ${e::class.simpleName}: ${e.message}")
            null
        }
    }

    private fun buildNitrokeyInfo(
        info: Ctap2Session.InfoData,
        fwVersion: String,
        transport: String
    ): ConnectedDeviceInfo {
        val fido = info.getVersions().joinToString(", ")
        val maxMsg = info.getMaxMsgSize() ?: 0
        return ConnectedDeviceInfo(
            deviceInfo = null,
            type       = YubiKeyType.NK3,
            atr        = "Device: Nitrokey 3\n" +
                         "Firmware: $fwVersion\n" +
                         "Transport: $transport\n" +
                         "FIDO: $fido\n" +
                         "Max message size: $maxMsg"
        )
    }

    private fun readNfcNdefVersion(device: NfcYubiKeyDevice): String? {
        return try {
            val message = NdefMessage(device.readNdef())
            message.records
                .filter { it.tnf == NdefRecord.TNF_WELL_KNOWN &&
                          it.type.contentEquals(NdefRecord.RTD_URI) }
                .mapNotNull { it.toUri()?.toString() }
                .mapNotNull { url -> Regex("""\d+\.\d+\.\d+""").find(url)?.value }
                .firstOrNull()
        } catch (e: Exception) {
            null
        }
    }
}
