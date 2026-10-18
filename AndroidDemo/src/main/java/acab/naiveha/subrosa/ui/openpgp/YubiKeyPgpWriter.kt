package acab.naiveha.subrosa.ui.openpgp

import android.util.Log
import com.yubico.yubikit.core.keys.PrivateKeyValues
import com.yubico.yubikit.openpgp.KeyRef
import com.yubico.yubikit.openpgp.OpenPgpSession

object YubiKeyPgpWriter : OpenPgpWriter {

    private const val TAG = "YubiKeyPgpWriter"

    override fun validate(session: OpenPgpSession, bundle: ImportBundle): List<String> {
        val problems = OpenPgpWriterUtils.validateCommon(bundle).toMutableList()
        val flaggedSlots = mutableSetOf<KeyRef>()

        for (slot in bundle.slots) {
            val reason = when (val values = slot.privateKeyValues) {
                is PrivateKeyValues.Rsa -> if (values.bitLength > 2048 && !session.supports(OpenPgpSession.FEATURE_RSA4096_KEYS)) {
                    "this device's firmware does not support RSA keys above 2048 bits"
                } else null
                is PrivateKeyValues.Ec -> if (!session.supports(OpenPgpSession.FEATURE_EC_KEYS)) {
                    "this device's firmware does not support elliptic-curve keys"
                } else null
                else -> "unsupported private key type ${values::class.simpleName}"
            }
            if (reason != null) {
                problems += "${slot.ref.name}: $reason"
                flaggedSlots += slot.ref
            }
        }

        val algorithmInfo = try {
            session.getAlgorithmInformation()
        } catch (e: Exception) {
            Log.d(TAG, "getAlgorithmInformation() unavailable (${e::class.simpleName}: ${e.message}) — " +
                "relying on FEATURE_ checks only")
            null
        }
        if (algorithmInfo != null) {
            for (slot in bundle.slots) {
                if (slot.ref in flaggedSlots) continue // already flagged above; avoid a redundant message
                val supportedForSlot = algorithmInfo[slot.ref].orEmpty()
                if (supportedForSlot.none { it.matches(slot.privateKeyValues) }) {
                    val label = when (val v = slot.privateKeyValues) {
                        is PrivateKeyValues.Rsa -> "RSA-${v.bitLength}"
                        is PrivateKeyValues.Ec -> v.curveParams.name
                        else -> v::class.simpleName.toString()
                    }
                    problems += "${slot.ref.name}: this device does not list $label as a supported algorithm for this slot"
                }
            }
        }
        return problems
    }

    override fun program(
        session: OpenPgpSession,
        bundle: ImportBundle,
        adminPin: CharArray,
        userPin: CharArray,
        status: (String) -> Unit,
    ): String? {
        validate(session, bundle).takeIf { it.isNotEmpty() }?.let {
            throw IllegalArgumentException(it.joinToString("\n"))
        }
        return OpenPgpWriterUtils.programCommon(session, bundle, adminPin, userPin, TAG, status) { s, slot, tag, st ->
            Log.d(tag, "  putKey(${slot.ref.name})…")
            s.putKey(slot.ref, slot.privateKeyValues)
            Log.d(tag, "  putKey(${slot.ref.name}) done")
            st("Key material written for ${slot.ref.name}")
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
