package acab.naiveha.subrosa.ui.nitrokey3updater

import acab.naiveha.subrosa.ui.nitrokey3updater.mboot.MbootClient
import acab.naiveha.subrosa.ui.nitrokey3updater.mboot.MbootFrame
import acab.naiveha.subrosa.ui.nitrokey3updater.mboot.MbootProtocol
import acab.naiveha.subrosa.ui.nitrokey3updater.mboot.MbootProtocolException
import acab.naiveha.subrosa.ui.nitrokey3updater.mboot.MbootStatus
import acab.naiveha.subrosa.ui.nitrokey3updater.mboot.MbootTimeoutException
import acab.naiveha.subrosa.ui.nitrokey3updater.mboot.MbootTransport
import acab.naiveha.subrosa.ui.nitrokey3updater.mboot.PrefixReply
import acab.naiveha.subrosa.ui.nitrokey3updater.mboot.PrefixTransfer
import acab.naiveha.subrosa.ui.nitrokey3updater.mboot.ReadDeadline
import acab.naiveha.subrosa.ui.nitrokey3updater.mboot.readWithin
import java.io.IOException

internal enum class CheckLevel(val tag: String) {
    INFO("info"),
    OK("ok"),
    WARN("WARN"),
    FAIL("FAIL"),
}

internal class CheckLine(val level: CheckLevel, val text: String)

internal class CheckSection(val title: String, val lines: List<CheckLine>) {
    val worst: CheckLevel get() = lines.maxOfOrNull { it.level } ?: CheckLevel.INFO
}

internal class BootloaderReport(
    val sections: List<CheckSection>,
    val packetSize: Int?,
    val needsReplug: Boolean = false,
) {
    val worst: CheckLevel get() = sections.maxOfOrNull { it.worst } ?: CheckLevel.INFO

    val hasProblems: Boolean get() = worst >= CheckLevel.WARN

    val canTestLongTransfers: Boolean get() = packetSize != null && !needsReplug

    fun toText(): String = sections.joinToString("\n\n") { section ->
        buildString {
            append(section.title)
            for (line in section.lines) {
                append("\n  [").append(line.level.tag.padEnd(TAG_WIDTH)).append("] ").append(line.text)
            }
        }
    }

    fun withLongTransfer(result: LongTransferResult): BootloaderReport = BootloaderReport(
        sections = sections.filter { it.title != BootloaderCheck.LONG_TRANSFERS_TITLE } + result.section,
        packetSize = packetSize,
        needsReplug = result.needsReplug,
    )

    companion object {
        private const val TAG_WIDTH = 4

        fun failure(message: String): BootloaderReport = BootloaderReport(
            sections = listOf(CheckSection("Check", listOf(CheckLine(CheckLevel.FAIL, message)))),
            packetSize = null,
        )
    }
}

internal class LongTransferResult(val section: CheckSection, val needsReplug: Boolean)

internal class LongTransferPlan(val prefixBytes: Int, val reports: Int, val longestFrame: Int)

internal class EndpointInfo(val address: Int, val maxPacketSize: Int, val interval: Int)

internal class UsbLinkInfo(
    val interfaceNumber: Int,
    val endpointIn: EndpointInfo,
    val endpointOut: EndpointInfo,
    val reportDescriptor: ByteArray?,
    val reportDescriptorProblem: String? = null,
)

