package ch.trancee.meshlink.internal

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_t
import kotlin.coroutines.CoroutineContext

/** Runs coroutines on the serial dispatch [queue]. */
internal class QueueDispatcher(
    private val queue: dispatch_queue_t,
) : CoroutineDispatcher() {
    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        dispatch_async(queue) { block.run() }
    }
}
