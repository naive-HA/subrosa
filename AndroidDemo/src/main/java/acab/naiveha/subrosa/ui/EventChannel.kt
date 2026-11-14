package acab.naiveha.subrosa.ui

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

class EventChannel<T : Any>(val capacity: Int = DEFAULT_CAPACITY) {

    init {
        require(capacity >= 1) { "capacity must be at least 1, was $capacity" }
    }

    private val channel = Channel<T>(capacity, BufferOverflow.DROP_OLDEST)

    val flow: Flow<T> = channel.receiveAsFlow()

    fun post(event: T) {
        channel.trySend(event)
    }

    fun discardPending() {
        while (channel.tryReceive().getOrNull() != null) {
            // keep draining
        }
    }

    companion object {
        const val DEFAULT_CAPACITY = 16

        fun <T : Any> latestOnly(): EventChannel<T> = EventChannel(capacity = 1)
    }
}
