package ch.trancee.meshlink.internal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The L2CAP CoC listener of [AppleRadio]'s peripheral side (library rules R1 and R2): [listen]
 * publishes one unencrypted channel and waits for its PSM. Each channel a central opens arrives as
 * [RadioEvent.ChannelOpened] with the peer of that central's GATT link, an [AppleCentralPeer].
 * CoreBluetooth ignores a publish while the peripheral manager in [managerState] isn't powered on,
 * so [listen] fails at once then. Bluetooth turning off forgets the PSM, fails a pending [listen]
 * and closes the accepted channels; a PSM published after its [listen] was cancelled is unpublished.
 */
internal class AppleChannelListener(
    private val bluetooth: CoreBluetooth,
    private val managerState: StateFlow<Long?>,
    private val emit: (RadioEvent) -> Unit,
) : ChannelListenerEvents {
    private val publishing = Mutex()
    private var pending: CompletableDeferred<Int?>? = null
    private var psm: Int? = null
    private val accepted = mutableSetOf<AppleChannel>()

    /** Publishes the channel unless it is published; its PSM, or [RadioFailure.RADIO_ERROR]. */
    suspend fun listen(): RadioResult<Int> =
        publishing.withLock {
            val published = psm ?: publish()
            psm = published
            published?.let { RadioResult.Ok(it) } ?: RadioResult.Failed(RadioFailure.RADIO_ERROR)
        }

    /** Unpublishes the channel, if it is published; accepted channels stay open. */
    fun stop() {
        psm?.let { bluetooth.unpublishChannel(it) }
        psm = null
    }

    /** The peripheral manager is off: its channel and connections are gone. */
    fun powerLost() {
        psm = null
        pending?.complete(null)
        accepted.toList().forEach { it.disconnect() }
    }

    override fun channelPublished(psm: Int?) {
        val waiting = pending
        if (waiting != null) {
            waiting.complete(psm)
        } else if (psm != null) {
            bluetooth.unpublishChannel(psm)
        }
    }

    override fun channelOpened(channel: CbChannel?) {
        if (channel != null) {
            val stream = AppleChannel(AppleCentralPeer(channel.peerId), channel) { accepted -= it }
            accepted += stream
            emit(RadioEvent.ChannelOpened(stream))
        }
    }

    /** Publishes the channel and waits for its PSM; null if it failed. */
    private suspend fun publish(): Int? {
        if (managerState.value != CbState.POWERED_ON) return null
        val answer = CompletableDeferred<Int?>()
        pending = answer
        bluetooth.publishChannel()
        return try {
            answer.await()
        } finally {
            pending = null
        }
    }
}
