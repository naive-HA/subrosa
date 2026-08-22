package acab.naiveha.subrosa.by.naiveha.ui.openpgp

import com.yubico.yubikit.openpgp.OpenPgpSession

object NitrokeyPgpWriter : OpenPgpWriter {

    private const val TAG = "NitrokeyPgpWriter"

    override fun program(
        session: OpenPgpSession,
        bundle: ImportBundle,
        adminPin: CharArray,
        userPin: CharArray,
        status: (String) -> Unit,
    ): String? =
        ManualApduKeyWriter.program(
            session, bundle, adminPin, userPin, TAG, status,
            clearSlotBeforeWrite = true,
        )

    override fun wipe(session: OpenPgpSession, status: (String) -> Unit): String? =
        ManualApduKeyWriter.wipe(session, TAG, status)
}
