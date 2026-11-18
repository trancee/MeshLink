package ch.trancee.meshlink.internal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The L2CAP CoC channels an [AppleCentralLink] opens to its [peripheral] (library rules R1 and
 * R2), one open at a time. CoreBluetooth never times out `openL2CAPChannel`, so [open] gives up
 * after [AppleCentralLink.CONNECT_TIMEOUT_MILLIS], and a channel that opens later, or as the link
 * closes, is closed. [close] closes them all with the link.
 */
internal class AppleChannelOpener(
    private val bluetooth: CoreBluetooth,
    private val peripheral: CbPeripheral,
) {
    private val opening = Mutex()
    private var pending: CompletableDeferred<CbChannel?>? = null
    private val open = mutableSetOf<AppleChannel>()
    private var closed = false

    /** Opens a channel to [psm]: [RadioFailure.LINK_CLOSED] once the link closed, else [RadioFailure.RADIO_ERROR]. */
    suspend fun open(psm: Int): RadioResult<RadioStream> =
        opening.withLock {
            val channel = if (closed) null else request(psm)
            when {
                closed -> {
                    channel?.close()
                    RadioResult.Failed(RadioFailure.LINK_CLOSED)
                }

                channel == null -> {
                    RadioResult.Failed(RadioFailure.RADIO_ERROR)
                }

                else -> {
                    val stream = AppleChannel(ApplePeer(peripheral), channel) { open -= it }
                    open += stream
                    RadioResult.Ok(stream)
                }
            }
        }

    /** `didOpen` an L2CAP channel, or null on an error; a channel nobody waits for is closed. */
    fun opened(channel: CbChannel?) {
        val waiting = pending
        if (waiting != null) waiting.complete(channel) else channel?.close()
    }

    /** The link closed: fails a pending [open] and closes every channel. */
    fun close() {
        closed = true
        pending?.complete(null)
        open.toList().forEach { it.disconnect() }
    }

    private suspend fun request(psm: Int): CbChannel? {
        val answer = CompletableDeferred<CbChannel?>()
        pending = answer
        bluetooth.openChannel(peripheral, psm)
        return try {
            withTimeoutOrNull(AppleCentralLink.CONNECT_TIMEOUT_MILLIS) { answer.await() }
        } finally {
            pending = null
        }
    }
}
