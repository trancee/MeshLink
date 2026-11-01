package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DiagnosticEvent
import ch.trancee.meshlink.MeshState
import ch.trancee.meshlink.NodeId
import ch.trancee.meshlink.OutgoingMessage
import ch.trancee.meshlink.Peer
import ch.trancee.meshlink.ReceivedMessage
import ch.trancee.meshlink.SendOptions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Behavior behind the [ch.trancee.meshlink.MeshLink] facade, after argument validation. */
internal interface NodeEngine {
    val localNodeId: NodeId
    val state: StateFlow<MeshState>
    val peers: StateFlow<List<Peer>>
    val messages: Flow<ReceivedMessage>
    val diagnostics: Flow<DiagnosticEvent>

    suspend fun start()

    suspend fun stop()

    fun close()

    fun send(to: NodeId, payload: ByteArray, options: SendOptions, lifetimeSeconds: Int): OutgoingMessage

    fun broadcast(payload: ByteArray, hopLimit: Int): OutgoingMessage
}
