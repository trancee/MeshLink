package ch.trancee.meshlink.internal

import ch.trancee.meshlink.InternalMeshLinkApi
import ch.trancee.meshlink.MeshLink
import ch.trancee.meshlink.MeshPlatform
import ch.trancee.meshlink.Preset
import ch.trancee.meshlink.SecurityProfile
import ch.trancee.meshlink.meshLinkConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Peers sighted without a payload, as backgrounded iPhones advertise (ADR 0010 decision 5): the
 * node dials them to learn their advertisement from the capability record, and marks the address
 * stale when that fails (ticket 32, Q4).
 */
@OptIn(ExperimentalCoroutinesApi::class, InternalMeshLinkApi::class)
class BackgroundDiscoveryTest {
    @Test
    fun backgroundPeerOfTheMeshLinksThroughTheAdvertisementInItsCapabilityRecord() =
        runTest {
            // Arrange
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler))
            val bob = air.platform(StandardTestDispatcher(testScheduler))
            bob.radio.background = true
            val nodes = listOf(node(alice), node(bob).apply { restrictedLinkRole = LinkRole.PERIPHERAL })

            // Act
            nodes.forEach { it.start() }
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertEquals(setOf<PeerHandle>(bob.radio.handle), nodes[0].linkCandidates())
            assertEquals(1, nodes[0].peers.value.size)
            assertEquals(1, nodes[1].peers.value.size)
        }

    @Test
    fun linkedBackgroundPeerIsNotDialedAgainWhenLaterWindowsSightIt() =
        runTest {
            // Arrange
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler))
            val bob = air.platform(StandardTestDispatcher(testScheduler))
            bob.radio.background = true
            val dials = Dials(alice.radio, bob.radio)
            val nodes =
                listOf(
                    node(platformWith(alice, dials)),
                    node(bob).apply { restrictedLinkRole = LinkRole.PERIPHERAL },
                )

            // Act
            nodes.forEach { it.start() }
            advanceTimeBy(WINDOWS_MILLIS)

            // Assert
            assertEquals(1, dials.count)
            assertEquals(1, nodes[0].peers.value.size)
        }

    @Test
    fun backgroundAddressWhoseConnectFailsIsStale() =
        runTest {
            val dials = staleDials { air, _ -> air.radio().also { it.background = true } }

            assertEquals(1, dials)
        }

    @Test
    fun backgroundAddressThatServesNoCapabilityIsStale() =
        runTest {
            val dials = staleDials(connects = true) { air, _ -> air.radio().also { it.background = true } }

            assertEquals(1, dials)
        }

    @Test
    fun backgroundAddressWhoseRecordHasNoAdvertisementIsStale() =
        runTest {
            val dials =
                staleDials(connects = true) { air, _ ->
                    air.radio().also {
                        it.background = true
                        it.serveCapability(CapabilityRecord(listOf(LinkPolicy.PROTOCOL_VERSION), emptyList()).encode())
                    }
                }

            assertEquals(1, dials)
        }

    @Test
    fun backgroundPeerOfAnotherMeshIsStale() =
        runTest {
            val dials =
                staleDials(connects = true) { air, scope ->
                    val elsewhere = air.platform(StandardTestDispatcher(scope.testScheduler))
                    elsewhere.radio.background = true
                    node(elsewhere, "Elsewhere").apply { restrictedLinkRole = LinkRole.PERIPHERAL }.start()
                    elsewhere.radio
                }

            assertEquals(1, dials)
        }

    @Test
    fun staleAddressIsForgottenAfterTenMinutesWithoutASighting() =
        runTest {
            // Arrange
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler))
            val target = air.radio().also { it.background = true }
            val dials = Dials(alice.radio, target)
            node(platformWith(alice, dials)).start()
            target.startAdvertising(ByteArray(RadioPort.ADVERTISEMENT_LENGTH))
            advanceTimeBy(WINDOWS_MILLIS)

            // Act
            suspend fun gone(minutes: Long): Int {
                target.stopAdvertising()
                advanceTimeBy(minutes * MINUTE_MILLIS)
                target.startAdvertising(ByteArray(RadioPort.ADVERTISEMENT_LENGTH))
                advanceTimeBy(WINDOWS_MILLIS)
                return dials.count
            }
            val afterNine = gone(9)
            val afterEleven = gone(11)

            // Assert
            assertEquals(1, afterNine)
            assertEquals(2, afterEleven)
        }

    /**
     * Starts a node beside the background radio [target] makes, lets it scan for [WINDOWS_MILLIS],
     * and returns how often it dialed the target. Unless [connects], every connect to it fails.
     */
    private suspend fun TestScope.staleDials(
        connects: Boolean = false,
        target: suspend (VirtualAir, TestScope) -> VirtualRadio,
    ): Int {
        val air = air()
        val alice = air.platform(StandardTestDispatcher(testScheduler))
        val radio = target(air, this)
        val dials = Dials(alice.radio, radio, connects)
        val node = node(platformWith(alice, dials))
        node.start()
        if (radio.advertisement == null) radio.startAdvertising(ByteArray(RadioPort.ADVERTISEMENT_LENGTH))
        advanceTimeBy(WINDOWS_MILLIS)
        assertEquals(emptySet(), node.linkCandidates())
        assertEquals(emptyList(), node.peers.value)
        return dials.count
    }

    /** [radio], counting its connects to [target]'s address, which fail unless [connects]. */
    private class Dials(
        private val radio: VirtualRadio,
        private val target: VirtualRadio,
        private val connects: Boolean = true,
    ) : RadioPort by radio {
        var count = 0

        override suspend fun connect(peer: PeerHandle): RadioResult<CentralLink> {
            if (peer === target.handle) count++
            return if (peer !== target.handle || connects) {
                radio.connect(peer)
            } else {
                RadioResult.Failed(RadioFailure.PEER_UNREACHABLE)
            }
        }
    }

    private fun node(
        platform: PlatformPort,
        meshId: String,
    ): MeshLink =
        MeshLink.create(
            meshLinkConfig(Preset.OfflineChat, SecurityProfile.Unsecured) { this.meshId = meshId }.build(),
            MeshPlatform.of(platform),
        )

    private companion object {
        /** Seven and a half scan windows. */
        const val WINDOWS_MILLIS = 60_000L
        const val MINUTE_MILLIS = 60_000L
    }
}
