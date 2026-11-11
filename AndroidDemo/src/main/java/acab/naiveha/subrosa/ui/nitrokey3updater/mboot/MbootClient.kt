package acab.naiveha.subrosa.ui.nitrokey3updater.mboot

import acab.naiveha.subrosa.ui.nitrokey3updater.mboot.MbootProtocol.CMD_GET_PROPERTY
import acab.naiveha.subrosa.ui.nitrokey3updater.mboot.MbootProtocol.CMD_RECEIVE_SB_FILE
import acab.naiveha.subrosa.ui.nitrokey3updater.mboot.MbootProtocol.CMD_RESET
import acab.naiveha.subrosa.ui.nitrokey3updater.mboot.MbootProtocol.FLAG_HAS_DATA_PHASE
import acab.naiveha.subrosa.ui.nitrokey3updater.mboot.MbootProtocol.FLAG_NONE
import acab.naiveha.subrosa.ui.nitrokey3updater.mboot.MbootProtocol.FRAME_HEADER_SIZE
import acab.naiveha.subrosa.ui.nitrokey3updater.mboot.MbootProtocol.REPORT_CMD_OUT
import acab.naiveha.subrosa.ui.nitrokey3updater.mboot.MbootProtocol.REPORT_DATA_OUT
import java.io.IOException

