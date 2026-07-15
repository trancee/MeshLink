package ch.trancee.meshlink.routing

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.wire.WireFrame
import ch.trancee.meshlink.wire.WriteBuffer

internal class RouteDigestTracker {
    private val routeBytesByDestinationPeerId: MutableMap<String, ByteArray> = linkedMapOf()
    private val orderedDestinationPeerIds: MutableList<String> = mutableListOf()
    private var cachedRouteDigest: ByteArray? = null

    internal fun routeDigestFrame(localPeerId: PeerId): WireFrame.RouteDigest {
        return WireFrame.RouteDigest(peerId = localPeerId, digest = routeDigest())
    }

    internal fun upsert(route: RouteEntry): Unit {
        val destinationPeerIdValue = route.destinationPeerId.value
        val isNewDestination =
            routeBytesByDestinationPeerId.put(destinationPeerIdValue, encode(route)) == null
        if (isNewDestination) {
            insertDestinationPeerId(destinationPeerIdValue)
        }
        cachedRouteDigest = null
    }

    internal fun remove(destinationPeerIdValue: String): Unit {
        if (routeBytesByDestinationPeerId.remove(destinationPeerIdValue) != null) {
            orderedDestinationPeerIds.remove(destinationPeerIdValue)
            cachedRouteDigest = null
        }
    }

    internal fun clear(): Unit {
        if (routeBytesByDestinationPeerId.isNotEmpty()) {
            routeBytesByDestinationPeerId.clear()
            orderedDestinationPeerIds.clear()
            cachedRouteDigest = null
        }
    }

    private fun routeDigest(): ByteArray {
        cachedRouteDigest?.let {
            return it
        }

        val buffer = WriteBuffer()
        orderedDestinationPeerIds.forEach { destinationPeerIdValue ->
            buffer.writeBytes(routeBytesByDestinationPeerId.getValue(destinationPeerIdValue))
        }
        return fnv1a32(buffer.toByteArray()).also { digest -> cachedRouteDigest = digest }
    }

    private fun encode(route: RouteEntry): ByteArray {
        val buffer = WriteBuffer()
        // Route digests are compared across different observers, so they must hash only
        // observer-invariant route identity/freshness fields. nextHop/metric are intentionally
        // excluded here because they are observer-relative in any distance-vector topology.
        buffer.writeIntLittleEndian(route.destinationPeerIdBytes.size)
        buffer.writeBytes(route.destinationPeerIdBytes)
        buffer.writeIntLittleEndian(route.seqNo.toInt())
        buffer.writeIntLittleEndian(route.ed25519PublicKey.size)
        buffer.writeBytes(route.ed25519PublicKey)
        buffer.writeIntLittleEndian(route.x25519PublicKey.size)
        buffer.writeBytes(route.x25519PublicKey)
        return buffer.toByteArray()
    }

    private fun insertDestinationPeerId(destinationPeerIdValue: String): Unit {
        val insertionIndex =
            orderedDestinationPeerIds.binarySearch(destinationPeerIdValue).let { index ->
                if (index >= 0) index else -index - 1
            }
        orderedDestinationPeerIds.add(insertionIndex, destinationPeerIdValue)
    }

    private fun fnv1a32(bytes: ByteArray): ByteArray {
        var hash = FNV_OFFSET_BASIS
        bytes.forEach { byte ->
            hash = hash xor byte.toUByte().toUInt()
            hash *= FNV_PRIME
        }
        return ByteArray(DIGEST_SIZE_BYTES) { byteIndex ->
            ((hash shr (BITS_PER_BYTE * byteIndex)) and DIGEST_BYTE_MASK).toByte()
        }
    }

    private companion object {
        private const val BITS_PER_BYTE: Int = 8
        private const val DIGEST_BYTE_MASK: UInt = 0xFFu
        private const val DIGEST_SIZE_BYTES: Int = 4
        private const val FNV_OFFSET_BASIS: UInt = 0x811C9DC5u
        private const val FNV_PRIME: UInt = 0x01000193u
    }
}
