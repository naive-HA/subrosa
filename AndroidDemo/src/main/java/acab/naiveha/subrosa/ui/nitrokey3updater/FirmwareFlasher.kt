package acab.naiveha.subrosa.ui.nitrokey3updater

internal enum class FlashPhase {
    CONNECTING,
    SENDING,
    RESTARTING,
}

internal data class FlashProgress(
    val phase: FlashPhase,
    val done: Long = 0,
    val total: Long = 0,
) {
    val percent: Int
        get() = if (phase == FlashPhase.SENDING && total > 0) {
            (done * 100 / total).toInt().coerceIn(0, 100)
        } else {
            -1
        }
}

internal interface FirmwareFlasher {
    val variant: Nk3Variant

    @Throws(FlashException::class)
    fun flash(image: FirmwareImage, onProgress: (FlashProgress) -> Unit)
}

internal open class FlashException(message: String, cause: Throwable? = null) : Exception(message, cause)

internal class FlashNotSupportedException(message: String) : FlashException(message)
