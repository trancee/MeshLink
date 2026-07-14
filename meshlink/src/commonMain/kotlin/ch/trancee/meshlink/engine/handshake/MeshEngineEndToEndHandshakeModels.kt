package ch.trancee.meshlink.engine.handshake

import ch.trancee.meshlink.crypto.NoiseXXHandshakeManager
import ch.trancee.meshlink.engine.internal.HopSession
import ch.trancee.meshlink.wire.WireFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex

/**
 * An authenticated end-to-end (multi-hop) session with a remote peer, established by a relayed
 * Noise XX handshake that traveled through zero or more intermediate relays. Unlike [HopSession],
 * which only ever spans one physical/direct connection, an [EndToEndSession] stays valid for as
 * long as both peers remain reachable through the mesh, however many hops apart they are.
 */
internal class EndToEndSession internal constructor(sendKey: ByteArray, receiveKey: ByteArray) {
    internal val sendKey: ByteArray = sendKey.copyOf()
    internal val receiveKey: ByteArray = receiveKey.copyOf()
    internal val outboundMutex: Mutex = Mutex()
    internal var sendNonce: ULong = 0u
    internal var receiveNonce: ULong = 0u
}

internal sealed class EndToEndSessionEstablishmentOutcome {
    class Established internal constructor(internal val session: EndToEndSession) :
        EndToEndSessionEstablishmentOutcome()

    data object TrustFailure : EndToEndSessionEstablishmentOutcome()

    data object Unreachable : EndToEndSessionEstablishmentOutcome()
}

internal class PendingEndToEndInitiatorHandshake
internal constructor(
    internal val handshakeId: String,
    internal val manager: NoiseXXHandshakeManager,
    internal val sessionDeferred: CompletableDeferred<EndToEndSessionEstablishmentOutcome>,
)

internal class PendingEndToEndResponderHandshake
internal constructor(
    internal val handshakeId: String,
    internal val manager: NoiseXXHandshakeManager,
)

internal class CreatedEndToEndInitiatorHandshake
internal constructor(
    internal val pendingHandshake: PendingEndToEndInitiatorHandshake,
    internal val message1Frame: WireFrame.EndToEndHandshakeMessage1,
)
