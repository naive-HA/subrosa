package acab.naiveha.subrosa.ui.nitrokey3updater.mboot

import java.io.IOException

internal interface MbootTransport {
    val maxFrameSize: Int

    @Throws(IOException::class)
    fun write(frame: ByteArray, length: Int, timeoutMs: Int)

    @Throws(IOException::class)
    fun read(buffer: ByteArray, timeoutMs: Int): Int
}

internal class MbootTimeoutException(message: String) : IOException(message)

private const val NANOS_PER_MS = 1_000_000L

internal class ReadDeadline(val limitMs: Int) {
    private val start = System.nanoTime()

    fun remainingMs(): Long = limitMs - (System.nanoTime() - start) / NANOS_PER_MS
}

internal fun MbootTransport.readWithin(buffer: ByteArray, deadline: ReadDeadline): Int {
    val remainingMs = deadline.remainingMs()
    if (remainingMs > 0) {
        try {
            return read(buffer, remainingMs.toInt())
        } catch (_: MbootTimeoutException) {
            // reported below, with the limit of the whole wait
        }
    }
    throw MbootTimeoutException("no report received within ${deadline.limitMs} ms")
}
