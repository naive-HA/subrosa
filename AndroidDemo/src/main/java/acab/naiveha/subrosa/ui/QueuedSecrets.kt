package acab.naiveha.subrosa.ui

import java.util.concurrent.atomic.AtomicInteger

class QueuedSecrets(
    private val chars: List<CharArray> = emptyList(),
    private val bytes: List<ByteArray> = emptyList(),
) {
    private val state = AtomicInteger(PENDING)

    fun start(): Boolean = state.compareAndSet(PENDING, RUNNING)

    fun cancel() {
        if (state.compareAndSet(PENDING, CANCELLED)) wipe()
    }

    fun finish() {
        if (state.compareAndSet(RUNNING, FINISHED)) wipe()
    }

    fun <R> runOnce(block: () -> R): R? {
        if (!start()) return null
        try {
            return block()
        } finally {
            finish()
        }
    }

    private fun wipe() {
        chars.forEach { it.fill('\u0000') }
        bytes.forEach { it.fill(0) }
    }

    private companion object {
        const val PENDING = 0
        const val RUNNING = 1
        const val CANCELLED = 2
        const val FINISHED = 3
    }
}
