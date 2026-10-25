package acab.naiveha.subrosa.ui.openpgp

import acab.naiveha.subrosa.BuildConfig
import android.util.Log
import com.yubico.yubikit.core.smartcard.ApduException
import com.yubico.yubikit.core.smartcard.SW
import com.yubico.yubikit.openpgp.Do
import com.yubico.yubikit.openpgp.OpenPgpSession

internal fun OpenPgpSession.probeCapabilities(tag: String): CardAlgorithmInfo? {
    if (BuildConfig.DEBUG) logCardIdentity(tag)
    val data = readAlgorithmInformation(tag) ?: return null
    val info = CardAlgorithmInfo.parse(data)
    if (BuildConfig.DEBUG) {
        Log.i(tag, "card: DO 0xFA (algorithm information)=${data.toHexString(HexFormat.UpperCase)}")
        info.toLines().forEach { Log.i(tag, "card: $it") }
    }
    return info
}

private fun OpenPgpSession.readAlgorithmInformation(tag: String): ByteArray? {
    try {
        return getData(Do.ALGORITHM_INFORMATION)
    } catch (e: ApduException) {
        if (e.sw != SW.WRONG_LENGTH) {
            Log.i(tag, "card: DO 0xFA (algorithm information) not provided (SW=0x%04X)".format(e.sw))
            return null
        }
    }

    if (cachedApplicationRelatedData.extendedLengthInfo == null) {
        Log.i(tag, "card: DO 0xFA (algorithm information) answered SW=0x6700, and the card does not declare " +
            "extended length (DO 0x7F66), so it is not asked again")
        return null
    }
    val le = extendedCapabilities.specialDoMaxLength
    if (le <= 0) {
        Log.i(tag, "card: DO 0xFA (algorithm information) answered SW=0x6700, and the card declares no special DO " +
            "length to retry with")
        return null
    }
    Log.i(tag, "card: DO 0xFA (algorithm information) answered SW=0x6700; retrying with Le=$le")
    forceExtendedApdusIfSupported()
    return try {
        getData(Do.ALGORITHM_INFORMATION, le)
    } catch (e: ApduException) {
        Log.i(tag, "card: DO 0xFA (algorithm information) not provided with Le=$le either (SW=0x%04X)".format(e.sw))
        null
    } catch (e: IllegalArgumentException) {
        Log.i(tag, "card: DO 0xFA (algorithm information) not read: Le=$le needs an extended APDU, " +
            "which this connection cannot send")
        null
    }
}

internal fun OpenPgpSession.capabilityProblems(bundle: ImportBundle, tag: String): List<String> {
    val declared = probeCapabilities(tag)?.takeIf { it.isUsable }
    if (declared == null) {
        Log.i(tag, "card: declares no usable algorithm list; keys are not checked against its capabilities")
        return emptyList()
    }
    val supported = declared.toSupportedAlgorithms()
    return bundle.slots.mapNotNull { slot ->
        val key = slot.privateKeyValues
        if (declared.lists(slot.ref, key)) {
            Log.d(tag, "card: lists ${CardAlgorithmInfo.describe(key)} for ${slot.ref.name}")
            return@mapNotNull null
        }
        val listed = supported.linesFor(slot.ref)
        Log.w(tag, "card: does not list ${CardAlgorithmInfo.describe(key)} for ${slot.ref.name}")
        "${slot.ref.name}: this device does not list ${CardAlgorithmInfo.describe(key)} as a supported algorithm " +
            "for this slot " +
            if (listed.isEmpty()) "(it lists nothing for this slot)" else "(it lists ${listed.joinToString("; ")})"
    }
}

private fun OpenPgpSession.logCardIdentity(tag: String) {
    try {
        val spec = aid.version
        Log.i(tag, "card: spec=${spec.first}.${spec.second} manufacturer=0x${"%04X".format(aid.manufacturer)} " +
            "session.version=$version")
        Log.i(tag, "card: extendedCapabilities=${extendedCapabilities.flags}")
    } catch (e: Exception) {
        Log.w(tag, "card: identity unavailable: ${e::class.simpleName}: ${e.message}")
    }
}
