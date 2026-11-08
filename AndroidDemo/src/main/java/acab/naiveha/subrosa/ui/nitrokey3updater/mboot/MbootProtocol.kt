package acab.naiveha.subrosa.ui.nitrokey3updater.mboot

import java.io.IOException

internal object MbootProtocol {
    const val REPORT_CMD_OUT = 0x01
    const val REPORT_DATA_OUT = 0x02
    const val REPORT_CMD_IN = 0x03
    const val REPORT_DATA_IN = 0x04

    const val FRAME_HEADER_SIZE = 4

    const val CMD_GET_PROPERTY = 0x07
    const val CMD_RECEIVE_SB_FILE = 0x08
    const val CMD_RESET = 0x0B

    const val FLAG_NONE = 0x00
    const val FLAG_HAS_DATA_PHASE = 0x01

    const val RESPONSE_GENERIC = 0xA0
    const val RESPONSE_GET_PROPERTY = 0xA7

    const val PROPERTY_CURRENT_VERSION = 0x01
    const val PROPERTY_MAX_PACKET_SIZE = 0x0B

    const val DEFAULT_MAX_PACKET_SIZE = 32

    private const val COMMAND_HEADER_SIZE = 4

    fun encodeCommandPacket(tag: Int, flags: Int, vararg params: Int): ByteArray {
        require(params.size <= 0xFF) { "Too many parameters: ${params.size}" }
        val packet = ByteArray(COMMAND_HEADER_SIZE + 4 * params.size)
        packet[0] = tag.toByte()
        packet[1] = flags.toByte()
        packet[2] = 0
        packet[3] = params.size.toByte()
        params.forEachIndexed { index, value -> putIntLe(packet, COMMAND_HEADER_SIZE + 4 * index, value) }
        return packet
    }

    fun encodeFrame(reportId: Int, payload: ByteArray): ByteArray {
        val frame = ByteArray(FRAME_HEADER_SIZE + payload.size)
        val length = encodeFrameInto(frame, reportId, payload, 0, payload.size)
        check(length == frame.size)
        return frame
    }

    fun encodeFrameInto(dst: ByteArray, reportId: Int, src: ByteArray, srcOffset: Int, srcLength: Int): Int {
        require(srcLength in 0..0xFFFF) { "Payload too long: $srcLength" }
        require(dst.size >= FRAME_HEADER_SIZE + srcLength) { "Frame buffer too small" }
        dst[0] = reportId.toByte()
        dst[1] = 0
        dst[2] = (srcLength and 0xFF).toByte()
        dst[3] = ((srcLength shr 8) and 0xFF).toByte()
        System.arraycopy(src, srcOffset, dst, FRAME_HEADER_SIZE, srcLength)
        return FRAME_HEADER_SIZE + srcLength
    }

    fun parseFrame(buffer: ByteArray, length: Int): MbootFrame {
        if (length < FRAME_HEADER_SIZE) {
            throw MbootProtocolException("Short HID report from bootloader ($length bytes)")
        }
        val reportId = buffer[0].toInt() and 0xFF
        val payloadLength = (buffer[2].toInt() and 0xFF) or ((buffer[3].toInt() and 0xFF) shl 8)
        if (payloadLength == 0) {
            return MbootFrame.Abort
        }
        if (FRAME_HEADER_SIZE + payloadLength > length) {
            throw MbootProtocolException(
                "Truncated HID report: header announces $payloadLength bytes, got ${length - FRAME_HEADER_SIZE}"
            )
        }
        val payload = buffer.copyOfRange(FRAME_HEADER_SIZE, FRAME_HEADER_SIZE + payloadLength)
        return if (reportId == REPORT_CMD_IN) parseResponse(payload) else MbootFrame.Data(reportId, payload)
    }

    private fun parseResponse(payload: ByteArray): MbootFrame.Response {
        if (payload.size < COMMAND_HEADER_SIZE) {
            throw MbootProtocolException("Truncated command response (${payload.size} bytes)")
        }
        val tag = payload[0].toInt() and 0xFF
        val flags = payload[1].toInt() and 0xFF
        val announced = payload[3].toInt() and 0xFF
        val available = (payload.size - COMMAND_HEADER_SIZE) / 4
        val count = minOf(announced, available)
        if (count < 1) {
            throw MbootProtocolException("Command response without status (tag 0x${tag.toString(16)})")
        }
        val params = IntArray(count) { getIntLe(payload, COMMAND_HEADER_SIZE + 4 * it) }
        return MbootFrame.Response(tag, flags, params)
    }

    fun putIntLe(dst: ByteArray, offset: Int, value: Int) {
        dst[offset] = value.toByte()
        dst[offset + 1] = (value shr 8).toByte()
        dst[offset + 2] = (value shr 16).toByte()
        dst[offset + 3] = (value shr 24).toByte()
    }

    fun getIntLe(src: ByteArray, offset: Int): Int =
        (src[offset].toInt() and 0xFF) or
            ((src[offset + 1].toInt() and 0xFF) shl 8) or
            ((src[offset + 2].toInt() and 0xFF) shl 16) or
            ((src[offset + 3].toInt() and 0xFF) shl 24)
}

