package ch.trancee.meshlink.engine

import ch.trancee.meshlink.messaging.MessagingConfig
import ch.trancee.meshlink.power.PowerConfig
import ch.trancee.meshlink.routing.RoutingConfig
import ch.trancee.meshlink.transfer.ChunkSizePolicy
import ch.trancee.meshlink.transfer.TransferConfig

/**
 * Aggregate configuration for [MeshEngine], bundling all subsystem configs into one parameter
 * object.
 *
 * [messaging] defaults to [MessagingConfig] with an empty [appIdHash]; callers should always supply
 * an application-specific hash.
 */
data class MeshEngineConfig(
    val routing: RoutingConfig = RoutingConfig(),
    val messaging: MessagingConfig = MessagingConfig(appIdHash = ByteArray(0)),
    val transfer: TransferConfig = TransferConfig(),
    val power: PowerConfig = PowerConfig(),
    val handshake: HandshakeConfig = HandshakeConfig(),
    val chunkSize: ChunkSizePolicy = ChunkSizePolicy.GATT,
)
