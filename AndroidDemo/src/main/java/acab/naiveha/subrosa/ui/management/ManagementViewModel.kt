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
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import acab.naiveha.subrosa.ui.PgpDeviceType
import acab.naiveha.subrosa.ui.YubiKeyViewModel
import acab.naiveha.subrosa.ui.openpgp.NitrokeyAdminVersion
import acab.naiveha.subrosa.ui.openpgp.OpenPgpCardInfo
import acab.naiveha.subrosa.ui.openpgp.OpenPgpReader
import com.yubico.yubikit.openpgp.OpenPgpSession
import com.yubico.yubikit.android.transport.nfc.NfcYubiKeyDevice
import com.yubico.yubikit.android.transport.usb.UsbYubiKeyDevice
import com.yubico.yubikit.core.UsbPid
import com.yubico.yubikit.core.YubiKeyConnection
import com.yubico.yubikit.core.YubiKeyDevice
import com.yubico.yubikit.core.application.ApplicationNotAvailableException
import com.yubico.yubikit.core.fido.FidoConnection
import com.yubico.yubikit.core.otp.OtpConnection
import com.yubico.yubikit.core.smartcard.SmartCardConnection
import com.yubico.yubikit.core.util.StringUtils
import com.yubico.yubikit.fido.ctap.Ctap2Session
import com.yubico.yubikit.management.ManagementSession
import com.yubico.yubikit.support.DeviceUtil

import org.slf4j.LoggerFactory

import java.io.IOException

class ManagementViewModel : YubiKeyViewModel<ManagementSession>() {

    private val logger = LoggerFactory.getLogger(ManagementViewModel::class.java)

    private val _connectedDevice = MutableLiveData<ConnectedDeviceInfo?>()

    private val _errorInfo = MutableLiveData<String?>()
    val errorInfo: LiveData<String?> = _errorInfo

    private val _pgpCardInfo = MutableLiveData<OpenPgpCardInfo?>(null)

    private val _uiState = MediatorLiveData<ManagementUiState?>().apply {
        addSource(_connectedDevice) { value = combine(it, _pgpCardInfo.value) }
        addSource(_pgpCardInfo) { value = combine(_connectedDevice.value, it) }
    }
    val uiState: LiveData<ManagementUiState?> = _uiState

    fun requestClearUi() {
        _connectedDevice.postValue(null)
        _pgpCardInfo.postValue(null)
        _errorInfo.postValue(null)
    }

    fun updatePinRetries(user: Int? = null, admin: Int? = null) {
        val current = _pgpCardInfo.value ?: return
        _pgpCardInfo.postValue(current.copy(
            userPinRetries = user ?: current.userPinRetries,
            adminPinRetries = admin ?: current.adminPinRetries
        ))
    }

    fun refreshPinRetries(session: OpenPgpSession) {
        val current = _pgpCardInfo.value ?: return
        _pgpCardInfo.postValue(
            try {
                val pw = session.pinStatus
                current.copy(userPinRetries = pw.attemptsUser, adminPinRetries = pw.attemptsAdmin)
            } catch (e: Exception) {
                logger.debug("refreshPinRetries failed: ${e.message}")
                current
            }
        )
    }

