package acab.naiveha.subrosa.ui.yubiotp

import android.content.Context
import androidx.annotation.StringRes
import acab.naiveha.subrosa.R
import com.yubico.yubikit.core.Transport
import com.yubico.yubikit.core.UsbPid
import com.yubico.yubikit.core.YubiKeyConnection
import com.yubico.yubikit.core.application.ApplicationNotAvailableException
import com.yubico.yubikit.core.fido.FidoConnection
import com.yubico.yubikit.core.otp.OtpConnection
import com.yubico.yubikit.core.smartcard.SmartCardConnection
import com.yubico.yubikit.management.Capability
import com.yubico.yubikit.management.DeviceConfig
import com.yubico.yubikit.management.DeviceInfo
import com.yubico.yubikit.management.ManagementSession
import com.yubico.yubikit.support.DeviceUtil
import com.yubico.yubikit.yubiotp.Slot
import org.slf4j.LoggerFactory

internal object OtpNfcConfig {
    private val logger = LoggerFactory.getLogger(OtpNfcConfig::class.java)

    const val REBOOT_AFTER_CHANGE = false

    @StringRes val NOT_A_YUBIKEY = R.string.otp_nfc_reason_unsupported
    @StringRes val NO_NFC = R.string.otp_nfc_reason_no_nfc
    @StringRes val NO_OTP = R.string.otp_nfc_reason_no_otp
    @StringRes val OLD_FIRMWARE = R.string.otp_nfc_reason_old_firmware
    @StringRes val LOCKED = R.string.otp_nfc_reason_locked
    @StringRes val UNREADABLE = R.string.otp_nfc_reason_unreadable
    @StringRes val NOT_APPLIED = R.string.otp_nfc_not_applied
    @StringRes val STATIC_PASSWORDS_NOT_SUPPORTED_RES = R.string.otp_static_passwords_not_supported
    @StringRes val SLOT_NOT_PROGRAMMED_RES = R.string.otp_slot_not_programmed
    @StringRes val NO_SLOT_CONFIGURED_RES = R.string.otp_no_slot_configured
    @StringRes val READ_COMPLETE_STATUS_RES = R.string.status_read_complete
    @StringRes val NFC_COMMUNICATION_FAILED_RES = R.string.otp_nfc_communication_failed
    @StringRes val OTP_NFC_SENT_RES = R.string.otp_nfc_sent

    fun staticPasswordsNotSupported(context: Context? = null): String =
        context?.getString(STATIC_PASSWORDS_NOT_SUPPORTED_RES) ?: "Static passwords are not supported on this device"

    fun slotNotProgrammed(context: Context? = null): String =
        context?.getString(SLOT_NOT_PROGRAMMED_RES) ?: "Slot not programmed"

    fun noSlotConfigured(context: Context? = null): String =
        context?.getString(NO_SLOT_CONFIGURED_RES) ?: "No slot configured"

    fun readCompleteStatus(context: Context? = null): String =
        context?.getString(READ_COMPLETE_STATUS_RES) ?: "Read complete"

    fun nfcCommunicationFailed(context: Context? = null): String =
        context?.getString(NFC_COMMUNICATION_FAILED_RES) ?: "NFC communication failed. Hold the key still against the phone and try again."

    fun otpNfcSent(context: Context? = null): String =
        context?.getString(OTP_NFC_SENT_RES) ?: "Change sent. Plug in or tap the key again to read the new setting."

    fun otpOverNfcStatus(enabled: Boolean): String =
        "Static password over NFC is now ${if (enabled) "on" else "off"}"

    fun slotProgrammedStatus(slot: Slot): String = "Slot $slot programmed"

    fun slotResetStatus(slot: Slot): String = "Slot $slot reset"

    fun notSupported(context: Context? = null): String =
        context?.getString(NOT_A_YUBIKEY) ?: "Not available on this device."

    fun notApplied(requested: Boolean, context: Context? = null): String =
        context?.getString(NOT_APPLIED, if (requested) "off" else "on")
            ?: "The device did not apply the change: static password over NFC is still ${if (requested) "off" else "on"}."

    fun read(connection: YubiKeyConnection, pid: UsbPid?, viaNfc: Boolean): OtpNfcInfo {
        val info = readDeviceInfo(connection, pid) ?: return OtpNfcInfo.NOT_APPLICABLE
        return info.toOtpNfcInfo(viaNfc)
    }

