package ch.trancee.meshlink.internal

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.Build
import kotlinx.coroutines.CoroutineDispatcher
import java.util.concurrent.ConcurrentHashMap

/**
 * The L2CAP CoC channels a central link opens to its [device] (spec "Bearer switch"). Android 10
 * (API 29) added LE CoC; below it [open] fails with [RadioFailure.UNSUPPORTED] (library rule R3).
 * The channels close with the link: [close] closes every open one and fails any still connecting.
 */
internal class CocChannels(
    private val device: BluetoothDevice,
    private val io: CoroutineDispatcher,
) {
    private val open = ConcurrentHashMap.newKeySet<CocChannel>()

    @Volatile private var closed = false

    /**
     * Opens an unencrypted channel to [psm] (library rule R2). Fails with
     * [RadioFailure.LINK_CLOSED] once the link has closed, and with [RadioFailure.RADIO_ERROR] if
     * the stack refuses the socket or its connect fails.
     */
    suspend fun open(psm: Int): RadioResult<RadioStream> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            connect(if (closed) null else runCatching { device.createInsecureL2capChannel(psm) }.getOrNull())
        } else {
            RadioResult.Failed(RadioFailure.UNSUPPORTED)
        }

    /** The link closed: closes every channel, including one still connecting. */
    fun close() {
        closed = true
        open.forEach { it.disconnect() }
    }

    /** Connects [socket], or fails if there is none because the link closed or the stack refused it. */
    private suspend fun connect(socket: BluetoothSocket?): RadioResult<RadioStream> {
        val channel = socket?.let { CocChannel(AndroidPeer(device), it, io, open::remove).also(open::add) }
        // A link that closed while the socket was created left the channel to close here.
        if (closed) channel?.disconnect()
        val ready = channel?.takeIf { it.connect() }
        if (ready == null) channel?.disconnect()
        return when {
            closed -> RadioResult.Failed(RadioFailure.LINK_CLOSED)
            ready != null -> RadioResult.Ok(ready)
            else -> RadioResult.Failed(RadioFailure.RADIO_ERROR)
        }
    }
}