    override fun onDeviceDisconnected() {
        requestClearUi()
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

    private fun combine(connected: ConnectedDeviceInfo?, pgp: OpenPgpCardInfo?): ManagementUiState? {
        if (connected == null) return null
        val showManagementActions = !(connected.type == PgpDeviceType.NITROKEY && connected.isNfc)
        logger.debug(
            "uiState: type=${connected.type} isNfc=${connected.isNfc} " +
                "showManagementActions=$showManagementActions"
        )
        return ManagementUiState(
            infoText = connected.infoText,
            showManagementActions = showManagementActions,
            pgpStatus = computePgpStatus(connected, pgp),
            pinRetries = pgp?.let { PinRetries(user = it.userPinRetries, admin = it.adminPinRetries) },
        )
    }

    private fun computePgpStatus(connected: ConnectedDeviceInfo, pgp: OpenPgpCardInfo?): PgpStatus {
        return when (connected.type) {
            PgpDeviceType.YUBIKEY -> PgpStatus.YubiKey(programmed = pgp.isProgrammed())
            PgpDeviceType.NITROKEY -> PgpStatus.Nitrokey(
                programmed = pgp?.let { it.isProgrammed() },
                nfcUnsupported = pgp == null && connected.isNfc,
            )
            PgpDeviceType.UNKNOWN -> when {
                pgp != null -> PgpStatus.OtherDevice(programmed = pgp.isProgrammed())
                connected.isNfc -> PgpStatus.AwaitingSecondTap
                else -> PgpStatus.None
            }
        }
    }

    private fun OpenPgpCardInfo?.isProgrammed(): Boolean = this?.slots?.any { it.hasKey } == true

    private fun readDeviceInfo(device: YubiKeyDevice): Boolean {

        val usbPid: UsbPid? = (device as? UsbYubiKeyDevice)?.pid

        if (device is UsbYubiKeyDevice && PgpDeviceType.fromUsbDescriptor(device) == PgpDeviceType.NITROKEY) {
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
        val pgpType = if (device is UsbYubiKeyDevice) {
            PgpDeviceType.fromUsbDescriptor(device)
        } else {
            PgpDeviceType.UNKNOWN
        }
        val readInfo: (YubiKeyConnection) -> Unit = { conn ->
            try {
                val atr = StringUtils.bytesToHex(
                    (conn as? SmartCardConnection)?.atr ?: byteArrayOf()
                )
                val devInfo = DeviceUtil.readInfo(conn, usbPid)
                _connectedDevice.postValue(
                    ConnectedDeviceInfo(
                        deviceInfo = devInfo,
                        type       = pgpType,
                        atr        = atr,
                        isNfc      = false,
                        infoText   = formatDeviceInfo(
                            name     = DeviceUtil.getName(devInfo, usbPid?.type),
                            firmware = devInfo.version.toString(),
                            fips     = devInfo.isFips,
                            locked   = devInfo.isLocked,
                        ),
                    )
                )
                (conn as? SmartCardConnection)?.let { readPgpInfo(it) }
            } catch (e: IllegalArgumentException) {
                _errorInfo.postValue("Failed to identify device. Is it a supported security key?")
                _connectedDevice.postValue(null)
                throw e
            } catch (e: Exception) {
                _errorInfo.postValue("Error reading device info: ${e.message}")
                _connectedDevice.postValue(null)
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
                _connectedDevice.postValue(null)
                return@requestConnection
            }
            try {
                val ctapInfo = Ctap2Session(result.value).cachedInfo
                val fwLabel  = fwVersion ?: "unavailable"
                _connectedDevice.postValue(buildNitrokeyInfo(ctapInfo, fwLabel, "USB HID/FIDO2"))
            } catch (e: Exception) {
                _errorInfo.postValue("Nitrokey USB info failed: ${e.message}")
                _connectedDevice.postValue(null)
            }
        }
    }

    private fun formatDeviceInfo(
        name: String,
        firmware: String,
        fips: Boolean? = null,
        locked: Boolean? = null,
        note: String? = null,
    ): String {
        val base = buildString {
            append("Device: $name\n")
            append("Firmware: $firmware")
            if (fips == true) append("\nFIPS-certified")
            if (locked == true) append("\nConfiguration locked")
        }
        return if (note != null) "$base\n\n$note" else base
    }

    private fun readNfcDevice(device: NfcYubiKeyDevice) {
        try {
            device.openConnection(SmartCardConnection::class.java).use { conn ->
                val atr = StringUtils.bytesToHex(conn.atr ?: byteArrayOf())

                val fwVersion = NitrokeyAdminVersion.query(conn)
                if (fwVersion != null) {
                    postNfcNitrokeyInfo(fwVersion, atr)
                    return@use
                }

                readNfcOpenPgpDevice(device, conn, atr)
            }
        } catch (e: Exception) {
            postNfcReadFailure(e)
        }
    }

    private fun postNfcNitrokeyInfo(fwVersion: String, atr: String) {
        logger.debug("readNfcDevice: Nitrokey detected (firmware $fwVersion) — " +
                     "OpenPGP applet is not reachable over NFC, not attempting it")
        _pgpCardInfo.postValue(null)
        _connectedDevice.postValue(
            ConnectedDeviceInfo(
                deviceInfo = null,
                type       = PgpDeviceType.NITROKEY,
                atr        = atr,
                isNfc      = true,
                infoText   = formatDeviceInfo(
                    name     = "Nitrokey 3",
                    firmware = fwVersion,
                    note     = NitrokeyAdminVersion.NFC_NOT_SUPPORTED_MESSAGE,
                ),
            )
        )
    }

    private fun readNfcOpenPgpDevice(device: NfcYubiKeyDevice, conn: SmartCardConnection, atr: String) {
        val pgpSession = try {
            OpenPgpSession(conn)
        } catch (e: ApplicationNotAvailableException) {
            logger.debug("readNfcDevice: OpenPGP applet absent: ${e.message}")
            _errorInfo.postValue("OpenPGP applet not available on this NFC device.")
            _connectedDevice.postValue(null)
            return
        }

        // The OpenPGP AID's manufacturer field is spec-authoritative, so now that a
        // session is open, classify off it rather than leaving the device unclassified
        // the way a DeviceUtil.readInfo()-only classification would.
        val pgpType = PgpDeviceType.detect(device, pgpSession.aid.manufacturer, pgpSession.version)

        val pgpInfo = buildPgpCardInfo(pgpSession)
        _pgpCardInfo.postValue(pgpInfo)
        val devInfo = try {
            DeviceUtil.readInfo(conn, null)
        } catch (e: Exception) {
            logger.debug("readNfcDevice: DeviceUtil.readInfo failed: ${e.message}")
            null
        }
        _connectedDevice.postValue(
            ConnectedDeviceInfo(
                deviceInfo = devInfo,
                type       = pgpType,
                atr        = atr,
                isNfc      = true,
                infoText   = if (devInfo != null) {
                    formatDeviceInfo(
                        name     = DeviceUtil.getName(devInfo, null),
                        firmware = devInfo.version.toString(),
                        fips     = devInfo.isFips,
                        locked   = devInfo.isLocked,
                    )
                } else {
                    "Device: Unknown\n$atr\n\n(Unknown device. Not supported by sub rosa)"
                },
            )
        )
    }

    private fun postNfcReadFailure(e: Exception) {
        logger.debug("readNfcDevice failed: ${e::class.simpleName}: ${e.message}")
        val msg = e.message ?: ""
        val userMessage = when {
            e is SecurityException
            || msg.contains("out of date", ignoreCase = true)
            || msg.contains("Tag was lost", ignoreCase = true) ->
                "NFC connection lost. Hold the device still over the reader and try again."
            else ->
                "NFC read failed: $msg"
        }
        _errorInfo.postValue(userMessage)
        _connectedDevice.postValue(null)
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
    ): OpenPgpCardInfo? = try {
        OpenPgpReader.read(session, knownFirmwareVersion, useCache = true).also {
            logger.debug("buildPgpCardInfo: v=${it.version}, ${it.slots.count { s -> s.hasKey }} key(s)")
        }
    } catch (e: Exception) {
        logger.debug("buildPgpCardInfo failed: ${e::class.simpleName}: ${e.message}")
        null
    }

    private fun buildNitrokeyInfo(
        info: Ctap2Session.InfoData,
        fwVersion: String,
        transport: String
    ): ConnectedDeviceInfo {
        return ConnectedDeviceInfo(
            deviceInfo = null,
            type       = PgpDeviceType.NITROKEY,
            atr        = "",
            isNfc      = false,
            infoText   = formatDeviceInfo(
                name     = "Nitrokey 3",
                firmware = fwVersion,
                note     = "Transport: $transport",
            ),
        )
    }
}
