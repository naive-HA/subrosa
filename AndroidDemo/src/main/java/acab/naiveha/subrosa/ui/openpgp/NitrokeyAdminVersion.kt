package acab.naiveha.subrosa.ui.openpgp

import com.yubico.yubikit.core.smartcard.SmartCardConnection

internal object NitrokeyAdminVersion {

    private val ADMIN_AID = byteArrayOf(
        0xa0.toByte(), 0x00, 0x00, 0x08, 0x47, 0x00, 0x00, 0x00, 0x01
    )

    private const val INS_ADMIN_VERSION: Byte = 0x61.toByte()

    fun query(conn: SmartCardConnection): String? {
        return try {
            val selectApdu = byteArrayOf(0x00, 0xa4.toByte(), 0x04, 0x00, ADMIN_AID.size.toByte()) + ADMIN_AID
            var resp = sendFull(conn, selectApdu)
            if (sw(resp) != 0x9000) return null

            resp = sendFull(conn, byteArrayOf(0x00, INS_ADMIN_VERSION, 0x00, 0x01, 0x01, 0x01))
            if (sw(resp) != 0x9000) return null

            parseVersion(resp.copyOf(resp.size - 2)) // strip SW
        } catch (e: Exception) {
            null
        }
    }

    private fun parseVersion(data: ByteArray): String {
        if (data.size == 4) {
            val v = ((data[0].toLong() and 0xFF) shl 24) or
                    ((data[1].toLong() and 0xFF) shl 16) or
                    ((data[2].toLong() and 0xFF) shl 8) or
                    (data[3].toLong() and 0xFF)
            val major = (v shr 22).toInt()
            val minor = ((v shr 6) and 0xFFFFL).toInt()
            val patch = (v and 0x3FL).toInt()
            return "v$major.$minor.$patch"
        }
        return try {
            data.toString(Charsets.UTF_8).trim()
        } catch (e: Exception) {
            data.joinToString("") { "%02X".format(it) }
        }
    }

    private fun sendFull(conn: SmartCardConnection, apdu: ByteArray): ByteArray {
        var resp = conn.sendAndReceive(apdu)
        while (resp.size >= 2 && (resp[resp.size - 2].toInt() and 0xFF) == 0x61) {
            val le = resp[resp.size - 1].toInt() and 0xFF
            resp = conn.sendAndReceive(byteArrayOf(0x00, 0xc0.toByte(), 0x00, 0x00, le.toByte()))
        }
        return resp
    }

    private fun sw(resp: ByteArray): Int {
        if (resp.size < 2) return 0
        return ((resp[resp.size - 2].toInt() and 0xFF) shl 8) or
                (resp[resp.size - 1].toInt() and 0xFF)
    }
}
