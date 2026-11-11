package acab.naiveha.subrosa.ui.nitrokey3updater

import acab.naiveha.subrosa.ui.nitrokey3updater.mboot.AbortOutcome
import acab.naiveha.subrosa.ui.nitrokey3updater.mboot.MbootClient
import acab.naiveha.subrosa.ui.nitrokey3updater.mboot.MbootProtocol
import acab.naiveha.subrosa.ui.nitrokey3updater.mboot.MbootStatus
import acab.naiveha.subrosa.ui.nitrokey3updater.mboot.MbootStatusException
import acab.naiveha.subrosa.ui.nitrokey3updater.mboot.MbootTimeoutException
import acab.naiveha.subrosa.ui.nitrokey3updater.mboot.MbootTransport
import acab.naiveha.subrosa.ui.nitrokey3updater.mboot.TransferStage
import java.io.IOException

internal class Lpc55Flasher(
    private val transport: MbootTransport,
    private val log: (String) -> Unit = {},
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val declaredDataReportBytes: Int? = null,
) : FirmwareFlasher {

    override val variant: Nk3Variant get() = Nk3Variant.LPC55

    override fun flash(image: FirmwareImage, onProgress: (FlashProgress) -> Unit) {
        if (image.variant != Nk3Variant.LPC55) {
            throw FlashException("This is not an LPC55 firmware image.")
        }
        val header = image.sb2Header ?: throw FlashException("The firmware image has no SB2 header.")

        val client = MbootClient(transport, log, sleep)
        val startedAtNanos = System.nanoTime()
        var resetStarted = false
        try {
            onProgress(FlashProgress(FlashPhase.CONNECTING))
            val packetSize = choosePacketSize(client)

            client.receiveSbFile(image.data, packetSize, header.pausePoint) { sent, total ->
                onProgress(FlashProgress(FlashPhase.SENDING, sent.toLong(), total.toLong()))
            }

            onProgress(FlashProgress(FlashPhase.RESTARTING))
            resetStarted = true
            if (!client.reset()) {
                throw IOException("The bootloader did not accept the reset command.")
            }
        } catch (e: Exception) {
            throw failed(e, client, image.data.size, startedAtNanos, resetStarted)
        }
    }

    private fun failed(
        cause: Exception,
        client: MbootClient,
        total: Int,
        startedAtNanos: Long,
        resetStarted: Boolean,
    ): FlashException {
        val stage = if (resetStarted) FlashStage.RESET else stageOf(client.transferStage)
        val abort = if (client.dataPhaseMayBeOpen) abortAfterFailure(client) else null
        val failure = FlashFailure(
            stage = stage,
            bytesSent = client.bytesSent.toLong(),
            total = total.toLong(),
            status = (cause as? MbootStatusException)?.status ?: client.lastStatus,
            replugRequired = replugRequired(stage, cause),
            abort = abort,
            elapsedMs = (System.nanoTime() - startedAtNanos) / NANOS_PER_MS,
        )
        log("${failure.summary()}; cause: ${cause.javaClass.simpleName}: ${cause.message}")
        val root = if (cause is FlashException) cause.cause else cause
        return FlashException(describe(cause), root, failure)
    }

    private fun abortAfterFailure(client: MbootClient): AbortOutcome = try {
        client.abortDataPhase(budgetMs = ABORT_BUDGET_MS)
    } catch (e: Exception) {
        log("abort after the failure threw ${e.javaClass.simpleName}: ${e.message}")
        AbortOutcome.NOT_SENT
    }

    private fun stageOf(stage: TransferStage): FlashStage = when (stage) {
        TransferStage.NOT_STARTED -> FlashStage.CONNECT
        TransferStage.STARTING -> FlashStage.START
        TransferStage.SENDING -> FlashStage.TRANSFER
        TransferStage.FINISHING -> FlashStage.FINAL
        TransferStage.FINISHED -> FlashStage.RESET
    }

    private fun replugRequired(stage: FlashStage, cause: Exception): Boolean = when (stage) {
        FlashStage.CONNECT -> cause is IOException
        else -> true
    }

    private fun describe(cause: Exception): String = when (cause) {
        is FlashException -> cause.message ?: "The firmware update failed."
        is MbootStatusException -> cause.message ?: MbootStatus.label(cause.status)
        is MbootTimeoutException -> "The bootloader stopped responding (${cause.message})."
        is IOException -> cause.message ?: "USB communication with the bootloader failed."
        else -> "Unexpected error: ${cause.message ?: cause.javaClass.simpleName}"
    }

    private fun choosePacketSize(client: MbootClient): Int {
        val reported = client.getProperty(MbootProtocol.PROPERTY_MAX_PACKET_SIZE)?.firstOrNull()
        val declared = declaredDataReportBytes?.toString() ?: "unknown"
        return when (val verdict = checkPacketSize(reported, transport.maxFrameSize, declaredDataReportBytes)) {
            is PacketSizeVerdict.Usable -> {
                log("max packet size: reported=$reported, OUT report 2=$declared, using ${verdict.size}")
                verdict.size
            }
            is PacketSizeVerdict.Rejected -> {
                log("max packet size: reported=$reported, OUT report 2=$declared, refusing to send: ${verdict.reason}")
                throw FlashException(verdict.reason)
            }
        }
    }

    companion object {
        const val ABORT_BUDGET_MS = 1_000

        private const val NANOS_PER_MS = 1_000_000L

        fun checkPacketSize(reported: Int?, maxFrameSize: Int, declaredReportBytes: Int?): PacketSizeVerdict {
            val header = MbootProtocol.FRAME_HEADER_SIZE
            val largest = maxFrameSize - header
            return when {
                reported == null -> PacketSizeVerdict.Rejected("The bootloader did not report a maximum packet size.")
                reported < MbootProtocol.MIN_DATA_PACKET_SIZE -> PacketSizeVerdict.Rejected(
                    "The bootloader reports a maximum packet size of ${reported.toUInt()} bytes; " +
                        "the smallest this app accepts is ${MbootProtocol.MIN_DATA_PACKET_SIZE}.",
                )
                reported > largest -> PacketSizeVerdict.Rejected(
                    "The bootloader reports a maximum packet size of $reported bytes; the largest this app sends is $largest.",
                )
                declaredReportBytes != null && reported + header > declaredReportBytes -> PacketSizeVerdict.Rejected(
                    "The bootloader reports a maximum packet size of $reported bytes, so a data report would be " +
                        "${reported + header} bytes, but the device declares only $declaredReportBytes bytes " +
                        "for its firmware data report.",
                )
                else -> PacketSizeVerdict.Usable(reported)
            }
        }
    }
}

internal sealed interface PacketSizeVerdict {
    class Usable(val size: Int) : PacketSizeVerdict

    class Rejected(val reason: String) : PacketSizeVerdict
}
