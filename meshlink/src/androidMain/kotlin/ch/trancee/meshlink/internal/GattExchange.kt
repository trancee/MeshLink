package ch.trancee.meshlink.internal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What a GATT callback answered: its [status] and, for some callbacks, a [number] or [bytes]. */
internal class GattAnswer(
    val status: Int,
    val number: Int = 0,
    val bytes: ByteArray = ByteArray(0),
)

/**
 * One GATT request at a time and the callback that answers it (library rules R12 and R14). Android
 * drops or refuses a request issued while another is outstanding, so every request waits for the
 * previous one's callback; a request the stack reports busy retries every [BUSY_RETRY_MILLIS] up to
 * [BUSY_RETRIES] times.
 */
internal class GattExchange {
    private val mutex = Mutex()

    @Volatile private var pending: Pending? = null

    @Volatile private var closed = false

    /**
     * Issues the request [start] and returns the callback's answer; null if the request never
     * started, the exchange closed or [owner] abandoned it. [start] returns [STARTED], [BUSY], or
     * anything else for a refusal.
     */
    suspend fun request(
        owner: Any? = null,
        start: () -> Int,
    ): GattAnswer? =
        mutex.withLock {
            val reply = CompletableDeferred<GattAnswer?>()
            pending = Pending(owner, reply)
            if (closed) reply.complete(null)
            if (!reply.isCompleted && !begin(start)) reply.complete(null)
            reply.await().also { pending = null }
        }

    /** Answers the outstanding request; does nothing if none is outstanding. */
    fun answer(answer: GattAnswer) {
        pending?.let { it.reply.complete(answer) }
    }

    /** Fails the outstanding request if [owner] made it: its answer may never come. */
    fun abandon(owner: Any) {
        pending?.let { if (it.owner === owner) it.reply.complete(null) }
    }

    /** Fails the outstanding request and every later one. */
    fun close() {
        closed = true
        pending?.let { it.reply.complete(null) }
    }

    private suspend fun begin(start: () -> Int): Boolean {
        var result = start()
        var attempts = 1
        while (result == BUSY && attempts < BUSY_RETRIES) {
            delay(BUSY_RETRY_MILLIS)
            result = start()
            attempts++
        }
        return result == STARTED
    }

    private class Pending(
        val owner: Any?,
        val reply: CompletableDeferred<GattAnswer?>,
    )

    companion object {
        /** [request]'s start succeeded; its callback follows. */
        const val STARTED: Int = 0

        /** [request]'s start found the stack busy, so it retries (`ERROR_GATT_WRITE_REQUEST_BUSY`). */
        const val BUSY: Int = 201

        /** How long a busy request waits before it retries. */
        const val BUSY_RETRY_MILLIS: Long = 10

        /** How often a busy request is tried before it fails: one second in all. */
        const val BUSY_RETRIES: Int = 100

        /** A request that was refused before it reached the stack, for example on a closed link. */
        const val REFUSED: Int = -1

        /** [STARTED] if [started], else [BUSY]: a legacy request reports a busy stack only as false. */
        fun legacy(started: Boolean): Int = if (started) STARTED else BUSY
    }
}
