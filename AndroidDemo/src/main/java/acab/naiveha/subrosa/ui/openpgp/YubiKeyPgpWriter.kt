package acab.naiveha.subrosa.ui.openpgp

import android.util.Log
import com.yubico.yubikit.openpgp.OpenPgpSession

object YubiKeyPgpWriter : OpenPgpWriter {

    private const val TAG = "YubiKeyPgpWriter"

    override fun program(
        session: OpenPgpSession,
        bundle: ImportBundle,
        adminPin: CharArray,
        userPin: CharArray,
        status: (String) -> Unit,
    ): String? = OpenPgpWriterUtils.programCommon(session, bundle, adminPin, userPin, TAG, status) { s, slot, tag, st ->
        Log.d(tag, "  putKey(${slot.ref.name})…")
        s.putKey(slot.ref, slot.privateKeyValues)
        Log.d(tag, "  putKey(${slot.ref.name}) done")
        st("Key material written for ${slot.ref.name}")
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
