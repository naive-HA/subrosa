package acab.naiveha.subrosa.ui.openpgp

import android.util.Log
import com.yubico.yubikit.openpgp.Do
import com.yubico.yubikit.openpgp.OpenPgpSession

object YubiKeyPgpWriter : OpenPgpWriter {

    private const val TAG = "YubiKeyPgpWriter"

    override fun program(
        session: OpenPgpSession,
        bundle: ImportBundle,
        adminPin: CharArray,
        status: (String) -> Unit,
    ): String? {
        Log.i(TAG, "program() — slots: ${bundle.slots.map { it.ref.name }} " +
                   "hasName=${bundle.nameBytes.isNotEmpty()} " +
                   "hasLogin=${bundle.loginBytes.isNotEmpty()} " +
                   "adminPinLength=${adminPin.size}")
        var succeeded = false
        try {
            Log.d(TAG, "Calling verifyAdminPin() (PIN length=${adminPin.size})…")
            status("Verifying admin PIN…")
            session.verifyAdminPin(adminPin)
            Log.i(TAG, "verifyAdminPin() succeeded")
            status("Admin PIN verified")

            OpenPgpWriterUtils.factoryResetAndRestorePin(session, adminPin, TAG, status)

            if (bundle.nameBytes.isNotEmpty()) {
                Log.d(TAG, "Writing DO.NAME (${bundle.nameBytes.size} bytes)…")
                session.putData(Do.NAME, bundle.nameBytes)
                Log.d(TAG, "DO.NAME written")
                status("NAME set")
            }
            if (bundle.loginBytes.isNotEmpty()) {
                Log.d(TAG, "Writing DO.LOGIN_DATA (${bundle.loginBytes.size} bytes)…")
                session.putData(Do.LOGIN_DATA, bundle.loginBytes)
                Log.d(TAG, "DO.LOGIN_DATA written")
                status("LOGIN_DATA set")
            }

            for (slot in bundle.slots) {
                Log.d(TAG, "Writing slot ${slot.ref.name} — " +
                           "fingerprintLength=${slot.fingerprint.size} " +
                           "creationTimestamp=${slot.creationTimestamp}")
                status("Writing slot ${slot.ref.name}…")

                Log.d(TAG, "  clearSlot(${slot.ref.name})…")
                OpenPgpWriterUtils.clearSlot(session, slot.ref, TAG)
                status("Slot ${slot.ref.name} cleared")

                Log.d(TAG, "  putKey(${slot.ref.name})…")
                session.putKey(slot.ref, slot.privateKeyValues)
                Log.d(TAG, "  putKey(${slot.ref.name}) done")
                status("Key material written for ${slot.ref.name}")

                Log.d(TAG, "  setFingerprint(${slot.ref.name})…")
                session.setFingerprint(slot.ref, slot.fingerprint)
                Log.d(TAG, "  setFingerprint(${slot.ref.name}) done")
                status("Fingerprint set for ${slot.ref.name}")

                Log.d(TAG, "  setGenerationTime(${slot.ref.name}, ${slot.creationTimestamp})…")
                session.setGenerationTime(slot.ref, slot.creationTimestamp)
                Log.d(TAG, "  setGenerationTime(${slot.ref.name}) done")
                status("Generation time set for ${slot.ref.name}")

                Log.i(TAG, "Slot ${slot.ref.name} programmed ✓")
            }

            val slotNames = bundle.slots.joinToString(", ") { it.ref.name }
            Log.i(TAG, "program() complete — $slotNames")
            status(OpenPgpWriter.WRITE_COMPLETE_STATUS)
            succeeded = true
            return null

        } catch (e: Exception) {
            Log.e(TAG, "program() FAILED: ${e::class.simpleName}: ${e.message}", e)
            throw e
        } finally {
            adminPin.fill('\u0000')
            bundle.destroy()
            Log.d(TAG, "adminPin zeroed and bundle destroyed")
            if (!succeeded) status("")
        }
    }

    override fun wipe(session: OpenPgpSession, status: (String) -> Unit): String? {
        Log.i(TAG, "wipe() — calling session.reset()")
        var succeeded = false
        try {
            status("Resetting YubiKey…")
            session.reset()
            Log.i(TAG, "wipe() complete")
            status(OpenPgpWriter.WIPE_COMPLETE_STATUS)
            succeeded = true
            return null
        } finally {
            if (!succeeded) status("")
        }
    }
}
