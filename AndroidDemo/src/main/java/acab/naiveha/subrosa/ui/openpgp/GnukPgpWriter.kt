package acab.naiveha.subrosa.ui.openpgp

import com.yubico.yubikit.openpgp.OpenPgpSession

object GnukPgpWriter : OpenPgpWriter {

    private const val TAG = "GnukPgpWriter"

    private const val SUPPORTS_ED25519_X25519 = false

    override fun validate(session: OpenPgpSession, bundle: ImportBundle): List<String> =
        OpenPgpWriterUtils.validateCommon(bundle) +
            bundle.slots.mapNotNull { slot ->
                ManualApduKeyWriter.unsupportedReason(slot.privateKeyValues, slot.ref, SUPPORTS_ED25519_X25519)
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
        session.forceExtendedApdusIfSupported()
        return ManualApduKeyWriter.program(
            session, bundle, adminPin, userPin, TAG, status,
            clearSlotBeforeWrite = false,
            supportsEd25519X25519 = SUPPORTS_ED25519_X25519,
            omitEcPublicKeyForNistCurves = true,
        )
    }

    override fun wipe(session: OpenPgpSession, status: (String) -> Unit): String? =
        ManualApduKeyWriter.wipe(session, TAG, status)
}
