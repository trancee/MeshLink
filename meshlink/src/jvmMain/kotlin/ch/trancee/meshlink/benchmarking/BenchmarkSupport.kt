@file:OptIn(UnstableMeshLinkBenchmarkApi::class)

package ch.trancee.meshlink.benchmarking

/**
 * JVM-only bridge wrappers used by the separate `:benchmarks` module.
 *
 * Every public declaration in this file is intentionally marked with [UnstableMeshLinkBenchmarkApi]
 * to make benchmark-only usage explicit.
 */
import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.MeshLink
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.config.MeshLinkConfig
import ch.trancee.meshlink.crypto.JvmCryptoProvider
import ch.trancee.meshlink.crypto.NoiseIdentity
import ch.trancee.meshlink.diagnostics.DiagnosticEvent
import ch.trancee.meshlink.diagnostics.DiagnosticSink
import ch.trancee.meshlink.engine.MeshEngine
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.identity.toBytes
import ch.trancee.meshlink.routing.RouteCoordinator
import ch.trancee.meshlink.routing.RoutingMutation
import ch.trancee.meshlink.storage.InMemorySecureStorage
import ch.trancee.meshlink.transport.BleTransport
import ch.trancee.meshlink.transport.OutboundFrame
import ch.trancee.meshlink.transport.TransportEvent
import ch.trancee.meshlink.transport.TransportMode
import ch.trancee.meshlink.transport.TransportSendResult
import ch.trancee.meshlink.trust.TrustPublicKeys
import ch.trancee.meshlink.trust.TrustRecord
import ch.trancee.meshlink.wire.WireCodec
import ch.trancee.meshlink.wire.WireFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@UnstableMeshLinkBenchmarkApi
public class BenchmarkX25519KeyPair
public constructor(privateKey: ByteArray, publicKey: ByteArray) {
    public val privateKey: ByteArray = privateKey.copyOf()
    public val publicKey: ByteArray = publicKey.copyOf()
}

@UnstableMeshLinkBenchmarkApi
public class BenchmarkCryptoProvider public constructor() {
    private val delegate: JvmCryptoProvider = JvmCryptoProvider()

    public fun randomBytes(size: Int): ByteArray {
        return delegate.randomBytes(size)
    }

    public fun generateX25519KeyPair(): BenchmarkX25519KeyPair {
        val keyPair = delegate.generateX25519KeyPair()
        return BenchmarkX25519KeyPair(
            privateKey = keyPair.privateKey,
            publicKey = keyPair.publicKey,
        )
    }

    public fun x25519(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        return delegate.x25519(privateKey = privateKey, publicKey = publicKey)
    }

    public fun chacha20Poly1305Seal(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        plaintext: ByteArray,
    ): ByteArray {
        return delegate.chacha20Poly1305Seal(
            key = key,
            nonce = nonce,
            aad = aad,
            plaintext = plaintext,
        )
    }

    public fun chacha20Poly1305Open(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray {
        return delegate.chacha20Poly1305Open(
            key = key,
            nonce = nonce,
            aad = aad,
            ciphertext = ciphertext,
        )
    }
}

@UnstableMeshLinkBenchmarkApi
public class BenchmarkTrustPublicKeys
public constructor(ed25519PublicKey: ByteArray, x25519PublicKey: ByteArray) {
    public val ed25519PublicKey: ByteArray = ed25519PublicKey.copyOf()
    public val x25519PublicKey: ByteArray = x25519PublicKey.copyOf()
}

@UnstableMeshLinkBenchmarkApi
public class BenchmarkTrustRecord
public constructor(
    public val peerIdValue: String,
    public val identityFingerprint: String? = null,
    identityFingerprintBytes: ByteArray? = null,
    public val firstSeenAtEpochMillis: Long,
    public val lastVerifiedAtEpochMillis: Long,
    public val publicKeys: BenchmarkTrustPublicKeys,
) {
    public val identityFingerprintBytes: ByteArray? = identityFingerprintBytes?.copyOf()
}

@UnstableMeshLinkBenchmarkApi
public sealed class BenchmarkWireFrame {
    public class RouteUpdateMetrics
    public constructor(
        public val metric: Int,
        public val seqNo: Long,
        public val feasibilityMetric: Int,
    )

    public class RouteUpdatePublicKeys
    public constructor(
        destinationEd25519PublicKey: ByteArray,
        destinationX25519PublicKey: ByteArray,
    ) {
        public val destinationEd25519PublicKey: ByteArray = destinationEd25519PublicKey.copyOf()
        public val destinationX25519PublicKey: ByteArray = destinationX25519PublicKey.copyOf()
    }

