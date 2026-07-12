package acab.naiveha.subrosa.ui.openpgp

import android.util.Log
import com.yubico.yubikit.openpgp.Do
import com.yubico.yubikit.openpgp.KeyRef
import com.yubico.yubikit.openpgp.OpenPgpSession

object OpenPgpReader {
    private const val TAG = "OpenPgpReader"

    const val READ_COMPLETE_STATUS = "Read complete"

    fun read(
        session: OpenPgpSession,
        knownFirmwareVersion: String?,
        useCache: Boolean = false,
        status: (String) -> Unit = {},
    ): OpenPgpCardInfo {
        Log.i(TAG, "read() — starting (useCache=$useCache)")
        var succeeded = false
        try {
            status("Reading card status…")
            val disc = (if (useCache) session.cachedApplicationRelatedData else session.getApplicationRelatedData())
                .discretionary
            val pw = disc.pwStatus

            status("Reading cardholder name…")
            val rawCrdResult = runCatching { session.getData(Do.CARDHOLDER_RELATED_DATA) }
            rawCrdResult.fold(
                onSuccess = { raw ->
                    Log.d(TAG, "getData(Do.CARDHOLDER_RELATED_DATA) raw bytes " +
                               "(${raw.size}): ${raw.joinToString(" ") { "%02X".format(it) }}")
                },
                onFailure = { e ->
                    Log.w(TAG, "getData(Do.CARDHOLDER_RELATED_DATA) raw read FAILED: " +
                               "${e::class.simpleName}: ${e.message}", e)
                }
            )

            val crdResult = runCatching { session.getCardholderRelatedData() }
            crdResult.fold(
                onSuccess = { crd ->
                    Log.d(TAG, "getCardholderRelatedData() parsed OK — " +
                               "name=${crd.name.joinToString(" ") { "%02X".format(it) }} " +
                               "(${crd.name.size} bytes) " +
                               "language=${crd.language.size} bytes sex=${crd.sex}")
                },
                onFailure = { e ->
                    Log.w(TAG, "getCardholderRelatedData() THREW after the raw read above " +
                               "— this is the bug: ${e::class.simpleName}: ${e.message}", e)
                }
            )

            val name = crdResult.mapCatching { crd ->
                String(crd.name, Charsets.UTF_8).trim()
            }.recoverCatching {
                Log.d(TAG, "Falling back to standalone getData(Do.NAME)…")
                val raw = session.getData(Do.NAME)
                Log.d(TAG, "Standalone getData(Do.NAME) unexpectedly succeeded: " +
                           "${raw.size} bytes")
                String(raw, Charsets.UTF_8).trim()
            }.onFailure { e ->
                Log.w(TAG, "Standalone getData(Do.NAME) fallback also failed (expected " +
                           "on YubiKey per its doc comment): ${e::class.simpleName}: ${e.message}")
            }.getOrDefault("")
            Log.i(TAG, "Resolved cardholder name: '$name'")

            status("Reading login data…")
            val login = runCatching {
                String(session.getData(Do.LOGIN_DATA), Charsets.UTF_8).trim()
            }.getOrDefault("")

            val slots = listOf(KeyRef.SIG, KeyRef.DEC, KeyRef.AUT).map { ref ->
                status("Reading slot ${ref.name}…")
                val attr = runCatching { disc.getAlgorithmAttributes(ref) }.getOrNull()
                val fp = runCatching { disc.getFingerprint(ref) }.getOrNull()
                val gt = runCatching { disc.getGenerationTime(ref) }.getOrDefault(0)
                val hasKey = fp?.any { it != 0.toByte() } == true
                OpenPgpCardInfo.SlotInfo(
                    ref = ref,
                    algorithm = attr?.toDisplayString() ?: "—",
                    fingerprint = if (hasKey) fp!!.toFingerprintDisplay() else "—",
                    created = if (gt != 0) gt.toDateDisplay() else "—",
                    hasKey = hasKey,
                )
            }

            val rawVersion = session.version.toString()
            val displayVersion = if (rawVersion == "0.0.0") {
                knownFirmwareVersion ?: rawVersion
            } else {
                rawVersion
            }

            val info = OpenPgpCardInfo(
                version = displayVersion,
                cardholderName = name.fromNameDo(),
                login = login.ifBlank { "—" },
                slots = slots,
                userPinRetries = pw.attemptsUser,
                resetRetries = pw.attemptsReset,
                adminPinRetries = pw.attemptsAdmin,
            )

            Log.i(TAG, "read() complete — v=${info.version} name='${info.cardholderName}' " +
                       "slots=${slots.count { it.hasKey }} key(s)")
            status(READ_COMPLETE_STATUS)
            succeeded = true
            return info
        } finally {
            if (!succeeded) status("")
        }
    }
}
