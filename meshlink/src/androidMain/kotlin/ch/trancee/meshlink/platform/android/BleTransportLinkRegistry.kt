package ch.trancee.meshlink.platform.android

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.transport.ActivePeerHintResolutionRequest
import ch.trancee.meshlink.transport.TemporaryPeerHintPromotionRequest
import ch.trancee.meshlink.transport.resolveActivePeerHint
import ch.trancee.meshlink.transport.selectTemporaryPeerHintPromotion
import java.io.Closeable
import kotlinx.coroutines.Job

internal enum class TemporaryLinkPromotionOutcome {
    NONE,
    PROMOTED,
    CLOSED_DUPLICATE,
}

internal class BleTransportLinkRegistry<T>(private val bindings: PeerBindings) {
    private val activeLinksByHint: MutableMap<String, T> = linkedMapOf()
    private val pendingConnectJobsByHint: MutableMap<String, Job> = linkedMapOf()
    // Tracks the socket a pending connect job is currently blocked in Socket.connect() on (once
    // created), so cancelPendingConnects() can force-close it. A blocking connect() call has no
    // suspension point, so cancelling the Job alone cannot interrupt it once it's already running;
    // only closing the underlying socket can.
    private val pendingConnectSocketsByHint: MutableMap<String, Closeable> = linkedMapOf()
    private val pendingConnectLock = Any()

    internal fun hasActiveLink(hintPeerIdValue: String): Boolean {
        return synchronized(activeLinksByHint) { activeLinksByHint.containsKey(hintPeerIdValue) }
    }

    internal fun activeLink(hintPeerIdValue: String): T? {
        return synchronized(activeLinksByHint) { activeLinksByHint[hintPeerIdValue] }
    }

    internal fun activeHintIds(): Set<String> {
        return synchronized(activeLinksByHint) { activeLinksByHint.keys.toSet() }
    }

    internal fun activeHintIdsSnapshot(): List<String> {
        return synchronized(activeLinksByHint) { activeLinksByHint.keys.toList() }
    }

    internal fun registerActiveLink(hintPeerIdValue: String, link: T): Boolean {
        return synchronized(activeLinksByHint) {
            if (activeLinksByHint.containsKey(hintPeerIdValue)) {
                false
            } else {
                activeLinksByHint[hintPeerIdValue] = link
                true
            }
        }
    }

    internal fun removeActiveLink(hintPeerIdValue: String): T? {
        return synchronized(activeLinksByHint) { activeLinksByHint.remove(hintPeerIdValue) }
    }

    internal fun resolveActiveLink(peer: DiscoveredPeer): T? {
        val activeHint =
            resolveActivePeerHint(
                ActivePeerHintResolutionRequest(
                    hintPeerIdValue = peer.hintPeerId.value,
                    temporaryHintPeerIdValue =
                        bindings.temporaryHintForAddress(peer.deviceAddress)
                            ?: bindings.hintForAddress(peer.deviceAddress),
                    activeHintIds = activeHintIds(),
                )
            ) ?: bindings.hintForAddress(peer.deviceAddress)?.takeIf { it in activeHintIds() }
        if (activeHint != null) {
            return activeLink(activeHint)
        }
        return synchronized(activeLinksByHint) { activeLinksByHint.values.firstOrNull() }
    }

    internal fun hasPendingConnect(hintPeerIdValue: String): Boolean {
        return synchronized(pendingConnectLock) {
            pendingConnectJobsByHint.containsKey(hintPeerIdValue)
        }
    }

    internal fun clearPendingConnect(hintPeerIdValue: String): Unit {
        synchronized(pendingConnectLock) {
            pendingConnectJobsByHint.remove(hintPeerIdValue)
            pendingConnectSocketsByHint.remove(hintPeerIdValue)
        }
    }

    internal fun reservePendingConnect(hintPeerIdValue: String, connectJob: Job): Boolean {
        val hasActiveLink = hasActiveLink(hintPeerIdValue)
        return synchronized(pendingConnectLock) {
            if (hasActiveLink || pendingConnectJobsByHint.containsKey(hintPeerIdValue)) {
                false
            } else {
                pendingConnectJobsByHint[hintPeerIdValue] = connectJob
                true
            }
        }
    }