    public class RouteUpdate
    public constructor(
        public val destinationPeerId: PeerId,
        public val nextHopPeerId: PeerId,
        public val metrics: RouteUpdateMetrics,
        public val publicKeys: RouteUpdatePublicKeys,
    ) : BenchmarkWireFrame()

    public class RouteRetraction
    public constructor(public val destinationPeerId: PeerId, public val seqNo: Long) :
        BenchmarkWireFrame()

    public class RouteDigest public constructor(public val peerId: PeerId, digest: ByteArray) :
        BenchmarkWireFrame() {
        public val digest: ByteArray = digest.copyOf()
    }

    public class Message
    public constructor(
        public val messageId: String,
        public val originPeerId: PeerId,
        public val destinationPeerId: PeerId,
        public val priority: DeliveryPriority,
        public val ttlMillis: Int,
        encryptedPayload: ByteArray,
    ) : BenchmarkWireFrame() {
        public val encryptedPayload: ByteArray = encryptedPayload.copyOf()
    }

    public class TransferChunk
    public constructor(
        public val transferId: String,
        public val chunkIndex: Int,
        payload: ByteArray,
    ) : BenchmarkWireFrame() {
        public val payload: ByteArray = payload.copyOf()
    }
}

@UnstableMeshLinkBenchmarkApi
public class BenchmarkAdvertisement
public constructor(public val targetPeerId: PeerId, public val frame: BenchmarkWireFrame)

@UnstableMeshLinkBenchmarkApi
public object BenchmarkWireCodec {
    public fun encode(frame: BenchmarkWireFrame): ByteArray {
        return WireCodec.encode(frame = frame.toInternal())
    }

    public fun decode(bytes: ByteArray): BenchmarkWireFrame {
        return WireCodec.decode(bytes = bytes).toBenchmark()
    }
}

@UnstableMeshLinkBenchmarkApi
public class BenchmarkRouteCoordinator public constructor(localPeerId: PeerId) {
    private val delegate: RouteCoordinator = RouteCoordinator(localPeerId)

    public fun onPeerConnected(
        peerId: PeerId,
        trustRecord: BenchmarkTrustRecord,
    ): List<BenchmarkAdvertisement> {
        return delegate
            .onPeerConnected(peerId = peerId, trustRecord = trustRecord.toInternal())
            .toBenchmarkAdvertisements()
    }

    public fun onPeerDisconnected(peerId: PeerId): List<BenchmarkAdvertisement> {
        return delegate.onPeerDisconnected(peerId = peerId).toBenchmarkAdvertisements()
    }

    public fun onRouteUpdate(
        fromPeerId: PeerId,
        update: BenchmarkWireFrame.RouteUpdate,
    ): List<BenchmarkAdvertisement> {
        return delegate
            .onRouteUpdate(fromPeerId = fromPeerId, update = update.toInternal())
            .toBenchmarkAdvertisements()
    }

    public fun onRouteRetraction(
        fromPeerId: PeerId,
        retraction: BenchmarkWireFrame.RouteRetraction,
    ): List<BenchmarkAdvertisement> {
        return delegate
            .onRouteRetraction(fromPeerId = fromPeerId, retraction = retraction.toInternal())
            .toBenchmarkAdvertisements()
    }

    public fun onRouteDigest(fromPeerId: PeerId, digest: BenchmarkWireFrame.RouteDigest): Unit {
        delegate.onRouteDigest(fromPeerId = fromPeerId, frame = digest.toInternal())
    }

    public fun nextHopFor(destinationPeerId: PeerId): PeerId? {
        return delegate.nextHopFor(destinationPeerId = destinationPeerId)
    }

    public fun hasRoute(destinationPeerId: PeerId): Boolean {
        return delegate.routeFor(destinationPeerId = destinationPeerId) != null
    }
}

@UnstableMeshLinkBenchmarkApi
public enum class BenchmarkTransportMode {
    L2CAP,
    GATT,
}

@UnstableMeshLinkBenchmarkApi
public class BenchmarkOutboundFrame
public constructor(
    public val peerId: PeerId,
    payload: ByteArray,
    public val preferredMode: BenchmarkTransportMode? = null,
) {
    public val payload: ByteArray = payload.copyOf()
}

@UnstableMeshLinkBenchmarkApi
public sealed class BenchmarkTransportSendResult {
    public data object Delivered : BenchmarkTransportSendResult()

    public class Dropped public constructor(public val reason: String) :
        BenchmarkTransportSendResult()
}

@UnstableMeshLinkBenchmarkApi
public sealed class BenchmarkTransportEvent {
    public class PeerDiscovered
    public constructor(
        public val peerId: PeerId,
        public val transportMode: BenchmarkTransportMode,
    ) : BenchmarkTransportEvent()

