package ch.trancee.meshlink.routing

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.wire.WireFrame
import ch.trancee.meshlink.wire.WriteBuffer

internal class RouteDigestTracker {
    private var cachedRouteDigest: ByteArray? = null

    internal fun routeDigestFrame(
        localPeerId: PeerId,
        routes: Collection<RouteEntry>,
    ): WireFrame.RouteDigest {
        return WireFrame.RouteDigest(peerId = localPeerId, digest = routeDigest(routes))
    }

    internal fun invalidate(): Unit {
        cachedRouteDigest = null
    }

    private fun routeDigest(routes: Collection<RouteEntry>): ByteArray {
        cachedRouteDigest?.let {
            return it
        }

        val buffer = WriteBuffer()
        routes
            .sortedWith(
                compareBy<RouteEntry> { route -> route.destinationPeerId.value }
                    .thenBy { route -> route.nextHopPeerId.value }
            )
            .forEach { route ->
                buffer.writeIntLittleEndian(route.destinationPeerIdBytes.size)
                buffer.writeBytes(route.destinationPeerIdBytes)
                buffer.writeIntLittleEndian(route.nextHopPeerIdBytes.size)
                buffer.writeBytes(route.nextHopPeerIdBytes)
                buffer.writeIntLittleEndian(route.metric)
                buffer.writeIntLittleEndian(route.seqNo.toInt())
                buffer.writeIntLittleEndian(route.ed25519PublicKey.size)
                buffer.writeBytes(route.ed25519PublicKey)
                buffer.writeIntLittleEndian(route.x25519PublicKey.size)
                buffer.writeBytes(route.x25519PublicKey)
            }
        return fnv1a32(buffer.toByteArray()).also { digest -> cachedRouteDigest = digest }
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
