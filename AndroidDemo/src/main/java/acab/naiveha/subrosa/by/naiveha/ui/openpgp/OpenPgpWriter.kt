package acab.naiveha.subrosa.by.naiveha.ui.openpgp

import acab.naiveha.subrosa.by.naiveha.ui.PgpDeviceType
import com.yubico.yubikit.openpgp.OpenPgpSession

interface OpenPgpWriter {

    companion object {
        const val WRITE_COMPLETE_STATUS = "Write complete"

        const val WIPE_COMPLETE_STATUS = "Wipe complete"
    }

    fun program(
        session: OpenPgpSession,
        bundle: ImportBundle,
        adminPin: CharArray,
        userPin: CharArray,
        status: (String) -> Unit = {},
    ): String?

    fun wipe(session: OpenPgpSession, status: (String) -> Unit = {}): String?
}

fun PgpDeviceType?.writer(): OpenPgpWriter = when (this) {
    PgpDeviceType.YUBIKEY -> YubiKeyPgpWriter
    PgpDeviceType.NITROKEY -> NitrokeyPgpWriter
    PgpDeviceType.GNUK     -> GnukPgpWriter
    PgpDeviceType.UNKNOWN,
    null                  -> throw UnsupportedOperationException(
        "No OpenPgpWriter available for an unrecognized device (PgpDeviceType=$this)."
    )
}