    public class PeerLost public constructor(public val peerId: PeerId) : BenchmarkTransportEvent()

    public class FrameReceived public constructor(public val peerId: PeerId, payload: ByteArray) :
        BenchmarkTransportEvent() {
        public val payload: ByteArray = payload.copyOf()
    }

    public class TransportModeChanged
    public constructor(
        public val peerId: PeerId,
        public val transportMode: BenchmarkTransportMode,
    ) : BenchmarkTransportEvent()
}

@UnstableMeshLinkBenchmarkApi
public interface BenchmarkBleTransport {
    public val events: Flow<BenchmarkTransportEvent>

    public suspend fun start(): Unit

    public suspend fun pause(): Unit

    public suspend fun resume(): Unit

    public suspend fun stop(): Unit

    public suspend fun send(frame: BenchmarkOutboundFrame): BenchmarkTransportSendResult
}

@UnstableMeshLinkBenchmarkApi
public fun interface BenchmarkDiagnosticSink {
    public fun emit(event: DiagnosticEvent): Unit
}

@UnstableMeshLinkBenchmarkApi
public fun createBenchmarkMeshLink(
    config: MeshLinkConfig,
    peerId: PeerId,
    bleTransport: BenchmarkBleTransport? = null,
    diagnosticSink: BenchmarkDiagnosticSink? = null,
): MeshLink {
    val provider = JvmCryptoProvider()
    val localIdentity =
        LocalIdentity.fromNoiseIdentity(
            noiseIdentity = NoiseIdentity.generate(provider = provider),
            provider = provider,
            peerId = peerId,
        )
    return MeshEngine.create(
        config = config,
        localIdentity = localIdentity,
        secureStorage = InMemorySecureStorage(),
        bleTransport = bleTransport?.let(::BenchmarkBleTransportAdapter),
        diagnosticSink = diagnosticSink?.let(::BenchmarkDiagnosticSinkAdapter),
    )
}

private class BenchmarkBleTransportAdapter(private val delegate: BenchmarkBleTransport) :
    BleTransport {
    override val events: Flow<TransportEvent>
        get() = delegate.events.map(BenchmarkTransportEvent::toInternal)

    override suspend fun start(): Unit {
        delegate.start()
    }

    override suspend fun pause(): Unit {
        delegate.pause()
    }

    override suspend fun resume(): Unit {
        delegate.resume()
    }

    override suspend fun stop(): Unit {
        delegate.stop()
    }

    override suspend fun send(frame: OutboundFrame): TransportSendResult {
        return delegate.send(frame.toBenchmark()).toInternal()
    }
}

private class BenchmarkDiagnosticSinkAdapter(private val delegate: BenchmarkDiagnosticSink) :
    DiagnosticSink {
    override fun emit(event: DiagnosticEvent): Unit {
        delegate.emit(event)
    }
}

private fun BenchmarkTrustRecord.toInternal(): TrustRecord {
    val resolvedFingerprintBytes =
        identityFingerprintBytes
            ?: identityFingerprint?.toBytes()
            ?: error("identity fingerprint is required")
    return TrustRecord(
        peerIdValue = peerIdValue,
        identityFingerprintBytes = resolvedFingerprintBytes,
        firstSeenAtEpochMillis = firstSeenAtEpochMillis,
        lastVerifiedAtEpochMillis = lastVerifiedAtEpochMillis,
        publicKeys =
            TrustPublicKeys(
                ed25519PublicKey = publicKeys.ed25519PublicKey,
                x25519PublicKey = publicKeys.x25519PublicKey,
            ),
    )
}

private fun BenchmarkWireFrame.RouteUpdate.toInternal(): WireFrame.RouteUpdate {
    return WireFrame.RouteUpdate(
        destinationPeerId = destinationPeerId,
        nextHopPeerId = nextHopPeerId,
        metrics =
            WireFrame.RouteUpdateMetrics(
                metric = metrics.metric,
                seqNo = metrics.seqNo,
                feasibilityMetric = metrics.feasibilityMetric,
            ),
        publicKeys =
            WireFrame.RouteUpdatePublicKeys(
                destinationEd25519PublicKey = publicKeys.destinationEd25519PublicKey,
                destinationX25519PublicKey = publicKeys.destinationX25519PublicKey,
            ),
    )
}

private fun BenchmarkWireFrame.RouteRetraction.toInternal(): WireFrame.RouteRetraction {
    return WireFrame.RouteRetraction(destinationPeerId = destinationPeerId, seqNo = seqNo)
}

private fun BenchmarkWireFrame.RouteDigest.toInternal(): WireFrame.RouteDigest {
    return WireFrame.RouteDigest(peerId = peerId, digest = digest)
}

private fun BenchmarkWireFrame.toInternal(): WireFrame {
    return when (this) {
        is BenchmarkWireFrame.Message ->
            WireFrame.Message(
                messageId = messageId,
                originPeerId = originPeerId,
                destinationPeerId = destinationPeerId,
                priority = priority,
                ttlMillis = ttlMillis,
                encryptedPayload = encryptedPayload,
            )

        is BenchmarkWireFrame.RouteDigest -> toInternal()
        is BenchmarkWireFrame.RouteRetraction -> toInternal()
        is BenchmarkWireFrame.RouteUpdate -> toInternal()
        is BenchmarkWireFrame.TransferChunk ->
            WireFrame.TransferChunk(
                transferId = transferId,
                chunkIndex = chunkIndex,
                payload = payload,
            )
    }
}

private fun WireFrame.toBenchmark(): BenchmarkWireFrame {
    return when (this) {
        is WireFrame.Message ->
            BenchmarkWireFrame.Message(
                messageId = messageId,
                originPeerId = originPeerId,
                destinationPeerId = destinationPeerId,
                priority = priority,
                ttlMillis = ttlMillis,
                encryptedPayload = encryptedPayload,
            )

        is WireFrame.RouteDigest -> BenchmarkWireFrame.RouteDigest(peerId = peerId, digest = digest)
        is WireFrame.RouteRetraction ->
            BenchmarkWireFrame.RouteRetraction(destinationPeerId = destinationPeerId, seqNo = seqNo)

        is WireFrame.RouteUpdate ->
            BenchmarkWireFrame.RouteUpdate(
                destinationPeerId = destinationPeerId,
                nextHopPeerId = nextHopPeerId,
                metrics =
                    BenchmarkWireFrame.RouteUpdateMetrics(
                        metric = metric,
                        seqNo = seqNo,
                        feasibilityMetric = feasibilityMetric,
                    ),
                publicKeys =
                    BenchmarkWireFrame.RouteUpdatePublicKeys(
                        destinationEd25519PublicKey = destinationEd25519PublicKey,
                        destinationX25519PublicKey = destinationX25519PublicKey,
                    ),
            )

        is WireFrame.TransferChunk ->
            BenchmarkWireFrame.TransferChunk(
                transferId = transferId,
                chunkIndex = chunkIndex,
                payload = payload,
            )

        else -> error("BenchmarkWireCodec does not support ${this::class.simpleName}")
    }
}

private fun RoutingMutation.toBenchmarkAdvertisements(): List<BenchmarkAdvertisement> {
    return advertisements.map { advertisement ->
        BenchmarkAdvertisement(
            targetPeerId = advertisement.targetPeerId,
            frame = advertisement.frame.toBenchmark(),
        )
    }
}

private fun BenchmarkTransportMode.toInternal(): TransportMode {
    return when (this) {
        BenchmarkTransportMode.GATT -> TransportMode.GATT
        BenchmarkTransportMode.L2CAP -> TransportMode.L2CAP
    }
}

private fun BenchmarkOutboundFrame.toInternal(): OutboundFrame {
    return OutboundFrame(
        peerId = peerId,
        payload = payload,
        preferredMode = preferredMode?.toInternal(),
    )
}

private fun BenchmarkTransportSendResult.toInternal(): TransportSendResult {
    return when (this) {
        BenchmarkTransportSendResult.Delivered -> TransportSendResult.Delivered
        is BenchmarkTransportSendResult.Dropped -> TransportSendResult.Dropped(reason = reason)
    }
}

private fun BenchmarkTransportEvent.toInternal(): TransportEvent {
    return when (this) {
        is BenchmarkTransportEvent.FrameReceived ->
            TransportEvent.FrameReceived(peerId = peerId, payload = payload)

        is BenchmarkTransportEvent.PeerDiscovered ->
            TransportEvent.PeerDiscovered(
                peerId = peerId,
                transportMode = transportMode.toInternal(),
            )

        is BenchmarkTransportEvent.PeerLost -> TransportEvent.PeerLost(peerId = peerId)
        is BenchmarkTransportEvent.TransportModeChanged ->
            TransportEvent.TransportModeChanged(
                peerId = peerId,
                transportMode = transportMode.toInternal(),
            )
    }
}

private fun TransportMode.toBenchmark(): BenchmarkTransportMode {
    return when (this) {
        TransportMode.GATT -> BenchmarkTransportMode.GATT
        TransportMode.L2CAP -> BenchmarkTransportMode.L2CAP
    }
}

private fun OutboundFrame.toBenchmark(): BenchmarkOutboundFrame {
    return BenchmarkOutboundFrame(
        peerId = peerId,
        payload = payload,
        preferredMode = preferredMode?.toBenchmark(),
    )
}