internal class BootloaderCheck(
    private val transport: MbootTransport,
    private val link: UsbLinkInfo,
    private val log: (String) -> Unit = {},
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    private val buffer = ByteArray(MbootClient.READ_BUFFER_SIZE)

    fun runReadOnly(): BootloaderReport {
        log("bootloader check: reading USB link descriptor")
        val usb = SectionBuilder("USB link")
        val descriptor = describeLink(usb)
        log("bootloader check: link descriptor parsed, testing repeated exchanges")

        val exchanges = askRepeatedly()
        log("bootloader check: ${exchanges.answers.size} of $READ_CHECK_REPEATS exchanges completed")

        val size = SectionBuilder("Packet size")
        val packetSize = evaluatePacketSize(size, exchanges, descriptor)
        log("bootloader check: evaluated packet size = $packetSize")

        val reads = SectionBuilder("One-packet reads")
        evaluateReads(reads, exchanges)
        log("bootloader check: one-packet reads evaluation completed")

        return BootloaderReport(listOf(usb.build(), size.build(), reads.build()), packetSize)
    }

    fun testLongTransfers(image: FirmwareImage): LongTransferResult {
        log("bootloader check: long transfers")
        val section = SectionBuilder(LONG_TRANSFERS_TITLE)

        val prefix = prefixLength(image)
        if (prefix == null) {
            section.fail("This firmware file cannot be used for the test (${whyNoPrefix(image)}). Nothing was sent.")
            return LongTransferResult(section.build(), needsReplug = false)
        }

        val client = MbootClient(transport, log, sleep)
        val reported = try {
            client.getProperty(MbootProtocol.PROPERTY_MAX_PACKET_SIZE)?.firstOrNull()
        } catch (e: IOException) {
            section.fail("The bootloader did not answer get-property (${e.message}). Nothing was sent.")
            return LongTransferResult(section.build(), needsReplug = false)
        }
        val packetSize = Lpc55Flasher.dataPacketSize(reported, transport.maxFrameSize)

        val transfer = try {
            client.sendSbFilePrefix(image.data, prefix, packetSize, LISTEN_MS)
        } catch (e: IOException) {
            log("bootloader check: long transfer test failed to send prefix: ${e.message}")
            try { client.abortDataPhase() } catch (_: Exception) {}
            section.fail("Starting the transfer failed (${e.message}).")
            return LongTransferResult(section.build(), needsReplug = false)
        }

        when (transfer) {
            is PrefixTransfer.Refused -> {
                log("bootloader check: long transfer test refused by bootloader (status=${transfer.status})")
                try { client.abortDataPhase() } catch (_: Exception) {}
                section.fail("The bootloader refused to start a file transfer: ${MbootStatus.describe(transfer.status).sentence()} Nothing was sent.")
                return LongTransferResult(section.build(), needsReplug = false)
            }
            is PrefixTransfer.WriteFailed -> {
                log("bootloader check: long transfer test write failed at byte ${transfer.bytes}: ${transfer.message}")
                try { client.abortDataPhase() } catch (_: Exception) {}
                section.fail(
                    "Sending failed after ${transfer.bytes} of $prefix bytes " +
                        "(${transfer.reports} data reports went through): ${transfer.message}.",
                )
                if (transfer.status != null) {
                    section.fail("The bootloader aborted the transfer: ${MbootStatus.describe(transfer.status).sentence()}")
                } else {
                    section.info("The bootloader did not say why.")
                }
                return LongTransferResult(section.build(), needsReplug = false)
            }
            is PrefixTransfer.Sent -> {
                log("bootloader check: long transfer test prefix sent successfully (${transfer.bytes} bytes, reply=${transfer.reply})")
                describeSent(section, transfer)
            }
        }
        try { client.abortDataPhase() } catch (_: Exception) {}
        log("bootloader check: long transfer test completed cleanly; data phase aborted.")
        return LongTransferResult(section.build(), needsReplug = false)
    }

    private fun describeSent(section: SectionBuilder, transfer: PrefixTransfer.Sent) {
        val sent = "the first ${transfer.bytes} bytes of the firmware file as ${transfer.reports} data reports " +
            "of up to ${transfer.longestFrame} bytes"
        when (val reply = transfer.reply) {
            PrefixReply.Silence -> {
                section.ok("Sent $sent. Every write was accepted and the bootloader raised no error within ${LISTEN_MS / 1000.0} s.")
                section.info(
                    "The bootloader verifies the file header as soon as it has received it, so damaged data would " +
                        "very likely have been rejected by now. A good sign, not a proof.",
                )
            }
            is PrefixReply.Aborted -> {
                val why = reply.status?.let { MbootStatus.describe(it) } ?: "no reason given"
                section.fail(
                    "Sent $sent, but the bootloader aborted the transfer: ${why.sentence()} Either the data arrived damaged " +
                        "(long writes are not reliable) or the file itself is not acceptable to the bootloader.",
                )
            }
            is PrefixReply.Unexpected -> section.warn(
                "Sent $sent. The bootloader answered with ${MbootStatus.label(reply.status)} " +
                    "although the file is incomplete; that is unexpected.",
            )
        }

        val outPacket = link.endpointOut.maxPacketSize
        if (outPacket > 0) {
            if (transfer.longestFrame > outPacket) {
                section.info(
                    "The longest report (${transfer.longestFrame} bytes) went out as " +
                        "${ceilDiv(transfer.longestFrame, outPacket)} USB packets of up to $outPacket bytes: " +
                        "long writes on the interrupt endpoint were exercised.",
                )
            } else {
                section.info(
                    "Every report (at most ${transfer.longestFrame} bytes) fitted into one USB packet " +
                        "($outPacket bytes), so this did not exercise long writes.",
                )
            }
        }
    }

    private fun describeLink(section: SectionBuilder): HidReportDescriptor? {
        section.info(
            "HID interface ${link.interfaceNumber}: interrupt IN ${describe(link.endpointIn)}, " +
                "interrupt OUT ${describe(link.endpointOut)}.",
        )

        val raw = link.reportDescriptor
        if (raw == null) {
            section.warn(
                "The HID report descriptor could not be read (${link.reportDescriptorProblem ?: "no reason given"}); " +
                    "report sizes are not cross-checked.",
            )
            return null
        }
        val descriptor = try {
            HidReportDescriptor.parse(raw)
        } catch (e: HidDescriptorException) {
            section.warn("The HID report descriptor (${raw.size} bytes) could not be decoded: ${e.message}.")
            return null
        }
        if (!descriptor.usesReportIds) {
            section.warn(
                "The HID report descriptor (${raw.size} bytes) declares no report ids, " +
                    "but the bootloader protocol uses reports 1 to 4.",
            )
            return descriptor
        }

        section.info("HID report descriptor: ${raw.size} bytes.")
        for ((type, id, role) in BOOTLOADER_REPORTS) {
            val wire = descriptor.wireBytes(type, id)
            if (wire == null) {
                section.warn("${type.label} report $id ($role) is not declared.")
            } else {
                section.info("${type.label} report $id ($role): up to $wire bytes.")
            }
        }
        return descriptor
    }

    private fun describe(endpoint: EndpointInfo): String =
        "0x${endpoint.address.toString(16).uppercase().padStart(2, '0')} " +
            "(${endpoint.maxPacketSize}-byte packets, bInterval ${endpoint.interval})"

    private class Answer(
        val bytes: Int,
        val response: MbootFrame.Response?,
        val problem: String?,
        val padding: Int,
        val leftover: Int,
    )

    private class Exchanges(val answers: List<Answer>, val stoppedAt: Int, val stopMessage: String?)

    private fun askRepeatedly(): Exchanges {
        val answers = mutableListOf<Answer>()
        repeat(READ_CHECK_REPEATS) { index ->
            val answer = try {
                askOnce()
            } catch (e: IOException) {
                return Exchanges(answers, index + 1, e.message ?: e.javaClass.simpleName)
            }
            answers += answer
            if (answer.response == null) return Exchanges(answers, index + 1, null)
        }
        return Exchanges(answers, READ_CHECK_REPEATS, null)
    }

    private fun askOnce(): Answer {
        val command = MbootProtocol.encodeFrame(
            MbootProtocol.REPORT_CMD_OUT,
            MbootProtocol.encodeCommandPacket(
                MbootProtocol.CMD_GET_PROPERTY,
                MbootProtocol.FLAG_NONE,
                MbootProtocol.PROPERTY_MAX_PACKET_SIZE,
                0,
            ),
        )
        transport.write(command, command.size, MbootClient.WRITE_TIMEOUT_MS)

        val length = readNonEmptyReport(MbootClient.COMMAND_TIMEOUT_MS)

        var response: MbootFrame.Response? = null
        var problem: String? = null
        var padding = 0
        try {
            when (val frame = MbootProtocol.parseFrame(buffer, length)) {
                is MbootFrame.Response -> {
                    response = frame
                    val payload = (buffer[2].toInt() and 0xFF) or ((buffer[3].toInt() and 0xFF) shl 8)
                    padding = length - (MbootProtocol.FRAME_HEADER_SIZE + payload)
                }
                is MbootFrame.Abort -> problem = "an empty abort report instead of a response"
                is MbootFrame.Data -> problem = "report ${frame.reportId} instead of a command response"
            }
        } catch (e: MbootProtocolException) {
            problem = e.message
        }

        var leftover = 0
        var reads = 0
        while (reads++ < MAX_LEFTOVER_READS) {
            leftover += try {
                transport.read(buffer, EXTRA_PACKET_WAIT_MS)
            } catch (_: MbootTimeoutException) {
                break
            }
        }
        return Answer(length, response, problem, padding, leftover)
    }

    private fun readNonEmptyReport(timeoutMs: Int): Int {
        val deadline = ReadDeadline(timeoutMs)
        var empty = 0
        while (empty < MAX_EMPTY_READS) {
            val length = try {
                transport.readWithin(buffer, deadline)
            } catch (e: MbootTimeoutException) {
                if (empty > 0) break
                throw e
            }
            if (length > 0) return length
            empty++
            log("bootloader check: empty report $empty of $MAX_EMPTY_READS while waiting for the answer")
        }
        throw MbootProtocolException("only empty reports arrived")
    }

    private fun evaluatePacketSize(section: SectionBuilder, exchanges: Exchanges, descriptor: HidReportDescriptor?): Int? {
        val first = exchanges.answers.firstOrNull()
        if (first == null) {
            section.fail(
                "The bootloader did not answer get-property (${exchanges.stopMessage ?: "no reason given"}). " +
                    "Is the key in bootloader mode?",
            )
            return null
        }
        val response = first.response
        if (response == null) {
            section.fail("The answer to get-property could not be decoded: ${first.problem}.")
            return null
        }

        val values = response.propertyValues
        val reported = values?.firstOrNull()
        val default = MbootProtocol.DEFAULT_MAX_PACKET_SIZE
        when {
            values == null -> section.warn(
                "get-property max-packet-size was answered with ${MbootStatus.label(response.status)}; " +
                    "an update would use the default of $default bytes.",
            )
            reported == null -> section.warn(
                "The answer to get-property max-packet-size carries no value; an update would use the default of $default bytes.",
            )
            reported <= 0 -> section.warn(
                "The bootloader reports a maximum packet size of $reported; an update would use the default of $default bytes.",
            )
            else -> section.ok("The bootloader reports a maximum packet size of $reported bytes.")
        }

        val dataSize = Lpc55Flasher.dataPacketSize(reported, transport.maxFrameSize)
        if (reported != null && reported > 0 && dataSize < reported) {
            section.info("The app sends at most $dataSize bytes per report (${transport.maxFrameSize} byte transfer limit), less than the $reported allowed.")
        }
        val frame = MbootProtocol.FRAME_HEADER_SIZE + dataSize
        section.info(
            "An update sends the firmware in data reports of $frame bytes " +
                "($dataSize bytes of data and a ${MbootProtocol.FRAME_HEADER_SIZE} byte header).",
        )

        val declared = descriptor?.wireBytes(HidReportType.OUTPUT, MbootProtocol.REPORT_DATA_OUT)
        if (declared != null) {
            if (frame <= declared) {
                section.ok("A data report of $frame bytes fits the OUT report 2 that the device declares ($declared bytes).")
            } else {
                section.warn(
                    "A data report of $frame bytes is longer than the OUT report 2 that the device declares " +
                        "($declared bytes). The device may ignore or cut such reports; the long-transfer test shows whether it does.",
                )
            }
        }

        val outPacket = link.endpointOut.maxPacketSize
        if (outPacket > 0) {
            if (frame > outPacket) {
                section.info(
                    "A data report of $frame bytes goes out as ${ceilDiv(frame, outPacket)} USB packets of up to " +
                        "$outPacket bytes (a long write); the long-transfer test exercises exactly that.",
                )
            } else {
                section.info("A data report of $frame bytes fits into one USB packet ($outPacket bytes); long writes are not needed.")
            }
        }
        return dataSize
    }

    private fun evaluateReads(section: SectionBuilder, exchanges: Exchanges) {
        val answers = exchanges.answers
        if (answers.isEmpty()) {
            section.info("Skipped: the bootloader gave no answer to the first request.")
            return
        }
        val inPacket = link.endpointIn.maxPacketSize
        var failed = false

        val damaged = answers.indexOfFirst { it.response == null }
        if (damaged >= 0) {
            failed = true
            val answer = answers[damaged]
            section.fail(
                "Answer ${damaged + 1} was not a complete command response (${answer.bytes} bytes read): ${answer.problem}.",
            )
        }

        val withLeftover = answers.filter { it.leftover > 0 }
        if (withLeftover.isNotEmpty()) {
            failed = true
            section.fail(
                "${withLeftover.size} of ${answers.size} answers were followed by up to ${withLeftover.maxOf { it.leftover }} " +
                    "more bytes. The device sends reports longer than one USB packet ($inPacket bytes), " +
                    "but the app reads one packet at a time.",
            )
        }

        exchanges.stopMessage?.let {
            failed = true
            section.fail("Request ${exchanges.stoppedAt} of $READ_CHECK_REPEATS got no answer: $it.")
        }

        if (!failed) {
            val shortest = answers.minOf { it.bytes }
            val longest = answers.maxOf { it.bytes }
            val sizes = if (shortest == longest) "$longest" else "$shortest to $longest"
            section.ok("${answers.size} of ${answers.size} answers arrived complete in a single read ($sizes bytes) with nothing following.")
            if (answers.any { it.padding > 0 }) {
                section.info("The device pads its reports with zeros (up to $longest bytes per read; a USB packet holds $inPacket).")
            }
        }
    }

    private class SectionBuilder(private val title: String) {
        private val lines = mutableListOf<CheckLine>()

        fun info(text: String) = add(CheckLevel.INFO, text)
        fun ok(text: String) = add(CheckLevel.OK, text)
        fun warn(text: String) = add(CheckLevel.WARN, text)
        fun fail(text: String) = add(CheckLevel.FAIL, text)

        private fun add(level: CheckLevel, text: String) {
            lines += CheckLine(level, text)
        }

        fun build() = CheckSection(title, lines.toList())
    }

    companion object {
        const val LONG_TRANSFERS_TITLE = "Long transfers"

        const val LISTEN_MS = 1_500

        const val MAX_PREFIX_BYTES = 16 * 1024

        const val READ_CHECK_REPEATS = 25
        private const val EXTRA_PACKET_WAIT_MS = 50
        private const val MAX_LEFTOVER_READS = 16
        private const val MAX_EMPTY_READS = 4

        private const val REPLUG_ADVICE =
            "The bootloader now waits for the rest of the file. Unplug the key and plug it in again, " +
                "then reboot it into the bootloader once more before checking or updating."

        private val BOOTLOADER_REPORTS = listOf(
            Triple(HidReportType.OUTPUT, MbootProtocol.REPORT_CMD_OUT, "commands"),
            Triple(HidReportType.OUTPUT, MbootProtocol.REPORT_DATA_OUT, "firmware data"),
            Triple(HidReportType.INPUT, MbootProtocol.REPORT_CMD_IN, "command responses"),
            Triple(HidReportType.INPUT, MbootProtocol.REPORT_DATA_IN, "data from the device"),
        )

        fun whyNoPrefix(image: FirmwareImage): String? {
            if (image.variant != Nk3Variant.LPC55) return "it is not an LPC55 firmware image"
            val header = image.sb2Header ?: return "it has no SB2 header"
            val end = header.pausePoint
            return when {
                end <= 0 -> "its header does not say where the signed header ends"
                end >= image.data.size -> "the signed header would be the whole file"
                end > MAX_PREFIX_BYTES -> "its signed header is unusually long ($end bytes)"
                else -> null
            }
        }

        fun prefixLength(image: FirmwareImage): Int? =
            if (whyNoPrefix(image) == null) image.sb2Header?.pausePoint else null

        fun plan(image: FirmwareImage, packetSize: Int): LongTransferPlan? {
            val prefix = prefixLength(image) ?: return null
            if (packetSize <= 0) return null
            return LongTransferPlan(
                prefixBytes = prefix,
                reports = ceilDiv(prefix, packetSize),
                longestFrame = MbootProtocol.FRAME_HEADER_SIZE + minOf(packetSize, prefix),
            )
        }

        fun longTransferFailure(message: String, needsReplug: Boolean): LongTransferResult {
            val lines = mutableListOf(CheckLine(CheckLevel.FAIL, message))
            if (needsReplug) lines += CheckLine(CheckLevel.INFO, REPLUG_ADVICE)
            return LongTransferResult(CheckSection(LONG_TRANSFERS_TITLE, lines), needsReplug)
        }

        private fun ceilDiv(a: Int, b: Int): Int = (a + b - 1) / b

        private fun String.sentence(): String = if (endsWith(".")) this else "$this."
    }
}