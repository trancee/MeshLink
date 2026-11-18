package ch.trancee.meshlink.internal

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Accepts the L2CAP CoC channels centrals open to this peripheral (spec "Bearer switch"). Android
 * 10 (API 29) added LE CoC; below it [listen] fails with [RadioFailure.UNSUPPORTED] (library rule
 * R3), so the node offers no channel. One server listens at a time, waiting for channels on [io];
 * each one accepted arrives through [emit] as [RadioEvent.ChannelOpened]. A server that fails, or
 * Bluetooth turning off ([close]), closes the server and every channel it accepted; centrals that
 * read the old PSM then fail to open a channel and stay on GATT. Cancelling [scope] closes them too.
 */
internal class CocListener(
    private val adapter: BluetoothAdapter,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val emit: (RadioEvent) -> Unit,
) {
    private val lock = Any()
    private var listening: Listening? = null
    private val accepted = ConcurrentHashMap.newKeySet<CocChannel>()

    /**
     * Listens on an unencrypted server (library rule R2) and returns its PSM, the same one if
     * already listening. Fails with [RadioFailure.RADIO_ERROR] if the stack refuses the server.
     */
    fun listen(): RadioResult<Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            synchronized(lock) {
                val opened = if (listening == null) runCatching { adapter.listenUsingInsecureL2capChannel() } else null
                opened?.getOrNull()?.let { start(it, it.psm) }
                listening?.let { RadioResult.Ok(it.psm) } ?: RadioResult.Failed(RadioFailure.RADIO_ERROR)
            }
        } else {
            RadioResult.Failed(RadioFailure.UNSUPPORTED)
        }

    /** Closes the server; accepted channels stay open. Does nothing if not listening. */
    fun stop() {
        synchronized(lock) {
            listening?.let { runCatching { it.server.close() } }
            listening = null
        }
    }

    /** Closes the server and every channel it accepted, as when Bluetooth turns off. */
    fun close() {
        stop()
        accepted.forEach { it.disconnect() }
    }

    private fun start(
        server: BluetoothServerSocket,
        psm: Int,
    ) {
        val started = Listening(server, psm)
        listening = started
        scope.launch {
            val accepting = launch(io) { accept(started) }
            try {
                accepting.join()
            } catch (cancelled: CancellationException) {
                // Cancelling a coroutine cannot abort a blocking accept; closing its server does.
                close()
                throw cancelled
            }
        }
    }

    /** Accepts channels on [started] until its server closes; a server that failed closes everything. */
    private fun accept(started: Listening) {
        var socket = runCatching { started.server.accept() }.getOrNull()
        while (socket != null) {
            val channel = CocChannel(AndroidPeer(socket.remoteDevice), socket, io, accepted::remove)
            accepted += channel
            emit(RadioEvent.ChannelOpened(channel))
            socket = runCatching { started.server.accept() }.getOrNull()
        }
        val failed = synchronized(lock) { listening === started }
        if (failed) close()
    }

    /** An open [server] and the [psm] the platform assigned it. */
    private class Listening(
        val server: BluetoothServerSocket,
        val psm: Int,
    )
}
