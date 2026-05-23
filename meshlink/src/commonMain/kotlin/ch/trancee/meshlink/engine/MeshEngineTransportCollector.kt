package ch.trancee.meshlink.engine

import ch.trancee.meshlink.transport.TransportEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

internal class MeshEngineTransportCollector(
    private val coroutineScope: CoroutineScope,
    private val transportEvents: () -> Flow<TransportEvent>?,
    private val handleTransportEvent: suspend (TransportEvent) -> Unit,
) {
    private var transportCollectionJob: Job? = null

    internal fun ensureStarted(): Unit {
        val events = transportEvents() ?: return
        if (transportCollectionJob != null) {
            return
        }
        transportCollectionJob =
            coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
                events.collect { event -> handleTransportEvent(event) }
            }
    }

    internal fun stop(): Unit {
        transportCollectionJob?.cancel()
        transportCollectionJob = null
    }
}
