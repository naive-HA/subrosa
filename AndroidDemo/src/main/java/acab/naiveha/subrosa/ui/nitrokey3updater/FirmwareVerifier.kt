package acab.naiveha.subrosa.ui.nitrokey3updater

internal enum class VerifyOutcome {
    VERIFIED,
    MISMATCH,
    UNREACHABLE,
}

internal class FirmwareVerification(
    val outcome: VerifyOutcome,
    val expected: FirmwareVersion?,
    val reported: FirmwareVersion?,
    val detail: String,
    val elapsedMs: Long,
    val attempts: Int,
) {
    fun summary(): String =
        "verify: outcome=$outcome, expected=${expected ?: "unknown"}, reported=${reported ?: "none"}, " +
            "elapsed=$elapsedMs ms, attempts=$attempts; $detail"

    companion object {
        fun unreachable(
            expected: FirmwareVersion?,
            detail: String,
            elapsedMs: Long = 0,
            attempts: Int = 0,
            reported: FirmwareVersion? = null,
        ): FirmwareVerification =
            FirmwareVerification(VerifyOutcome.UNREACHABLE, expected, reported, detail, elapsedMs, attempts)
    }
}

internal sealed interface VersionRead {
    class Version(val text: String) : VersionRead
    object NoDevice : VersionRead
    object NoPermission : VersionRead
    class Failed(val reason: String) : VersionRead
}

internal fun interface ApplicationModeProbe {
    fun readVersion(): VersionRead
}

internal class FirmwareVerifier(
    private val probe: ApplicationModeProbe,
    private val log: (String) -> Unit = {},
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val nowMs: () -> Long = { System.nanoTime() / NANOS_PER_MS },
    private val timeoutMs: Long = TIMEOUT_MS,
    private val pollMs: Long = POLL_MS,
) {

    fun verify(expected: FirmwareVersion?): FirmwareVerification {
        val startedAt = nowMs()
        val deadline = startedAt + timeoutMs
        var attempts = 0
        var last: VersionRead = VersionRead.NoDevice
        var lastDescription: String? = null
        while (true) {
            attempts++
            val read = readOnce()
            last = read
            val description = describe(read)
            if (description != lastDescription) {
                log("attempt $attempts after ${nowMs() - startedAt} ms: $description")
                lastDescription = description
            }
            if (read is VersionRead.Version) {
                return conclude(expected, read.text, nowMs() - startedAt, attempts)
            }
            val remaining = deadline - nowMs()
            if (remaining <= 0) break
            sleep(minOf(pollMs, remaining))
        }
        return FirmwareVerification.unreachable(expected, timeoutDetail(last), nowMs() - startedAt, attempts)
    }

    private fun readOnce(): VersionRead = try {
        probe.readVersion()
    } catch (e: InterruptedException) {
        throw e
    } catch (e: Exception) {
        VersionRead.Failed("${e.javaClass.simpleName}: ${e.message}")
    }

    private fun conclude(expected: FirmwareVersion?, text: String, elapsedMs: Long, attempts: Int): FirmwareVerification {
        val reported = FirmwareVersion.parse(text)
            ?: return FirmwareVerification.unreachable(
                expected,
                "the key reported a version that cannot be read: \"$text\"",
                elapsedMs,
                attempts,
            )
        return when {
            expected == null -> FirmwareVerification.unreachable(
                expected,
                "the key reports $reported, but the image carries no version to compare it with",
                elapsedMs,
                attempts,
                reported,
            )
            reported == expected -> FirmwareVerification(
                VerifyOutcome.VERIFIED,
                expected,
                reported,
                "the key reports the version of the image",
                elapsedMs,
                attempts,
            )
            else -> FirmwareVerification(
                VerifyOutcome.MISMATCH,
                expected,
                reported,
                "the key reports $reported, the image is $expected",
                elapsedMs,
                attempts,
            )
        }
    }

    private fun describe(read: VersionRead): String = when (read) {
        is VersionRead.Version -> "the key reports version \"${read.text}\""
        VersionRead.NoDevice -> "no application-mode Nitrokey 3 attached"
        VersionRead.NoPermission -> "application-mode Nitrokey 3 attached, USB access not granted"
        is VersionRead.Failed -> "application-mode Nitrokey 3 attached, version not readable (${read.reason})"
    }

    private fun timeoutDetail(last: VersionRead): String {
        val seconds = timeoutMs / MS_PER_SECOND
        return when (last) {
            is VersionRead.Version -> "unreachable" // not reached: a version ends the loop
            VersionRead.NoDevice -> "no application-mode Nitrokey 3 appeared within $seconds s"
            VersionRead.NoPermission -> "the key appeared but USB access was not granted within $seconds s"
            is VersionRead.Failed -> "the key appeared but its version could not be read within $seconds s (${last.reason})"
        }
    }

    companion object {
        const val TIMEOUT_MS = 30_000L
        const val POLL_MS = 250L
        private const val NANOS_PER_MS = 1_000_000L
        private const val MS_PER_SECOND = 1_000L
    }
}
