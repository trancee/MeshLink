package io.meshlink

import io.meshlink.diagnostics.DiagnosticEvent
import io.meshlink.diagnostics.MeshHealthSnapshot
import io.meshlink.model.Message
import io.meshlink.model.PeerEvent
import io.meshlink.model.TransferProgress
import io.meshlink.transport.BleTransport
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
interface MeshLinkApi {
    fun start(): Result<Unit>
    fun stop()
    fun pause()
    fun resume()
    fun send(recipient: ByteArray, payload: ByteArray): Result<Uuid>
    fun broadcast(payload: ByteArray, maxHops: UByte): Result<Uuid>
    fun meshHealth(): MeshHealthSnapshot
    fun drainDiagnostics(): List<DiagnosticEvent>
    fun sweep(seenPeers: Set<String>): Set<String>
    fun addRoute(destination: String, nextHop: String, cost: Double, sequenceNumber: UInt)
    val peers: Flow<PeerEvent>
    val messages: Flow<Message>
    val deliveryConfirmations: Flow<Uuid>
    val transferProgress: Flow<TransferProgress>
}