internal class MbootClient(
    private val transport: MbootTransport,
    private val log: (String) -> Unit = {},
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    private val readBuffer = ByteArray(READ_BUFFER_SIZE)

    var dataPhaseMayBeOpen: Boolean = true
        private set

    var transferStage: TransferStage = TransferStage.NOT_STARTED
        private set

    var bytesSent: Int = 0
        private set

    var lastStatus: Int? = null
        private set

    private var inputDrained = false

    private val receiveSbFileResponse = ExpectedResponse.forCommand(CMD_RECEIVE_SB_FILE)

    private val resetResponse = ExpectedResponse.forCommand(CMD_RESET)

    var drainedReports: Int = 0
        private set

    var skippedReports: Int = 0
        private set

    fun getProperty(propertyTag: Int, memoryIndex: Int = 0): IntArray? {
        val response = command(
            CMD_GET_PROPERTY,
            FLAG_NONE,
            intArrayOf(propertyTag, memoryIndex),
            ExpectedResponse.forCommand(CMD_GET_PROPERTY),
        )
        dataPhaseMayBeOpen = false
        lastStatus = response.status
        val values = response.propertyValues
        if (values == null) {
            log("get-property 0x%02X: %s".format(propertyTag, MbootStatus.label(response.status)))
        }
        return values
    }

    fun receiveSbFile(
        image: ByteArray,
        packetSize: Int,
        pausePoint: Int,
        onProgress: (sent: Int, total: Int) -> Unit,
    ) {
        val total = image.size
        require(total > 0) { "Empty firmware image" }
        requirePacketSize(packetSize)

        log("receive-sb-file: $total bytes in $packetSize byte packets")
        beginTransfer()
        val start = command(CMD_RECEIVE_SB_FILE, FLAG_HAS_DATA_PHASE, intArrayOf(total), receiveSbFileResponse)
        lastStatus = start.status
        if (start.status != MbootStatus.SUCCESS) {
            dataPhaseMayBeOpen = false
            throw MbootStatusException(
                start.status,
                "The bootloader refused to start the update: ${MbootStatus.describe(start.status)}",
            )
        }

        transferStage = TransferStage.SENDING
        val outcome = sendReports(image, total, packetSize, pausePoint, onProgress)
        outcome.failure?.let { throw explainWriteFailure(it, outcome.sent, total) }

        transferStage = TransferStage.FINISHING
        val finalResponse = try {
            readResponse(receiveSbFileResponse, FINAL_RESPONSE_TIMEOUT_MS)
        } catch (e: MbootTimeoutException) {
            throw unconfirmed(e)
        } catch (e: MbootProtocolException) {
            throw unconfirmed(e)
        }
        dataPhaseMayBeOpen = false
        lastStatus = finalResponse.status
        log("receive-sb-file finished: ${MbootStatus.label(finalResponse.status)}")
        if (!MbootStatus.isSuccessfulCompletion(finalResponse.status)) {
            throw MbootStatusException(
                finalResponse.status,
                "The bootloader rejected the firmware: ${MbootStatus.describe(finalResponse.status)}",
            )
        }
        transferStage = TransferStage.FINISHED
    }

    fun sendSbFilePrefix(
        image: ByteArray,
        prefixLength: Int,
        packetSize: Int,
        listenMs: Int,
    ): PrefixTransfer {
        val total = image.size
        require(prefixLength in 1 until total) { "The prefix ($prefixLength bytes) must be shorter than the file ($total bytes)" }
        requirePacketSize(packetSize)

        log("receive-sb-file (prefix only): announcing $total bytes, sending the first $prefixLength in $packetSize byte packets")
        beginTransfer()
        val start = command(CMD_RECEIVE_SB_FILE, FLAG_HAS_DATA_PHASE, intArrayOf(total), receiveSbFileResponse)
        lastStatus = start.status
        if (start.status != MbootStatus.SUCCESS) {
            dataPhaseMayBeOpen = false
            log("receive-sb-file refused: ${MbootStatus.label(start.status)}")
            return PrefixTransfer.Refused(start.status)
        }

        transferStage = TransferStage.SENDING
        val outcome = sendReports(image, prefixLength, packetSize, 0) { _, _ -> }
        val failure = outcome.failure
        if (failure != null) {
            val status = try {
                pollForErrorStatus()
            } catch (_: IOException) {
                null
            }
            log("data write failed after ${outcome.sent} bytes: ${failure.message}")
            return PrefixTransfer.WriteFailed(outcome.sent, outcome.reports, status, failure.message ?: "USB write failed")
        }

        val reply = listenForReply(listenMs)
        dataPhaseMayBeOpen = reply is PrefixReply.Silence
        val phase = if (dataPhaseMayBeOpen) "Bootloader is left in data phase expecting remaining bytes." else "Bootloader has left the data phase."
        log("prefix of ${outcome.sent} bytes sent in ${outcome.reports} reports, bootloader reply: $reply. $phase")
        return PrefixTransfer.Sent(outcome.sent, outcome.reports, outcome.longestFrame, reply)
    }

    fun abortDataPhase(budgetMs: Int? = null): AbortOutcome {
        log("abortDataPhase() — sending empty data report to abort active write/data phase")
        val deadline = budgetMs?.let { ReadDeadline(it) }
        try {
            drainStaleInputOnce()
            val emptyFrame = MbootProtocol.encodeFrame(REPORT_DATA_OUT, ByteArray(0))
            transport.write(emptyFrame, emptyFrame.size, budgetMs ?: WRITE_TIMEOUT_MS)
        } catch (e: IOException) {
            log("abortDataPhase write failed: ${e.message}")
            return AbortOutcome.NOT_SENT
        }
        val answerTimeoutMs = if (deadline != null) {
            deadline.remainingMs().coerceAtLeast(MIN_ABORT_ANSWER_MS).toInt()
        } else {
            DIAGNOSE_TIMEOUT_MS
        }
        return try {
            val response = readResponse(receiveSbFileResponse, answerTimeoutMs)
            dataPhaseMayBeOpen = false
            log("abortDataPhase response: tag=0x%02X status=%d (%s)".format(
                response.tag, response.status, MbootStatus.label(response.status)
            ))
            AbortOutcome.ACKNOWLEDGED
        } catch (e: IOException) {
            log("abortDataPhase: no response to abort frame: ${e.message}")
            AbortOutcome.NO_ANSWER
        }
    }

    fun reset(): Boolean {
        if (dataPhaseMayBeOpen) {
            log("reset() starting — a data phase may still be open, aborting it first")
            abortDataPhase()
        } else {
            log("reset() starting — bootloader is in command mode, no abort needed")
        }

        log("sending CMD_RESET frame")
        try {
            drainStaleInputOnce()
            writeFrame(REPORT_CMD_OUT, MbootProtocol.encodeCommandPacket(CMD_RESET, FLAG_NONE))
            log("reset command frame sent")
        } catch (e: IOException) {
            log("reset command could not be sent: ${e.message}")
            return false
        }
        return try {
            val response = readResponse(resetResponse, RESET_RESPONSE_TIMEOUT_MS)
            lastStatus = response.status
            log("reset response: tag=0x%02X status=%d (%s) params=%s".format(
                response.tag, response.status, MbootStatus.label(response.status), response.params.joinToString()
            ))
            if (response.status != MbootStatus.SUCCESS) {
                log("reset command returned error status ${response.status} (${MbootStatus.label(response.status)}). " +
                    "Re-sending CMD_RESET once...")
                writeFrame(REPORT_CMD_OUT, MbootProtocol.encodeCommandPacket(CMD_RESET, FLAG_NONE))
                val secondResponse = readResponse(resetResponse, RESET_RESPONSE_TIMEOUT_MS)
                lastStatus = secondResponse.status
                log("second reset response: tag=0x%02X status=%d (%s)".format(
                    secondResponse.tag, secondResponse.status, MbootStatus.label(secondResponse.status)
                ))
                secondResponse.status == MbootStatus.SUCCESS
            } else {
                true
            }
        } catch (e: IOException) {
            log("no answer to reset (device is restarting): ${e.message}")
            true
        }
    }

    private fun beginTransfer() {
        dataPhaseMayBeOpen = true
        transferStage = TransferStage.STARTING
        bytesSent = 0
    }

    private fun requirePacketSize(packetSize: Int) {
        require(packetSize in 1..(transport.maxFrameSize - FRAME_HEADER_SIZE)) {
            "Packet size $packetSize does not fit into ${transport.maxFrameSize} byte reports"
        }
    }

    private class SendOutcome(val sent: Int, val reports: Int, val longestFrame: Int, val failure: IOException?)

    private fun sendReports(
        image: ByteArray,
        limit: Int,
        packetSize: Int,
        pausePoint: Int,
        onProgress: (sent: Int, total: Int) -> Unit,
    ): SendOutcome {
        val frame = ByteArray(FRAME_HEADER_SIZE + packetSize)
        var sent = 0
        var reports = 0
        var longestFrame = 0
        var paused = false
        while (sent < limit) {
            val count = minOf(packetSize, limit - sent)
            val frameLength = MbootProtocol.encodeFrameInto(frame, REPORT_DATA_OUT, image, sent, count)
            try {
                transport.write(frame, frameLength, WRITE_TIMEOUT_MS)
            } catch (e: IOException) {
                return SendOutcome(sent, reports, longestFrame, e)
            }
            sent += count
            reports++
            bytesSent = sent
            longestFrame = maxOf(longestFrame, frameLength)
            onProgress(sent, image.size)
            if (!paused && pausePoint > 0 && sent > pausePoint) {
                sleep(PAUSE_AFTER_HEADER_MS)
                paused = true
            }
        }
        return SendOutcome(sent, reports, longestFrame, null)
    }

    private fun listenForReply(listenMs: Int): PrefixReply {
        var sawAbort = false
        var deadline = ReadDeadline(listenMs)
        repeat(MAX_REPORTS_TO_SKIP) {
            val length = try {
                transport.readWithin(readBuffer, deadline)
            } catch (_: MbootTimeoutException) {
                return if (sawAbort) PrefixReply.Aborted(null) else PrefixReply.Silence
            }
            if (length == 0) return@repeat
            when (val frame = MbootProtocol.parseFrame(readBuffer, length)) {
                is MbootFrame.Response -> {
                    log("report after the prefix: ${MbootStatus.label(frame.status)}")
                    return if (MbootStatus.isSuccessfulCompletion(frame.status)) {
                        PrefixReply.Unexpected(frame.status)
                    } else {
                        PrefixReply.Aborted(frame.status)
                    }
                }
                is MbootFrame.Abort -> {
                    log("abort report after the prefix")
                    if (!sawAbort) deadline = ReadDeadline(DIAGNOSE_TIMEOUT_MS)
                    sawAbort = true
                }
                is MbootFrame.Data -> log("data report ${frame.reportId} after the prefix")
            }
        }
        return if (sawAbort) PrefixReply.Aborted(null) else PrefixReply.Silence
    }

    private fun command(
        tag: Int,
        flags: Int,
        params: IntArray,
        expected: ExpectedResponse,
        timeoutMs: Int = COMMAND_TIMEOUT_MS,
    ): MbootFrame.Response {
        drainStaleInputOnce()
        writeFrame(REPORT_CMD_OUT, MbootProtocol.encodeCommandPacket(tag, flags, *params))
        return readResponse(expected, timeoutMs)
    }

    private fun writeFrame(reportId: Int, packet: ByteArray) {
        val frame = MbootProtocol.encodeFrame(reportId, packet)
        transport.write(frame, frame.size, WRITE_TIMEOUT_MS)
    }

    private fun readResponse(expected: ExpectedResponse, timeoutMs: Int): MbootFrame.Response {
        val deadline = ReadDeadline(timeoutMs)
        repeat(MAX_REPORTS_TO_SKIP) {
            val length = transport.readWithin(readBuffer, deadline)
            if (length == 0) return@repeat
            when (val frame = MbootProtocol.parseFrame(readBuffer, length)) {
                is MbootFrame.Response -> {
                    if (expected.matches(frame)) {
                        log("readResponse: received response frame (tag=0x%02X, status=%d (%s), params=%s)".format(
                            frame.tag, frame.status, MbootStatus.label(frame.status), frame.params.joinToString()
                        ))
                        return frame
                    }
                    skippedReports++
                    log("readResponse: skipping response frame (tag=0x%02X, status=%d (%s), params=%s) while waiting for %s".format(
                        frame.tag, frame.status, MbootStatus.label(frame.status), frame.params.joinToString(), expected
                    ))
                }
                is MbootFrame.Abort -> {
                    skippedReports++
                    log("readResponse: skipping abort report (device aborted active data phase)")
                }
                is MbootFrame.Data -> {
                    skippedReports++
                    log("readResponse: skipping data report ${frame.reportId} (${frame.payload.size} bytes)")
                }
            }
        }
        log("readResponse: gave up after $MAX_REPORTS_TO_SKIP reports without $expected")
        throw MbootProtocolException("The bootloader did not send the response to the command (it sent other reports instead)")
    }

    private fun drainStaleInputOnce() {
        if (inputDrained) return
        drainedReports = transport.drainStaleReports(readBuffer) { length ->
            log("drain: discarding a stale report (${describeStale(length)})")
        }
        inputDrained = true
        log("drained $drainedReports stale report(s) before the first command")
    }

    private fun describeStale(length: Int): String = try {
        when (val frame = MbootProtocol.parseFrame(readBuffer, length)) {
            is MbootFrame.Response -> "response tag=0x%02X params=%s".format(frame.tag, frame.params.joinToString())
            is MbootFrame.Data -> "data report ${frame.reportId}, ${frame.payload.size} bytes"
            is MbootFrame.Abort -> "empty report"
        }
    } catch (_: MbootProtocolException) {
        "$length bytes that are not a valid report"
    }

    private fun unconfirmed(cause: IOException) = IOException(
        "The bootloader did not confirm the update (${cause.message}).",
        cause,
    )

    private fun explainWriteFailure(cause: IOException, sent: Int, total: Int): IOException {
        val status = try {
            pollForErrorStatus()
        } catch (_: IOException) {
            null
        }
        return if (status != null) {
            MbootStatusException(
                status,
                "The bootloader aborted the update after $sent of $total bytes: ${MbootStatus.describe(status)}",
            )
        } else {
            IOException("USB transfer failed after $sent of $total bytes: ${cause.message}", cause)
        }
    }

    private fun pollForErrorStatus(): Int? {
        val deadline = ReadDeadline(DIAGNOSE_TIMEOUT_MS)
        repeat(MAX_REPORTS_TO_SKIP) {
            val length = transport.readWithin(readBuffer, deadline)
            if (length == 0) return@repeat
            val frame = MbootProtocol.parseFrame(readBuffer, length)
            if (frame is MbootFrame.Response) {
                if (!receiveSbFileResponse.matches(frame)) {
                    skippedReports++
                    log("pollForErrorStatus: skipping response frame (tag=0x%02X, params=%s)".format(
                        frame.tag, frame.params.joinToString()
                    ))
                    return@repeat
                }
                val status = frame.status.takeIf { it != MbootStatus.SUCCESS }
                if (status != null) {
                    dataPhaseMayBeOpen = false
                    lastStatus = status
                }
                return status
            }
        }
        return null
    }

    companion object {
        const val READ_BUFFER_SIZE = 512
        const val COMMAND_TIMEOUT_MS = 5_000
        const val WRITE_TIMEOUT_MS = 15_000
        const val FINAL_RESPONSE_TIMEOUT_MS = 30_000
        const val RESET_RESPONSE_TIMEOUT_MS = 2_000
        const val DIAGNOSE_TIMEOUT_MS = 1_000
        const val PAUSE_AFTER_HEADER_MS = 100L
        private const val MIN_ABORT_ANSWER_MS = 50L
        private const val MAX_REPORTS_TO_SKIP = 16
    }
}

internal enum class TransferStage {
    NOT_STARTED,
    STARTING,
    SENDING,
    FINISHING,
    FINISHED,
}

internal enum class AbortOutcome {
    ACKNOWLEDGED,
    NO_ANSWER,
    NOT_SENT,
}

internal sealed interface PrefixTransfer {
    class Refused(val status: Int) : PrefixTransfer

    class Sent(val bytes: Int, val reports: Int, val longestFrame: Int, val reply: PrefixReply) : PrefixTransfer

    class WriteFailed(val bytes: Int, val reports: Int, val status: Int?, val message: String) : PrefixTransfer
}

internal sealed interface PrefixReply {
    object Silence : PrefixReply {
        override fun toString() = "silence"
    }

    class Aborted(val status: Int?) : PrefixReply {
        override fun toString() = "aborted (${status?.let { MbootStatus.label(it) } ?: "no status"})"
    }

    class Unexpected(val status: Int) : PrefixReply {
        override fun toString() = "unexpected response (${MbootStatus.label(status)})"
    }
}
