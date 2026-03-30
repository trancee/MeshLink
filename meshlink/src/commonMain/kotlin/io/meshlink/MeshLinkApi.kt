package io.meshlink

import io.meshlink.diagnostics.DiagnosticEvent
import io.meshlink.diagnostics.MeshHealthSnapshot
import io.meshlink.model.KeyChangeEvent
import io.meshlink.model.Message
import io.meshlink.model.PeerDetail
import io.meshlink.model.PeerEvent
import io.meshlink.model.TransferFailure
import io.meshlink.model.TransferProgress
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
    val diagnosticEvents: Flow<DiagnosticEvent>
    fun sweep(seenPeers: Set<String>): Set<String>
    fun sweepStaleTransfers(maxAgeMillis: Long): Int
    fun sweepStaleReassemblies(maxAgeMillis: Long): Int
    fun sweepExpiredPendingMessages(): Int
    fun shedMemoryPressure(): List<String>
    fun addRoute(destination: String, nextHop: String, cost: Double, sequenceNumber: UInt)
    fun updateBattery(batteryPercent: Int, isCharging: Boolean)
    fun setCustomPowerMode(mode: String?)
    val peers: Flow<PeerEvent>
    val messages: Flow<Message>
    val deliveryConfirmations: Flow<Uuid>
    val transferFailures: Flow<TransferFailure>
    val transferProgress: Flow<TransferProgress>
    val meshHealthFlow: Flow<MeshHealthSnapshot>
    val keyChanges: Flow<KeyChangeEvent>
    val localPublicKey: ByteArray?
    val broadcastPublicKey: ByteArray?
    fun peerPublicKey(peerIdHex: String): ByteArray?
    fun peerDetail(peerIdHex: String): PeerDetail?
    fun allPeerDetails(): List<PeerDetail>
    fun rotateIdentity(): Result<Unit>
}
