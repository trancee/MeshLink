package ch.trancee.meshlink.internal

import ch.trancee.meshlink.ReceivedMessage
import ch.trancee.meshlink.crypto.sodium.SodiumCryptoProvider
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

// Flood forwarding of broadcasts and unknown flood types, on lanes to three linked peers.
class FloodsTest {
    private val a = nodeId(1)
    private val b = nodeId(2)
    private val c = nodeId(3)
    private val lanes = Lanes().apply { listOf(a, b, c).forEach { open(it) } }
    private val delivered = mutableListOf<ReceivedMessage>()

    private fun floods(secured: Boolean = false) = Floods(lanes, broadcastCodec(secured)) { delivered += it }

    private suspend fun waiting() = listOf(a, b, c).associateWith { peer -> lanes.drain(peer).map { it.render() } }

    @Test
    fun aNewBroadcastIsDeliveredAndFloodedToEveryOtherLinkWithOneHopLess() =
        runTest {
            // Arrange
            val floods = floods()
            val frame = broadcast(hopLimit = 5)

            // Act
            floods.received(a, frame, NOW)

            // Assert
            val relayed = frame.copyOf().also { it[1] = 4 }
            val waiting = waiting()
            assertContentEquals(SENDER, delivered.single().sender.toByteArray())
            assertEquals("hello", delivered.single().text())
            assertEquals(emptyList(), waiting.getValue(a))
            listOf(b, c).forEach { assertContentEquals(relayed, waiting.getValue(it).single()) }
        }

    @Test
    fun aMessageIdSeenBeforeIsDroppedThroughItsLastTimelySecond() =
        runTest {
            // Arrange
            val floods = floods()
            floods.received(a, broadcast(), NOW)
            waiting()

            // Act
            floods.received(b, broadcast(), NOW + LIFETIME + SKEW)

            // Assert
            assertEquals(1, delivered.size)
            assertEquals(listOf(a, b, c).associateWith { emptyList<ByteArray>() }, waiting())
        }

    @Test
    fun aBroadcastWhoseNextHopLimitWouldBeZeroIsDeliveredButNotForwarded() =
        runTest {
            // Arrange
            val floods = floods()

            // Act
            floods.received(a, broadcast(hopLimit = 1), NOW)

            // Assert
            assertEquals(1, delivered.size)
            assertEquals(listOf(a, b, c).associateWith { emptyList<ByteArray>() }, waiting())
        }

    @Test
    fun aFrameThisNodeMustNotProcessIsDroppedWithoutStateChange() =
        runTest {
            // Arrange
            val floods = floods()
            val valid = broadcast()
            val dropped =
                listOf(
                    valid.copyOf().also { it[1] = 0 },
                    valid.copyOf(valid.size - 1),
                    valid.copyOf(FloodHeader.LENGTH - 1),
                    broadcast(container = Container.Unsecured(SENDER, ByteArray(PAYLOAD_MAX + 1))),
                    broadcast(container = Container.SignedBroadcast(ByteArray(32), byteArrayOf(1), ByteArray(64))),
                    signed(),
                    header() + byteArrayOf(Container.IK_INITIATOR.toByte()) + ByteArray(48),
                )

            // Act
            dropped.forEach { floods.received(a, it, NOW) }
            floods.received(a, broadcast(expiry = NOW - SKEW - 1), NOW)
            floods.received(a, broadcast(expiry = NOW + LIFETIME_MAX + SKEW + 1), NOW)
            val nothing = waiting()
            floods.received(a, valid, NOW)

            // Assert
            assertEquals(listOf(a, b, c).associateWith { emptyList<ByteArray>() }, nothing)
            assertEquals(1, delivered.size)
            assertEquals(listOf(0, 1, 1), waiting().values.map { it.size })
        }

    @Test
    fun theTimelinessBoundsIncludeTheirEdges() =
        runTest {
            // Arrange
            val floods = floods()

            // Act
            floods.received(a, broadcast(id = 1, expiry = NOW - SKEW), NOW)
            floods.received(a, broadcast(id = 2, expiry = NOW + LIFETIME_MAX + SKEW), NOW)

            // Assert
            assertEquals(2, delivered.size)
        }

    @Test
    fun aSecuredNodeNeitherDeliversNorRelaysAnUnsecuredBroadcast() =
        runTest {
            // Arrange
            val floods = floods(secured = true)

            // Act
            floods.received(a, broadcast(), NOW)

            // Assert
            assertEquals(emptyList(), delivered)
            assertEquals(listOf(a, b, c).associateWith { emptyList<ByteArray>() }, waiting())
        }

    @Test
    fun aSecuredNodeDeliversAndRelaysASignedBroadcastFromItsOriginsNodeId() =
        runTest {
            // Arrange
            val floods = floods(secured = true)
            val frame = signed()

            // Act
            floods.received(a, frame, NOW)

            // Assert
            val origin = Derivations(SodiumCryptoProvider).nodeId(identityKey(2).publicKey)
            assertContentEquals(origin, delivered.single().sender.toByteArray())
            assertEquals("hello", delivered.single().text())
            assertEquals(listOf(0, 1, 1), waiting().values.map { it.size })
        }

