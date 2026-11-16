package acab.naiveha.subrosa.ui.nitrokey3updater

import acab.naiveha.subrosa.ui.nitrokey3updater.mboot.AbortOutcome
import acab.naiveha.subrosa.ui.nitrokey3updater.mboot.MbootStatus

internal enum class FlashPhase {
    CONNECTING,
    SENDING,
    RESTARTING,
    VERIFYING,
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

internal enum class FlashStage {
    CONNECT,
    START,
    TRANSFER,
    FINAL,
    RESET,
}

internal enum class FailureAdvice {
    RETRY,
    REPLUG_NO_DATA,
    REPLUG_PARTIAL,
    REPLUG_UNCONFIRMED,
    REPLUG_RESTART;

    companion object {
        fun of(stage: FlashStage, bytesSent: Long, replugRequired: Boolean): FailureAdvice = when {
            stage == FlashStage.RESET -> REPLUG_RESTART
            stage == FlashStage.FINAL -> REPLUG_UNCONFIRMED
            bytesSent > 0 -> REPLUG_PARTIAL
            replugRequired -> REPLUG_NO_DATA
            else -> RETRY
        }
    }
}

internal class FlashFailure(
    val stage: FlashStage,
    val bytesSent: Long,
    val total: Long,
    val status: Int?,
    val replugRequired: Boolean,
    val abort: AbortOutcome?,
    val elapsedMs: Long,
) {
    val advice: FailureAdvice get() = FailureAdvice.of(stage, bytesSent, replugRequired)

    fun summary(): String =
        "flash failed: stage=$stage, sent=$bytesSent/$total bytes, elapsed=$elapsedMs ms, " +
            "status=${status?.let { MbootStatus.label(it) } ?: "none"}, " +
            "abort=${abort ?: "not needed"}, replugRequired=$replugRequired"
}

internal interface FirmwareFlasher {
    val variant: Nk3Variant

    @Throws(FlashException::class)
    fun flash(image: FirmwareImage, onProgress: (FlashProgress) -> Unit)
}

internal open class FlashException(
    message: String,
    cause: Throwable? = null,
    val failure: FlashFailure? = null,
) : Exception(message, cause)

internal class FlashNotSupportedException(message: String) : FlashException(message)
