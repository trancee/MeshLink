package ch.trancee.meshlink.platform.ios

import ch.trancee.meshlink.transport.ActivePeerHintResolutionRequest
import ch.trancee.meshlink.transport.resolveActivePeerHint

internal class BleTransportGattNotifyRegistry {
    private val linksByHint: MutableMap<String, GattNotifyLink> = linkedMapOf()

    internal val size: Int
        get() = linksByHint.size

    /**
     * Mirrors Android's `GattSideLinkCoordinator.activeHintIds()`: the set of peer hints that
     * currently occupy a GATT notify side-link connection slot. Used alongside
     * [BleTransportAdapter.activeLinksByHint]'s keys to compute the active connection count for
     * [ch.trancee.meshlink.power.hasConnectionBudget]'s admission-control check.
     */
    internal fun activeHintIds(): Set<String> {
        return linksByHint.keys
    }

    internal fun hasLink(hintPeerIdValue: String): Boolean {
        return linksByHint.containsKey(hintPeerIdValue)
    }

    internal fun currentLink(hintPeerIdValue: String): GattNotifyLink? {
        return linksByHint[hintPeerIdValue]
    }

    internal fun replaceLink(hintPeerIdValue: String, link: GattNotifyLink): GattNotifyLink? {
        return linksByHint.put(hintPeerIdValue, link)
    }

    internal fun removeLink(hintPeerIdValue: String): GattNotifyLink? {
        return linksByHint.remove(hintPeerIdValue)
    }

    internal fun removeLinkForCentralIdentifier(identifier: String): GattNotifyLink? {
        val entry =
            linksByHint.entries.firstOrNull { (_, link) -> link.centralIdentifier == identifier }
                ?: return null
        val key = entry.key
        val link = entry.value
        linksByHint.remove(key)
        return link
    }

    internal fun stopAll(): Unit {
        linksByHint.values.forEach(GattNotifyLink::close)
        linksByHint.clear()
    }

    internal fun pumpAll(): Unit {
        linksByHint.values.forEach(GattNotifyLink::pump)
    }

    internal fun resolveActiveLink(
        peer: DiscoveredPeer,
        temporaryHintPeerIdValue: String?,
        supportsGattNotifyBearer: Boolean,
    ): GattNotifyLink? {
        val activeHint =
            resolveActivePeerHint(
                ActivePeerHintResolutionRequest(
                    hintPeerIdValue = peer.hintPeerId.value,
                    temporaryHintPeerIdValue = temporaryHintPeerIdValue,
                    activeHintIds = linksByHint.keys,
                )
            )
        return if (supportsGattNotifyBearer && activeHint != null) {
            linksByHint[activeHint]
        } else {
            null
        }
    }
}
