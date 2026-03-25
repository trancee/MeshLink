package io.meshlink

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
    val peers: Flow<PeerEvent>
    val messages: Flow<Message>
    val deliveryConfirmations: Flow<Uuid>
    val transferProgress: Flow<TransferProgress>
}
