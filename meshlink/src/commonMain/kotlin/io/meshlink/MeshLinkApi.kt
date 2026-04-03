package io.meshlink

import io.meshlink.diagnostics.DiagnosticEvent
import io.meshlink.diagnostics.MeshHealthSnapshot
import io.meshlink.model.KeyChangeEvent
import io.meshlink.model.Message
import io.meshlink.model.MessageId
import io.meshlink.model.PeerDetail
import io.meshlink.model.PeerEvent
import io.meshlink.model.SendResult
import io.meshlink.model.TransferFailure
import io.meshlink.model.TransferProgress
import io.meshlink.power.PowerMode
import kotlinx.coroutines.flow.Flow

interface MeshLinkApi {
    fun start(): Result<Unit>
    fun stop()
    fun pause()
    fun resume()
    fun send(recipient: ByteArray, payload: ByteArray, priority: Byte = 0): Result<SendResult>
    fun broadcast(payload: ByteArray, maxHops: UByte, priority: Byte = 0): Result<MessageId>
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
    fun setCustomPowerMode(mode: PowerMode?)
    val peers: Flow<PeerEvent>
    val messages: Flow<Message>
    val deliveryConfirmations: Flow<MessageId>
    val transferFailures: Flow<TransferFailure>
    val transferProgress: Flow<TransferProgress>
    val meshHealthFlow: Flow<MeshHealthSnapshot>
    val keyChanges: Flow<KeyChangeEvent>
    val localPublicKey: ByteArray?
    val broadcastPublicKey: ByteArray?
    fun peerPublicKey(peerIdHex: String): ByteArray?
    fun peerDetail(peerIdHex: String): PeerDetail?
    fun allPeerDetails(): List<PeerDetail>

    /**
     * Compute a human-readable safety number for verifying a peer's identity
     * out-of-band (e.g., comparing numbers in person, QR code).
     *
     * Returns a 12-digit numeric string derived from the SHA-256 of both peers'
     * public keys (local + remote), or null if either key is unavailable.
     *
     * The safety number is symmetric: `peerFingerprint(A, B) == peerFingerprint(B, A)`.
     * Both sides compute the same number regardless of who initiates.
     *
     * Usage: consuming apps should display this number to users during first
     * contact and prompt them to verify it matches the other device's display.
     */
    fun peerFingerprint(peerIdHex: String): String?

    fun rotateIdentity(): Result<Unit>
}
