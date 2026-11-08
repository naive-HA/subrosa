package acab.naiveha.subrosa.ui.openpgp

import com.yubico.yubikit.openpgp.OpenPgpSession

object Nitrokey2PgpWriter : OpenPgpWriter {

    private const val TAG = "Nitrokey2PgpWriter"

    private const val OMIT_EC_PUBLIC_KEY_FOR_NIST_CURVES = true

    override fun validate(session: OpenPgpSession, bundle: ImportBundle): List<String> =
        ManualApduKeyWriter.validate(session, bundle, TAG, OMIT_EC_PUBLIC_KEY_FOR_NIST_CURVES)

    override fun program(
        session: OpenPgpSession,
        bundle: ImportBundle,
        adminPin: CharArray,
        userPin: CharArray,
        status: (String) -> Unit,
    ): String? {
        requireValid(session, bundle)
        session.forceExtendedApdusIfSupported()
        return ManualApduKeyWriter.program(
            session, bundle, adminPin, userPin, TAG, status,
            clearSlotBeforeWrite = false,
            omitEcPublicKeyForNistCurves = OMIT_EC_PUBLIC_KEY_FOR_NIST_CURVES,
        )
    }

    override fun wipe(session: OpenPgpSession, status: (String) -> Unit): String? =
        ManualApduKeyWriter.wipe(session, TAG, status)
}
