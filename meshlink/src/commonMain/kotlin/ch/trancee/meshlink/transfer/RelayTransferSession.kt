package ch.trancee.meshlink.transfer

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.engine.MeshEngineHardRunToken

internal class RelayTransferSession
internal constructor(
    internal val transferId: String,
    internal val messageId: String,
    internal val originPeerId: PeerId,
    internal val destinationPeerId: PeerId,
    internal var upstreamPeerId: PeerId,
    internal val hardRunToken: MeshEngineHardRunToken,
)
