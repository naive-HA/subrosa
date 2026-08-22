package acab.naiveha.subrosa.by.naiveha.ui.openpgp

import com.yubico.yubikit.openpgp.OpenPgpSession

object GnukPgpWriter : OpenPgpWriter {

    private const val TAG = "GnukPgpWriter"

    override fun program(
        session: OpenPgpSession,
        bundle: ImportBundle,
        adminPin: CharArray,
        userPin: CharArray,
        status: (String) -> Unit,
    ): String? {
        session.forceExtendedApdusIfSupported()
        return ManualApduKeyWriter.program(
            session, bundle, adminPin, userPin, TAG, status,
            clearSlotBeforeWrite = false,
            omitEcPublicKeyForNistCurves = true,
        )
    }

    override fun wipe(session: OpenPgpSession, status: (String) -> Unit): String? =
        ManualApduKeyWriter.wipe(session, TAG, status)
}