    /**
     * Records the socket a pending connect job is about to block on inside `Socket.connect()`, so
     * [cancelPendingConnects] can force-close it later if needed. Only stored while the connect is
     * still tracked as pending, so a socket created after the job was already cleared/cancelled
     * (e.g. by a concurrent teardown) isn't registered and leaked into this map.
     */
    internal fun registerPendingConnectSocket(hintPeerIdValue: String, socket: Closeable): Unit {
        synchronized(pendingConnectLock) {
            if (pendingConnectJobsByHint.containsKey(hintPeerIdValue)) {
                pendingConnectSocketsByHint[hintPeerIdValue] = socket
            }
        }
    }

    internal fun cancelPendingConnects(): Unit {
        val sockets =
            synchronized(pendingConnectLock) {
                pendingConnectJobsByHint.values.forEach(Job::cancel)
                pendingConnectJobsByHint.clear()
                val sockets = pendingConnectSocketsByHint.values.toList()
                pendingConnectSocketsByHint.clear()
                sockets
            }
        // Closed outside the lock: Closeable.close() can run arbitrary I/O and must not be called
        // while holding pendingConnectLock.
        sockets.forEach { socket -> runCatching { socket.close() } }
    }

    internal fun promoteTemporaryLink(
        address: String,
        resolvedHintPeerIdValue: String,
        temporaryPeerPrefix: String,
        updateHint: (T, PeerId) -> Unit,
        closeLink: (T) -> Unit,
    ): TemporaryLinkPromotionOutcome {
        val mappedTemporaryHint =
            bindings.temporaryHintForAddress(address) ?: return TemporaryLinkPromotionOutcome.NONE
        val temporaryHint =
            selectTemporaryPeerHintPromotion(
                TemporaryPeerHintPromotionRequest(
                    temporaryHintPeerIdValue = mappedTemporaryHint,
                    resolvedHintPeerIdValue = resolvedHintPeerIdValue,
                    activeHintIds = activeHintIds(),
                    temporaryPeerPrefix = temporaryPeerPrefix,
                )
            )
        val resolvedHintPeerId = PeerId(resolvedHintPeerIdValue)
        var duplicateLink: T? = null
        val outcome =
            synchronized(activeLinksByHint) {
                when {
                    temporaryHint != null -> {
                        val link = activeLinksByHint.remove(temporaryHint)
                        if (link == null) {
                            TemporaryLinkPromotionOutcome.NONE
                        } else {
                            updateHint(link, resolvedHintPeerId)
                            activeLinksByHint[resolvedHintPeerIdValue] = link
                            TemporaryLinkPromotionOutcome.PROMOTED
                        }
                    }

                    activeLinksByHint.containsKey(mappedTemporaryHint) &&
                        activeLinksByHint.containsKey(resolvedHintPeerIdValue) -> {
                        duplicateLink = activeLinksByHint.remove(mappedTemporaryHint)
                        TemporaryLinkPromotionOutcome.CLOSED_DUPLICATE
                    }

                    else -> TemporaryLinkPromotionOutcome.NONE
                }
            }
        duplicateLink?.let(closeLink)
        bindings.removeTemporaryHint(address)
        bindings.bindHintToAddress(address, resolvedHintPeerIdValue)
        return outcome
    }

    internal fun rebindActiveLink(
        fromHintPeerIdValue: String,
        toHintPeerIdValue: String,
        updateHint: (T, PeerId) -> Unit,
        closeLink: (T) -> Unit,
    ): T? {
        val targetHintPeerId = PeerId(toHintPeerIdValue)
        var duplicateLink: T? = null
        val promotedLink =
            synchronized(activeLinksByHint) {
                val link = activeLinksByHint.remove(fromHintPeerIdValue) ?: return@synchronized null
                if (activeLinksByHint.containsKey(toHintPeerIdValue)) {
                    duplicateLink = link
                    null
                } else {
                    updateHint(link, targetHintPeerId)
                    activeLinksByHint[toHintPeerIdValue] = link
                    link
                }
            }
        duplicateLink?.let(closeLink)
        return promotedLink
    }
}
