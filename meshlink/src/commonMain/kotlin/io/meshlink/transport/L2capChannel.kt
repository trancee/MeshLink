package io.meshlink.transport

import kotlinx.coroutines.flow.SharedFlow

/**
 * Abstraction for an L2CAP Connection-oriented Channel (CoC).
 *
 * Platform implementations wrap the native L2CAP socket
 * (Android BluetoothSocket / iOS CBL2CAPChannel) behind this interface
 * so the common layer can read and write without platform knowledge.
 */
interface L2capChannel {

    /** The PSM (Protocol/Service Multiplexer) number for this channel. */
    val psm: Int

    /** Whether the channel is currently open and usable. */
    val isOpen: Boolean

    /** Flow of incoming data frames received from the remote peer. */
    val incoming: SharedFlow<ByteArray>

    /** Write [data] to the channel. Suspends until the write completes. */
    suspend fun write(data: ByteArray)

    /** Close the channel and release resources. */
    fun close()
}
