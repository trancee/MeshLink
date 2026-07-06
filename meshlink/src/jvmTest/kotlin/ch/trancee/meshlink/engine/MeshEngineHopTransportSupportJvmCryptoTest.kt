package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.crypto.Ed25519KeyPair
import ch.trancee.meshlink.crypto.JvmCryptoProvider
import ch.trancee.meshlink.crypto.NoiseIdentity
import ch.trancee.meshlink.crypto.X25519KeyPair
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.routing.RouteCoordinator
import ch.trancee.meshlink.transport.TransportSendResult
import ch.trancee.meshlink.wire.WireCodec
import ch.trancee.meshlink.wire.WireFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

/**
 * Exercises [MeshEngineHopTransportSupport]'s hop-frame AAD binding against the *real* JVM AEAD
 * implementation ([JvmCryptoProvider]), rather than the
 * [ch.trancee.meshlink.crypto.PlaceholderCryptoProvider] used throughout
 * [MeshEngineHopTransportSupportTest]. The placeholder is a simplified test double whose
 * pseudo-AEAD tag computation only ever reads the first 32 bytes of its `key + nonce + aad +
 * ciphertext` input -- which, for a 32-byte key, means the tag never actually depends on the nonce,
 * AAD, or ciphertext at all. That makes it unsuitable for verifying the explicit-sequence-number
 * replay-protection design's core security property: that the sequence number header is
 * cryptographically bound to the frame via AAD (see
 * docs/explanation/hop-session-replay-protection.md), so tampering with it invalidates the frame
 * instead of silently letting an on-path relay relabel a captured frame's declared sequence number.
 * This test uses the production JVM crypto provider so that property is checked against a real
 * ChaCha20-Poly1305 implementation.
 */
class MeshEngineHopTransportSupportJvmCryptoTest {
    @Test
    fun `decryptHopPayload rejects a frame whose declared sequence number was tampered with`() =
        runBlocking<Unit> {
            // Arrange
            val localIdentity = jvmLocalIdentity("hop-transport-jvm-tamper")
            val peerId = PeerId("peer-abcdef")
            val session = hopSession(keyByte = 0xAA.toByte())
            val fixture = hopTransportFixture(localIdentity = localIdentity)
            val ciphertext =
                encryptedPayloadFor(
                    fixture = fixture,
                    peerId = peerId,
                    session = session,
                    transferId = "transfer-tamper",
                )
            val tamperedCiphertext =
                ciphertext.copyOf().also { bytes ->
                    // Flip a bit in the sequence-number header (byte offset 1, just after the
                    // version byte) without re-sealing, simulating an on-path relay trying to
                    // relabel the frame's declared sequence number.
                    bytes[1] = (bytes[1].toInt() xor 0x01).toByte()
                }

            // Act
            val exception =
                assertFailsWith<Exception> {
                    fixture.support.decryptHopPayload(session, tamperedCiphertext)
                }

            // Assert: a real authentication failure (not a replay/format rejection), and the
            // genuine (untampered) frame must still decrypt successfully afterward -- the failed
            // attempt must not have consumed the sequence number's replay-window slot.
            assertTrue(exception !== ReplayedHopPayloadException)
            assertTrue(exception !is HopFrameFormatException)
            val plaintext = fixture.support.decryptHopPayload(session, ciphertext)
            assertEquals(
                "transfer-tamper",
                assertIs<WireFrame.TransferComplete>(WireCodec.decode(plaintext)).transferId,
            )
        }

