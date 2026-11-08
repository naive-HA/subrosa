package acab.naiveha.subrosa.ui.openpgp

class PartialWriteException(cause: Throwable) : Exception(cause) {

    override val message: String = interruptionMessage(SUMMARY, advice(), cause)

    private companion object {
        const val SUMMARY = "Writing was interrupted after the device had started to reset."

        fun advice(): String {
            val defaultPins = "Admin ${String(OpenPgpWriterUtils.DEFAULT_ADMIN_PIN)}, " +
                "User ${String(OpenPgpWriterUtils.DEFAULT_USER_PIN)}"
            return "The device may have the default PINs ($defaultPins) or the PINs you entered, " +
                "and its key may be missing or incomplete. Do not use it yet: reconnect it and press Save " +
                "again. If the key is no longer shown, load it again first."
        }
    }
}

class WipeInterruptedException(cause: Throwable) : Exception(cause) {

    override val message: String = interruptionMessage(SUMMARY, ADVICE, cause)

    private companion object {
        const val SUMMARY = "Erase was interrupted before it finished."
        const val ADVICE = "The device's PINs may be blocked. Run Erase again to finish resetting it."
    }
}

private fun interruptionMessage(summary: String, advice: String, cause: Throwable): String {
    val reason = cause.message?.trim().orEmpty()
    return buildString {
        append(summary).append("\n\n").append(advice)
        if (reason.isNotEmpty()) append("\n\nReason: ").append(reason)
    }
}
