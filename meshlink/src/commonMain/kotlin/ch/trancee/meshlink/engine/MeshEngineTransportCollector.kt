package ch.trancee.meshlink.engine

import ch.trancee.meshlink.transport.TransportEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
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

    internal suspend fun stop(): Unit {
        val currentJob = transportCollectionJob ?: return
        transportCollectionJob = null
        currentJob.cancelAndJoin()
    }
}

internal fun buildMeshEngineRuntimeTransportCollector(
    coroutineScope: CoroutineScope,
    transportEvents: () -> Flow<TransportEvent>?,
    handleTransportEvent: suspend (TransportEvent) -> Unit,
): MeshEngineTransportCollector {
    return MeshEngineTransportCollector(
        coroutineScope = coroutineScope,
        transportEvents = transportEvents,
        handleTransportEvent = handleTransportEvent,
    )
}
