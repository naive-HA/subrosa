package acab.naiveha.subrosa.ui.openpgp

import android.util.Log
import com.yubico.yubikit.core.smartcard.ApduException
import com.yubico.yubikit.core.smartcard.SW
import com.yubico.yubikit.openpgp.Do
import com.yubico.yubikit.openpgp.KeyRef
import com.yubico.yubikit.openpgp.OpenPgpSession

internal object OpenPgpWriterUtils {

    val DEFAULT_ADMIN_PIN: CharArray get() = "12345678".toCharArray()

    fun factoryResetAndRestorePin(
        session: OpenPgpSession,
        adminPin: CharArray,
        tag: String,
        status: (String) -> Unit = {},
    ) {
        Log.d(tag, "factoryResetAndRestorePin() — sending TERMINATE + ACTIVATE…")
        status("Resetting OpenPGP applet…")
        session.terminateAndActivate()
        Log.d(tag, "TERMINATE + ACTIVATE done — re-selecting application…")
        session.reselect()
        status("OpenPGP applet factory-reset")
        Log.d(tag, "Re-selected — restoring Admin PIN from device default…")
        session.changeAdminPin(DEFAULT_ADMIN_PIN, adminPin)
        status("Admin PIN restored")
        Log.d(tag, "Admin PIN restored — re-verifying…")
        session.verifyAdminPin(adminPin)
        status("Admin PIN re-verified")
        Log.d(tag, "Re-verified — setting Signature PIN policy to 'not forced'…")
        session.putData(Do.PW_STATUS_BYTES, byteArrayOf(0x00))
        status("Signature PIN set to: not forced")
        Log.i(tag, "factoryResetAndRestorePin() complete")
    }

    fun forceWipe(session: OpenPgpSession, tag: String, status: (String) -> Unit = {}) {
        Log.d(tag, "forceWipe() — manually blocking PINs…")

        val invalidPin = CharArray(8) { '0' }

        val pinStatus = session.pinStatus

        val userAttempts = pinStatus.attemptsUser
        Log.d(tag, "  Blocking User PIN ($userAttempts attempt(s) to burn)…")
        status("Blocking User PIN…")
        for (i in 1..userAttempts) {
            try {
                session.verifyUserPin(invalidPin, false)
                Log.w(tag, "  Unexpected: verifyUserPin() succeeded with a garbage PIN — stopping")
                break
            } catch (e: Exception) {
                Log.d(tag, "  User PIN attempt $i/$userAttempts rejected (${e.message})")
            }
        }

        val adminAttempts = pinStatus.attemptsAdmin
        Log.d(tag, "  Blocking Admin PIN ($adminAttempts attempt(s) to burn)…")
        status("Blocking Admin PIN…")
        for (i in 1..adminAttempts) {
            try {
                session.verifyAdminPin(invalidPin)
                Log.w(tag, "  Unexpected: verifyAdminPin() succeeded with a garbage PIN — stopping")
                break
            } catch (e: Exception) {
                Log.d(tag, "  Admin PIN attempt $i/$adminAttempts rejected (${e.message})")
            }
        }
        
        Log.d(tag, "  PINs blocked; calling terminateAndActivate()…")
        status("Resetting OpenPGP applet…")
        session.terminateAndActivate()
        
        Log.d(tag, "  Reset done; re-selecting application…")
        session.reselect()
        status("OpenPGP applet factory-reset")
        Log.i(tag, "forceWipe() complete")
    }

    fun buildSlotClearTemplate(ref: KeyRef): ByteArray {
        val crt = ref.crt
        return byteArrayOf(0x4D, crt.size.toByte()) + crt
    }

    fun clearSlot(session: OpenPgpSession, ref: KeyRef, tag: String) {
        Log.d(tag, "clearSlot(${ref.name})…")
        try {
            session.putRawKeyTemplate(buildSlotClearTemplate(ref))
            Log.d(tag, "clearSlot(${ref.name}) — slot removed (SW=9000)")
        } catch (e: ApduException) {
            if (e.sw == SW.INCORRECT_PARAMETERS) {
                Log.d(tag, "clearSlot(${ref.name}) — slot was already empty (SW=6A80)")
            } else {
                throw e
            }
        }
    }
}
