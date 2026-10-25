package acab.naiveha.subrosa.ui.openpgp

import com.yubico.yubikit.openpgp.OpenPgpSession

object Nitrokey3PgpWriter : OpenPgpWriter {

    private const val TAG = "Nitrokey3PgpWriter"

    override fun validate(session: OpenPgpSession, bundle: ImportBundle): List<String> =
        ManualApduKeyWriter.validate(session, bundle, TAG)

    override fun program(
        session: OpenPgpSession,
        bundle: ImportBundle,
        adminPin: CharArray,
        userPin: CharArray,
        status: (String) -> Unit,
    ): String? {
        requireValid(session, bundle)
        return ManualApduKeyWriter.program(
            session, bundle, adminPin, userPin, TAG, status,
            clearSlotBeforeWrite = true,
        )
    }

    override fun wipe(session: OpenPgpSession, status: (String) -> Unit): String? =
        ManualApduKeyWriter.wipe(session, TAG, status)
}
