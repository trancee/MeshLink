package io.meshlink

import io.meshlink.model.Message
import io.meshlink.model.PeerEvent
import io.meshlink.transport.BleTransport
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
interface MeshLinkApi {
    fun start(): Result<Unit>
    fun stop()
    fun send(recipient: ByteArray, payload: ByteArray): Result<Uuid>
    val peers: Flow<PeerEvent>
    val messages: Flow<Message>
}