    @Test
    fun `decryptHopPayload rejects a frame whose ciphertext body was tampered with`() =
        runBlocking<Unit> {
            // Arrange: sanity check that ordinary ciphertext corruption is still rejected (this
            // was already true before the replay-window change; guards against a regression in
            // how the header/ciphertext split is wired into the real AEAD call).
            val localIdentity = jvmLocalIdentity("hop-transport-jvm-corrupt")
            val peerId = PeerId("peer-abcdef")
            val session = hopSession(keyByte = 0xBB.toByte())
            val fixture = hopTransportFixture(localIdentity = localIdentity)
            val ciphertext =
                encryptedPayloadFor(
                    fixture = fixture,
                    peerId = peerId,
                    session = session,
                    transferId = "transfer-corrupt",
                )
            val corruptedCiphertext =
                ciphertext.copyOf().also { bytes -> bytes[bytes.size - 1] = bytes.last().inc() }

            // Act & Assert
            val exception =
                assertFailsWith<Exception> {
                    fixture.support.decryptHopPayload(session, corruptedCiphertext)
                }
            assertTrue(exception !== ReplayedHopPayloadException)
            assertTrue(exception !is HopFrameFormatException)
        }
}

private fun jvmLocalIdentity(seed: String): LocalIdentity {
    val provider = JvmCryptoProvider()
    val noiseIdentity =
        NoiseIdentity(
            ed25519KeyPair = ed25519KeyPair(provider),
            x25519KeyPair = x25519KeyPair(provider),
        )
    return LocalIdentity.fromNoiseIdentity(
        noiseIdentity = noiseIdentity,
        provider = provider,
        peerId = PeerId(seed),
    )
}

private fun ed25519KeyPair(provider: JvmCryptoProvider): Ed25519KeyPair {
    val keyPair = provider.generateEd25519KeyPair()
    return Ed25519KeyPair(privateKey = keyPair.privateKey, publicKey = keyPair.publicKey)
}

private fun x25519KeyPair(provider: JvmCryptoProvider): X25519KeyPair {
    val keyPair = provider.generateX25519KeyPair()
    return X25519KeyPair(privateKey = keyPair.privateKey, publicKey = keyPair.publicKey)
}

private fun hopSession(keyByte: Byte): HopSession {
    val key = ByteArray(32) { keyByte }
    return HopSession(sendKey = key, receiveKey = key)
}

private suspend fun encryptedPayloadFor(
    fixture: JvmHopTransportFixture,
    peerId: PeerId,
    session: HopSession,
    transferId: String,
): ByteArray {
    fixture.support.sendEncryptedDirectWireFrame(
        peerId = peerId,
        session = session,
        frame = WireFrame.TransferComplete(transferId),
        action = "transfer.complete",
    )
    return assertIs<DirectWireFrame.Data>(fixture.sentFrames.last().frame).payload
}

private data class JvmHopTransportFixture(
    val support: MeshEngineHopTransportSupport,
    val sentFrames: MutableList<RecordedJvmHopTransportSend>,
)

private data class RecordedJvmHopTransportSend(val frame: DirectWireFrame)

private fun hopTransportFixture(localIdentity: LocalIdentity): JvmHopTransportFixture {
    val sentFrames = mutableListOf<RecordedJvmHopTransportSend>()
    val runtimeSurface = MeshEngineRuntimeSurface()
    val routingSupport =
        RouteCoordinator(localIdentity.peerId).let { routeCoordinator ->
            MeshEngineRoutingSupport(
                routeCoordinator = routeCoordinator,
                runtimeGate = runtimeSurface.runtimeGate,
                coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                emitDiagnostic = { _, _, _, _, _, _ -> },
                sendEncryptedWireFrame = { _, _, _, _ -> true },
            )
        }
    val support =
        MeshEngineHopTransportSupport(
            localIdentity = localIdentity,
            runtimeGate = runtimeSurface.runtimeGate,
            routingSupport = routingSupport,
            establishedHopSession = { null },
            ensureHopSession = { _, _ -> SessionEstablishmentOutcome.Unreachable },
            sendDirectWireFrame = { _, frame, _, _ ->
                sentFrames += RecordedJvmHopTransportSend(frame)
                TransportSendResult.Delivered
            },
            emitDiagnostic = { _, _, _, _, _, _ -> },
        )
    return JvmHopTransportFixture(support = support, sentFrames = sentFrames)
}
