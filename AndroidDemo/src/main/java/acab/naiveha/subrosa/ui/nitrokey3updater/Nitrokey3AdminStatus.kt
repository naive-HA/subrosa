package acab.naiveha.subrosa.ui.nitrokey3updater

import java.io.ByteArrayOutputStream
import java.io.IOException

internal fun interface ApduChannel {
    @Throws(IOException::class)
    fun transmit(apdu: ByteArray): ByteArray
}

internal class AdminStatus(
    val initStatus: Int,
    val internalFreeBlocks: Int?,
    val externalFreeBlocks: Int?,
)

internal sealed interface StatusRead {
    class Value(val status: AdminStatus) : StatusRead

    object NotSupported : StatusRead

    class Failed(val reason: String) : StatusRead
}

internal object Nitrokey3AdminStatus {

    private val ADMIN_AID = byteArrayOf(0xA0.toByte(), 0x00, 0x00, 0x08, 0x47, 0x00, 0x00, 0x00, 0x01)

    private val SELECT_ADMIN = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, ADMIN_AID.size.toByte()) + ADMIN_AID

    private val STATUS = byteArrayOf(0x00, 0x80.toByte(), 0x00, 0x00)

    private const val SW_OK = 0x9000
    private const val SW_INS_NOT_SUPPORTED = 0x6D00
    private const val MAX_GET_RESPONSES = 8

    fun query(channel: ApduChannel): StatusRead = try {
        val selected = exchange(channel, SELECT_ADMIN)
        if (selected.sw != SW_OK) {
            StatusRead.Failed("the admin applet could not be selected (SW %04X)".format(selected.sw))
        } else {
            val reply = exchange(channel, STATUS)
            when {
                reply.sw == SW_INS_NOT_SUPPORTED -> StatusRead.NotSupported
                reply.sw != SW_OK -> StatusRead.Failed("the admin applet answered the status command with SW %04X".format(reply.sw))
                else -> parse(reply.data)?.let { StatusRead.Value(it) } ?: StatusRead.Failed("the status reply was empty")
            }
        }
    } catch (e: IOException) {
        StatusRead.Failed("${e.javaClass.simpleName}: ${e.message}")
    } catch (e: RuntimeException) {
        StatusRead.Failed("${e.javaClass.simpleName}: ${e.message}")
    }

    fun parse(reply: ByteArray): AdminStatus? {
        if (reply.isEmpty()) return null
        val initStatus = reply[0].toInt() and 0xFF
        if (reply.size < 4) return AdminStatus(initStatus, null, null)
        val internal = reply[1].toInt() and 0xFF
        val external = ((reply[2].toInt() and 0xFF) shl 8) or (reply[3].toInt() and 0xFF)
        return AdminStatus(initStatus, internal, external)
    }

    private class Reply(val data: ByteArray, val sw: Int)

    private fun exchange(channel: ApduChannel, apdu: ByteArray): Reply {
        val data = ByteArrayOutputStream()
        var raw = channel.transmit(apdu)
        var chained = 0
        while (raw.size >= 2 && (raw[raw.size - 2].toInt() and 0xFF) == 0x61) {
            if (++chained > MAX_GET_RESPONSES) throw IOException("the card kept announcing more data")
            data.write(raw, 0, raw.size - 2)
            val available = raw[raw.size - 1]
            raw = channel.transmit(byteArrayOf(0x00, 0xC0.toByte(), 0x00, 0x00, available))
        }
        if (raw.size < 2) throw IOException("the card sent no status word")
        data.write(raw, 0, raw.size - 2)
        val sw = ((raw[raw.size - 2].toInt() and 0xFF) shl 8) or (raw[raw.size - 1].toInt() and 0xFF)
        return Reply(data.toByteArray(), sw)
    }
}