    fun setOtpOverNfc(
        connection: YubiKeyConnection,
        pid: UsbPid?,
        enabled: Boolean,
        viaNfc: Boolean,
        context: Context? = null,
    ): OtpNfcInfo {
        val notSupportedMsg = context?.getString(NOT_A_YUBIKEY) ?: "Not available on this device."
        val noNfcMsg = context?.getString(NO_NFC) ?: "This device has no NFC. There is nothing to change."
        val noOtpMsg = context?.getString(NO_OTP) ?: "This device does not support static passwords over NFC."
        val oldFirmwareMsg = context?.getString(OLD_FIRMWARE) ?: "The firmware does not support this features."
        val lockedMsg = context?.getString(LOCKED) ?: "The configuration is locked with a lock code and cannot be changed."
        val unreadableMsg = context?.getString(UNREADABLE) ?: "The device did not report its NFC setting. Nothing was changed."

        val before = readDeviceInfo(connection, pid) ?: throw IllegalStateException(notSupportedMsg)
        val beforeInfo = before.toOtpNfcInfo(viaNfc)
        check(beforeInfo.hasNfc) { noNfcMsg }
        check(!enabled || beforeInfo.otpSupported) { noOtpMsg }
        check(beforeInfo.canConfigure) { oldFirmwareMsg }
        check(!beforeInfo.locked) { lockedMsg }
        val current = before.config.getEnabledCapabilities(Transport.NFC) ?: throw IllegalStateException(unreadableMsg)

        val target = if (enabled) current or Capability.OTP.bit else current and Capability.OTP.bit.inv()
        logger.debug(
            "setOtpOverNfc — requested=${if (enabled) "enabled" else "disabled"} nfcMask ${current.hex()} -> ${target.hex()} " +
                "reboot=$REBOOT_AFTER_CHANGE; config before: ${before.describeConfig()}"
        )
        if (target == current) {
            logger.debug("the key already has it that way, nothing to write")
            return beforeInfo
        }

        val config = DeviceConfig.Builder().enabledCapabilities(Transport.NFC, target).build()
        managementSession(connection).updateDeviceConfig(config, REBOOT_AFTER_CHANGE, null, null)
        if (REBOOT_AFTER_CHANGE) {
            logger.debug("written, the key restarts, so the setting is not read back")
            return beforeInfo.copy(otpOverNfc = OtpNfcState.UNKNOWN)
        }

        val after = readDeviceInfo(connection, pid) ?: throw IllegalStateException("Not available on this device.")
        logger.debug("written; config after: ${after.describeConfig()}")
        if (before.describeOtherConfig() != after.describeOtherConfig()) {
            logger.warn(
                "fields other than the NFC mask changed: " +
                    "before=[${before.describeOtherConfig()}] after=[${after.describeOtherConfig()}]"
            )
        }
        val afterMask = after.config.getEnabledCapabilities(Transport.NFC)
        if (afterMask != null && (afterMask and Capability.OTP.bit.inv()) != (current and Capability.OTP.bit.inv())) {
            logger.warn("NFC bits other than YubiOTP changed: ${current.hex()} -> ${afterMask.hex()}")
        }
        return after.toOtpNfcInfo(viaNfc)
    }

    private fun readDeviceInfo(connection: YubiKeyConnection, pid: UsbPid?): DeviceInfo? = try {
        DeviceUtil.readInfo(connection, pid)
    } catch (e: IllegalArgumentException) {
        logger.debug("not supported by device: ${e.message}")
        null
    } catch (e: ApplicationNotAvailableException) {
        logger.debug("no Management application: ${e.message}")
        null
    }

    private fun managementSession(connection: YubiKeyConnection): ManagementSession = when (connection) {
        is SmartCardConnection -> ManagementSession(connection)
        is OtpConnection -> ManagementSession(connection)
        is FidoConnection -> ManagementSession(connection)
        else -> throw ApplicationNotAvailableException("Unsupported connection type")
    }

    private fun DeviceInfo.toOtpNfcInfo(viaNfc: Boolean): OtpNfcInfo {
        val enabledMask = config.getEnabledCapabilities(Transport.NFC)
        val supportedMask = getSupportedCapabilities(Transport.NFC)
        val info = OtpNfcInfo(
            applicable = true,
            hasNfc = hasTransport(Transport.NFC),
            otpSupported = (supportedMask and Capability.OTP.bit) != 0,
            locked = isLocked,
            canConfigure = ManagementSession.FEATURE_DEVICE_CONFIG.isSupportedBy(version),
            otpOverNfc = when {
                enabledMask == null -> OtpNfcState.UNKNOWN
                (enabledMask and Capability.OTP.bit) != 0 -> OtpNfcState.ENABLED
                else -> OtpNfcState.DISABLED
            },
            viaNfc = viaNfc,
        )
        logger.debug(
            "firmware=$version nfcSupported=${supportedMask.hex()} " +
                "nfcEnabled=${enabledMask?.hex() ?: "unreadable"} -> $info"
        )
        return info
    }

    private fun DeviceInfo.describeConfig(): String =
        "nfc=${config.getEnabledCapabilities(Transport.NFC)?.hex()} ${describeOtherConfig()}"

    private fun DeviceInfo.describeOtherConfig(): String = with(config) {
        "usb=${getEnabledCapabilities(Transport.USB)?.hex()} autoEject=$autoEjectTimeout " +
            "challengeResponseTimeout=$challengeResponseTimeout deviceFlags=$deviceFlags nfcRestricted=$nfcRestricted"
    }

    private fun Int.hex(): String = "0x%04X".format(this)
}