internal sealed class MbootFrame {
    class Response(val tag: Int, val flags: Int, val params: IntArray) : MbootFrame() {
        val status: Int get() = params[0]

        val propertyValues: IntArray?
            get() = if (status == MbootStatus.SUCCESS) params.copyOfRange(1, params.size) else null
    }

    class Data(val reportId: Int, val payload: ByteArray) : MbootFrame()

    object Abort : MbootFrame()
}

internal class MbootProtocolException(message: String) : IOException(message)

internal class MbootStatusException(val status: Int, message: String) : IOException(message)

internal object MbootStatus {
    const val SUCCESS = 0
    const val ABORT_DATA_PHASE = 10002
    const val ROMLDR_PENDING_JUMP_COMMAND = 10119
    const val ROMLDR_PENDING_RESET_COMMAND = 10120

    fun isSuccessfulCompletion(status: Int): Boolean =
        status == SUCCESS || status == ROMLDR_PENDING_JUMP_COMMAND || status == ROMLDR_PENDING_RESET_COMMAND

    private val labels: Map<Int, String> = mapOf(
        0 to "Success",
        1 to "Fail",
        2 to "Read only error",
        3 to "Out of range error",
        4 to "Invalid argument error",
        5 to "Timeout error",
        6 to "No transfer in progress",
        100 to "Flash driver: size error",
        101 to "Flash driver: alignment error",
        102 to "Flash driver: address error",
        103 to "Flash driver: access error",
        104 to "Flash driver: protection violation",
        105 to "Flash driver: command failure",
        106 to "Flash driver: unknown property",
        107 to "Flash driver: erase key mismatch",
        108 to "Flash driver: region execute only",
        109 to "Flash driver: execute-in-RAM function not ready",
        111 to "Flash driver: command not supported",
        112 to "Flash driver: read-only property",
        113 to "Flash driver: invalid property value",
        114 to "Flash driver: invalid speculation option",
        116 to "Flash driver: ECC error",
        117 to "Flash driver: compare error",
        118 to "Flash driver: loss of regulation",
        119 to "Flash driver: invalid wait state cycles",
        132 to "Flash driver: out of date CFPA page",
        133 to "Flash driver: blank IFR page data",
        134 to "Flash driver: encrypted regions erase not done at once",
        135 to "Flash driver: program verification not allowed",
        136 to "Flash driver: hash check error",
        137 to "Flash driver: sealed PFR region",
        138 to "Flash driver: PFR region write broken",
        139 to "Flash driver: NMPA update not allowed",
        140 to "Flash driver: CMPA cfg direct erase not allowed",
        141 to "Flash driver: PFR bank is locked",
        148 to "Flash driver: CFPA scratch page invalid",
        149 to "Flash driver: CFPA version rollback disallowed",
        150 to "Flash driver: flash memory hiding read not allowed",
        151 to "Flash driver: modify protected area disallowed",
        152 to "Flash driver: command operation in progress",
        10000 to "Unknown command",
        10001 to "Security violation",
        10002 to "Data phase aborted",
        10003 to "Ping error",
        10004 to "No response from device",
        10005 to "No response expected",
        10006 to "Unsupported command",
        10100 to "ROM loader: section overrun",
        10101 to "ROM loader: signature error",
        10102 to "ROM loader: section length error",
        10103 to "ROM loader: unencrypted only",
        10104 to "ROM loader: end of file reached",
        10105 to "ROM loader: checksum error",
        10106 to "ROM loader: CRC32 error",
        10107 to "ROM loader: unknown command",
        10108 to "ROM loader: ID not found",
        10109 to "ROM loader: data underrun",
        10110 to "ROM loader: jump returned",
        10111 to "ROM loader: call failed",
        10112 to "ROM loader: key not found",
        10113 to "ROM loader: secure only",
        10114 to "ROM loader: reset returned",
        10115 to "ROM loader: rollback blocked",
        10116 to "ROM loader: invalid section MAC count",
        10117 to "ROM loader: unexpected command",
        10118 to "ROM loader: bad SBKEK",
        10119 to "ROM loader: pending jump command",
        10120 to "ROM loader: pending reset command",
    )

    private val hints: Map<Int, String> = mapOf(
        10101 to "The bootloader rejected the firmware signature. Use an official firmware release.",
        10113 to "The bootloader only accepts signed firmware.",
        10115 to "The bootloader refuses to install an older firmware version than the one on the device.",
        149 to "The bootloader refuses to install an older firmware version than the one on the device.",
        10118 to "This firmware file was not built for this device.",
        10112 to "This firmware file was not built for this device.",
        10105 to "The firmware file is damaged. Import the archive again.",
        10106 to "The firmware file is damaged. Import the archive again.",
        10109 to "The firmware file is incomplete. Import the archive again.",
    )

    fun label(status: Int): String {
        val name = labels[status]
        return if (name != null) "$name ($status)" else "status $status (0x${status.toUInt().toString(16)})"
    }

    fun describe(status: Int): String {
        val hint = hints[status]
        return if (hint != null) "${label(status)}. $hint" else label(status)
    }
}
