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
        includeSupportedAlgorithms: Boolean = false,
        status: (String) -> Unit = {},
    ): OpenPgpCardInfo {
        Log.i(TAG, "read() — starting (useCache=$useCache, includeSupportedAlgorithms=$includeSupportedAlgorithms)")
        var succeeded = false
        try {
            status("Reading card status…")
            val disc = (if (useCache) session.cachedApplicationRelatedData else session.getApplicationRelatedData())
                .discretionary
            val pw = disc.pwStatus

            status("Reading cardholder name…")
            val name = runCatching {
                String(session.getCardholderRelatedData().name, Charsets.UTF_8).trim()
            }.getOrDefault("")

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

            val supportedAlgorithms = if (includeSupportedAlgorithms) {
                status("Reading supported algorithms…")
                readSupportedAlgorithms(session)
            } else {
                null
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
                supportedAlgorithms = supportedAlgorithms,
            )

            Log.i(TAG, "read() complete — v=${info.version} hasName=${info.cardholderName != "—"} " +
                       "slots=${slots.count { it.hasKey }} key(s)")
            status(READ_COMPLETE_STATUS)
            succeeded = true
            return info
        } finally {
            if (!succeeded) status("")
        }
    }

    private fun readSupportedAlgorithms(session: OpenPgpSession): OpenPgpCardInfo.SupportedAlgorithms? = try {
        session.probeCapabilities(TAG)?.toSupportedAlgorithms()
    } catch (e: Exception) {
        Log.i(TAG, "DO 0xFA (algorithm information) unavailable: ${e::class.simpleName}: ${e.message}")
        null
    }
}
