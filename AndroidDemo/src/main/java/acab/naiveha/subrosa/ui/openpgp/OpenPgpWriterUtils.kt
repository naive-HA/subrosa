package acab.naiveha.subrosa.ui.openpgp

import android.util.Log
import com.yubico.yubikit.core.smartcard.ApduException
import com.yubico.yubikit.core.smartcard.SW
import com.yubico.yubikit.openpgp.Do
import com.yubico.yubikit.openpgp.KeyRef
import com.yubico.yubikit.openpgp.OpenPgpSession

internal object OpenPgpWriterUtils {
    private const val TAG = "OpenPgpWriterUtils"

    val DEFAULT_ADMIN_PIN: CharArray get() = "12345678".toCharArray()

    val DEFAULT_USER_PIN: CharArray get() = "123456".toCharArray()

    fun factoryResetAndRestorePin(
        session: OpenPgpSession,
        adminPin: CharArray,
        userPin: CharArray,
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
        Log.d(tag, "Re-verified — restoring User PIN from device default…")
        session.changeUserPin(DEFAULT_USER_PIN, userPin)
        status("User PIN restored")
        Log.d(tag, "User PIN restored — setting Signature PIN policy to 'not forced'…")
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

    fun changeAdminPin(
        session: OpenPgpSession,
        currentPin: CharArray,
        newPin: CharArray,
        tag: String,
        status: (String) -> Unit = {},
    ) {
        Log.d(tag, "changeAdminPin() — changing Admin PIN (currentLength=${currentPin.size}, newLength=${newPin.size})…")
        status("Changing Admin PIN…")
        session.changeAdminPin(currentPin, newPin)
        status("Admin PIN changed")
        Log.i(tag, "changeAdminPin() complete")
    }

    fun changeUserPin(
        session: OpenPgpSession,
        currentPin: CharArray,
        newPin: CharArray,
        tag: String,
        status: (String) -> Unit = {},
    ) {
        Log.d(tag, "changeUserPin() — changing User PIN (currentLength=${currentPin.size}, newLength=${newPin.size})…")
        status("Changing User PIN…")
        session.changeUserPin(currentPin, newPin)
        status("User PIN changed")
        Log.i(tag, "changeUserPin() complete")
    }

    fun resetBlockedUserPin(
        session: OpenPgpSession,
        adminPin: CharArray,
        newUserPin: CharArray,
        tag: String,
        status: (String) -> Unit = {},
    ) {
        Log.d(tag, "resetBlockedUserPin() — verifying Admin PIN (length=${adminPin.size})…")
        status("Verifying Admin PIN…")
        session.verifyAdminPin(adminPin)
        status("Admin PIN verified")
        Log.d(tag, "Admin PIN verified — resetting User PIN (newLength=${newUserPin.size})…")
        status("Resetting User PIN…")
        session.resetPin(newUserPin, null)
        status("User PIN reset")
        Log.i(tag, "resetBlockedUserPin() complete")
    }

    fun programCommon(
        session: OpenPgpSession,
        bundle: ImportBundle,
        adminPin: CharArray,
        userPin: CharArray,
        tag: String,
        status: (String) -> Unit = {},
        writeKeyMaterial: (session: OpenPgpSession, slot: SlotData, tag: String, status: (String) -> Unit) -> Unit,
    ): String? {
        Log.i(tag, "program() — slots: ${bundle.slots.map { it.ref.name }} " +
                   "hasName=${bundle.nameBytes.isNotEmpty()} " +
                   "hasLogin=${bundle.loginBytes.isNotEmpty()} " +
                   "adminPinLength=${adminPin.size} " +
                   "userPinLength=${userPin.size}")
        var succeeded = false
        try {
            Log.d(tag, "Calling verifyAdminPin() (PIN length=${adminPin.size})…")
            status("Verifying admin PIN…")
            session.verifyAdminPin(adminPin)
            Log.i(tag, "verifyAdminPin() succeeded")
            status("Admin PIN verified")

            factoryResetAndRestorePin(session, adminPin, userPin, tag, status)

            if (bundle.nameBytes.isNotEmpty()) {
                Log.d(tag, "Writing DO.NAME (${bundle.nameBytes.size} bytes)…")
                session.putData(Do.NAME, bundle.nameBytes)
                Log.d(tag, "DO.NAME written")
                status("NAME set")
            }
            if (bundle.loginBytes.isNotEmpty()) {
                Log.d(tag, "Writing DO.LOGIN_DATA (${bundle.loginBytes.size} bytes)…")
                session.putData(Do.LOGIN_DATA, bundle.loginBytes)
                Log.d(tag, "DO.LOGIN_DATA written")
                status("LOGIN_DATA set")
            }

            for (slot in bundle.slots) {
                Log.d(tag, "Writing slot ${slot.ref.name} — " +
                           "fingerprintLength=${slot.fingerprint.size} " +
                           "creationTimestamp=${slot.creationTimestamp}")
                status("Writing slot ${slot.ref.name}…")

                Log.d(tag, "  clearSlot(${slot.ref.name})…")
                clearSlot(session, slot.ref, tag)
                status("Slot ${slot.ref.name} cleared")

                writeKeyMaterial(session, slot, tag, status)

                Log.d(tag, "  setFingerprint(${slot.ref.name})…")
                session.setFingerprint(slot.ref, slot.fingerprint)
                Log.d(tag, "  setFingerprint(${slot.ref.name}) done")
                status("Fingerprint set for ${slot.ref.name}")

                Log.d(tag, "  setGenerationTime(${slot.ref.name}, ${slot.creationTimestamp})…")
                session.setGenerationTime(slot.ref, slot.creationTimestamp)
                Log.d(tag, "  setGenerationTime(${slot.ref.name}) done")
                status("Generation time set for ${slot.ref.name}")

                Log.i(tag, "Slot ${slot.ref.name} programmed ✓")
            }

            val slotNames = bundle.slots.joinToString(", ") { it.ref.name }
            Log.i(tag, "program() complete — $slotNames")
            status(OpenPgpWriter.WRITE_COMPLETE_STATUS)
            succeeded = true
            return null

        } catch (e: Exception) {
            Log.e(tag, "program() FAILED: ${e::class.simpleName}: ${e.message}", e)
            throw e
        } finally {
            adminPin.fill('\u0000')
            userPin.fill('\u0000')
            bundle.destroy()
            Log.d(tag, "adminPin and userPin zeroed and bundle destroyed")
            if (!succeeded) status("")
        }
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
            if (e.sw == SW.INCORRECT_PARAMETERS /* 0x6A80 */) {
                Log.d(tag, "clearSlot(${ref.name}) — slot was already empty (SW=6A80)")
            } else {
                throw e
            }
        }
    }
}
