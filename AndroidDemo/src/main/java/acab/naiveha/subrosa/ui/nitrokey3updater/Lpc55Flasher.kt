package acab.naiveha.subrosa.ui.nitrokey3updater

import acab.naiveha.subrosa.ui.nitrokey3updater.mboot.MbootClient
import acab.naiveha.subrosa.ui.nitrokey3updater.mboot.MbootProtocol
import acab.naiveha.subrosa.ui.nitrokey3updater.mboot.MbootStatus
import acab.naiveha.subrosa.ui.nitrokey3updater.mboot.MbootStatusException
import acab.naiveha.subrosa.ui.nitrokey3updater.mboot.MbootTimeoutException
import acab.naiveha.subrosa.ui.nitrokey3updater.mboot.MbootTransport
import java.io.IOException

internal class Lpc55Flasher(
    private val transport: MbootTransport,
    private val log: (String) -> Unit = {},
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) : FirmwareFlasher {

    override val variant: Nk3Variant get() = Nk3Variant.LPC55

    override fun flash(image: FirmwareImage, onProgress: (FlashProgress) -> Unit) {
        if (image.variant != Nk3Variant.LPC55) {
            throw FlashException("This is not an LPC55 firmware image.")
        }
        val header = image.sb2Header ?: throw FlashException("The firmware image has no SB2 header.")

        val client = MbootClient(transport, log, sleep)
        try {
            onProgress(FlashProgress(FlashPhase.CONNECTING))
            val packetSize = choosePacketSize(client)

            client.receiveSbFile(image.data, packetSize, header.pausePoint) { sent, total ->
                onProgress(FlashProgress(FlashPhase.SENDING, sent.toLong(), total.toLong()))
            }

            onProgress(FlashProgress(FlashPhase.RESTARTING))
            if (!client.reset()) {
                log("The bootloader did not accept the reset command")
            }
        } catch (e: MbootStatusException) {
            val msg = e.message ?: MbootStatus.label(e.status)
            android.util.Log.e("Lpc55Flasher", "LPC55 flashing failed with status exception: $msg", e)
            throw FlashException(msg, e)
        } catch (e: MbootTimeoutException) {
            val msg = "The bootloader stopped responding (${e.message})."
            android.util.Log.e("Lpc55Flasher", "LPC55 flashing failed with timeout exception: $msg", e)
            throw FlashException(msg, e)
        } catch (e: IOException) {
            val msg = e.message ?: "USB communication with the bootloader failed."
            android.util.Log.e("Lpc55Flasher", "LPC55 flashing failed with IO exception: $msg", e)
            throw FlashException(msg, e)
        }
    }

    private fun choosePacketSize(client: MbootClient): Int {
        val reported = client.getProperty(MbootProtocol.PROPERTY_MAX_PACKET_SIZE)?.firstOrNull()
        val size = dataPacketSize(reported, transport.maxFrameSize)
        log("max packet size: reported=$reported, using $size")
        return size
    }

    companion object {
        fun dataPacketSize(reported: Int?, maxFrameSize: Int): Int {
            val wanted = if (reported == null || reported <= 0) MbootProtocol.DEFAULT_MAX_PACKET_SIZE else reported
            return minOf(wanted, maxFrameSize - MbootProtocol.FRAME_HEADER_SIZE)
        }
    }
}