    @Test
    fun aForgedSignedBroadcastIsDroppedAndDoesNotShadowTheGenuineOne() =
        runTest {
            // Arrange
            val floods = floods(secured = true)
            val genuine = signed()
            val forged = genuine.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }

            // Act
            floods.received(a, forged, NOW)
            val nothing = waiting()
            floods.received(a, genuine, NOW)

            // Assert
            assertEquals(listOf(a, b, c).associateWith { emptyList<ByteArray>() }, nothing)
            assertEquals(1, delivered.size)
            assertEquals(listOf(0, 1, 1), waiting().values.map { it.size })
        }

    @Test
    fun anUnknownFloodTypeIsRelayedOnceWithOneHopLessAndNotDelivered() =
        runTest {
            // Arrange
            val floods = floods()
            val frame = header(type = UNKNOWN_TYPE, hopLimit = 5) + byteArrayOf(9, 9)

            // Act
            floods.received(a, frame, NOW)
            val waiting = waiting()
            floods.received(b, frame, NOW)

            // Assert
            val relayed = frame.copyOf().also { it[1] = 4 }
            assertEquals(emptyList(), delivered)
            listOf(b, c).forEach { assertContentEquals(relayed, waiting.getValue(it).single()) }
            assertEquals(listOf(a, b, c).associateWith { emptyList<ByteArray>() }, waiting())
        }

    @Test
    fun anUnknownFloodTypeAboveTheSmallFrameMaximumIsDroppedWithoutStateChange() =
        runTest {
            // Arrange
            val floods = floods()
            val small = header(type = UNKNOWN_TYPE)
            val large = small + ByteArray(Reassembly.FRAME_SMALL_MAX - small.size + 1)

            // Act
            floods.received(a, large, NOW)
            val nothing = waiting()
            floods.received(a, small + ByteArray(Reassembly.FRAME_SMALL_MAX - small.size), NOW)

            // Assert
            assertEquals(listOf(a, b, c).associateWith { emptyList<ByteArray>() }, nothing)
            assertEquals(listOf(0, 1, 1), waiting().values.map { it.size })
        }

    @Test
    fun anOriginsOwnBroadcastComingBackIsDropped() =
        runTest {
            // Arrange
            val floods = floods()
            floods.originate(Parcel(messageId(1), relayed = false, { ByteArray(0) }) {}, NOW + LIFETIME)
            waiting()

            // Act
            floods.received(a, broadcast(), NOW + LIFETIME + SKEW)

            // Assert
            assertEquals(emptyList(), delivered)
            assertEquals(listOf(a, b, c).associateWith { emptyList<ByteArray>() }, waiting())
        }

    @Test
    fun anOriginatedBroadcastGoesToTheLinksUpThenAndNotToALaterLink() =
        runTest {
            // Arrange
            val floods = floods()
            val d = nodeId(4)

            // Act
            floods.originate(Parcel(messageId(1), relayed = false, { ByteArray(0) }) {}, NOW + LIFETIME)
            lanes.open(d)
            floods.linked(d)

            // Assert
            assertEquals(listOf(1, 1, 1), waiting().values.map { it.size })
            assertEquals(emptyList(), lanes.drain(d))
        }

    private fun header(
        type: Int = BroadcastFrame.TYPE,
        hopLimit: Int = 5,
        id: Int = 1,
        expiry: Long = NOW + LIFETIME,
    ): ByteArray {
        val bytes = FloodHeader(maxOf(hopLimit, 1), messageId(id), expiry.toUInt()).encode(type)
        return bytes.also { it[1] = hopLimit.toByte() }
    }

    private fun broadcast(
        hopLimit: Int = 5,
        id: Int = 1,
        expiry: Long = NOW + LIFETIME,
        container: Container = Container.Unsecured(SENDER, "hello".encodeToByteArray()),
    ): ByteArray = header(BroadcastFrame.TYPE, hopLimit, id, expiry) + container.encode()

    private fun signed(): ByteArray {
        val header = FloodHeader(5, messageId(1), (NOW + LIFETIME).toUInt())
        return BroadcastFrame.sign(SodiumCryptoProvider, header, identityKey(2), "hello".encodeToByteArray()).encode()
    }

    private fun ReceivedMessage.text() = payload.decodeToString()

    private companion object {
        const val NOW = 1_767_225_600L
        const val LIFETIME = 300L
        const val SKEW = 30L
        const val LIFETIME_MAX = 3_600L
        const val PAYLOAD_MAX = 1_024
        const val UNKNOWN_TYPE = 0xbf
        val SENDER = ByteArray(32) { 7 }
    }
}
