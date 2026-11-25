package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DeliveryStatus
import ch.trancee.meshlink.InternalMeshLinkApi
import ch.trancee.meshlink.SecurityProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Messages sealed to a peer's earlier certificate after it rotated its static keys (spec "Static-key rotation";
 * ADR 0014 decision 9), and to a peer whose cached certificate expired (ADR 0018 decision 8), on seeded air.
 */
@OptIn(ExperimentalCoroutinesApi::class, InternalMeshLinkApi::class)
class StaticKeyRotationAirTest {
    @Test
    fun aClassicalFirstMessageSealedToTheEarlierCertificateOpensAfterTheDestinationRotated() =
        runTest(timeout = DAY_TIMEOUT) {
            opensDirect(SecurityProfile.Classical)
        }

    @Test
    fun aPostQuantumFirstMessageSealedToTheEarlierCertificateOpensAfterTheDestinationRotated() =
        runTest(timeout = DAY_TIMEOUT) {
            opensDirect(SecurityProfile.PostQuantum)
        }

    @Test
    fun aClassicalHeldMessageTaggedForTheEarlierCertificateIsClaimedAndOpenedAfterTheDestinationRotated() =
        runTest(timeout = DAY_TIMEOUT) {
            opensHeld(SecurityProfile.Classical)
        }

    @Test
    fun aPostQuantumHeldMessageTaggedForTheEarlierCertificateIsClaimedAndOpenedAfterTheDestinationRotated() =
        runTest(timeout = DAY_TIMEOUT) {
            opensHeld(SecurityProfile.PostQuantum)
        }

    @Test
    fun aMessageToAPeerWhoseCachedCertificateExpiredWaitsQueuedForALookupThenIsSealedToTheFreshOneAndDelivered() =
        runTest(timeout = DAY_TIMEOUT) {
            // Arrange: D leaves until S's copy of its first certificate expired, then returns next to R only.
            val mesh = ChatMesh(this, SecurityProfile.Classical, count = 3).start()
            val d = mesh.id(D_HELD)
            val earlier = assertNotNull(mesh.nodes[S].certificateLookups?.certificateOf(d))
            mesh.leave(D_HELD)
            advanceTimeBy(EXPIRED)
            mesh.place(D_HELD, 2 * ChatMesh.SPACING)
            advanceTimeBy(ChatMesh.SETTLE_MILLIS)
            val held = mesh.nodes[S].certificateLookups?.certificateOf(d)

            // Act
            val message = mesh.send(S, d, "hello", settleMillis = 0)
            val queued = message.status.value
            advanceTimeBy(ChatMesh.SETTLE_MILLIS)

            // Assert
            assertNull(held)
            assertEquals(DeliveryStatus.Queued, queued)
            val fresh = assertNotNull(mesh.nodes[S].certificateLookups?.certificateOf(d))
            assertTrue(fresh.notBefore > earlier.notAfter - RENEWAL)
            assertEquals(listOf("hello"), mesh.texts(D_HELD))
            assertEquals(DeliveryStatus.Delivered, message.status.value)
        }

    @Test
    fun aMessageToAPeerWhoseCachedCertificateExpiredAndNoLookupAnswersIsNeverSealedAndExpires() =
        runTest(timeout = DAY_TIMEOUT) {
            // Arrange: D leaves for good; S's copy of its certificate expires.
            val mesh = ChatMesh(this, SecurityProfile.Classical, count = 3).start()
            val d = mesh.id(D_HELD)
            mesh.leave(D_HELD)
            advanceTimeBy(EXPIRED)
            val seen = mutableListOf<DeliveryStatus>()

            // Act
            val message = mesh.send(S, d, "hello", settleMillis = 0)
            backgroundScope.launch { message.status.toList(seen) }
            advanceTimeBy(LIFETIME)

            // Assert
            assertEquals(listOf(DeliveryStatus.Queued, DeliveryStatus.Expired), seen)
            assertNull(mesh.nodes[S].certificateLookups?.certificateOf(d))
        }

    // S and D link at the start, so S holds D's first certificate; D rotates 12 h later on the same link.
    private suspend fun TestScope.opensDirect(profile: SecurityProfile) {
        // Arrange
        val mesh = ChatMesh(this, profile, count = 2).start()
        val d = mesh.id(D)
        val earlier = assertNotNull(mesh.nodes[S].certificateLookups?.certificateOf(d))
        advanceTimeBy(ROTATED)
        val sealedTo = mesh.nodes[S].certificateLookups?.certificateOf(d)

        // Act
        val message = mesh.send(S, d, "hello")

        // Assert
        assertEquals(earlier.notBefore, sealedTo?.notBefore)
        assertEquals(listOf("hello"), mesh.texts(D))
        assertEquals(DeliveryStatus.Delivered, message.status.value)
    }

    // D leaves after the start and rotates away; S's message to it, sealed to D's first certificate, waits at R
    // until D returns next to R.
    private suspend fun TestScope.opensHeld(profile: SecurityProfile) {
        // Arrange
        val mesh = ChatMesh(this, profile, count = 3).start()
        val d = mesh.id(D_HELD)
        val earlier = assertNotNull(mesh.nodes[S].certificateLookups?.certificateOf(d))
        mesh.leave(D_HELD)
        advanceTimeBy(ROTATED)
        val sealedTo = mesh.nodes[S].certificateLookups?.certificateOf(d)
        val message = mesh.send(S, d, "hello", settleMillis = RETRIES_MILLIS)

        // Act
        mesh.place(D_HELD, 2 * ChatMesh.SPACING)
        advanceTimeBy(ChatMesh.SETTLE_MILLIS)

        // Assert
        assertEquals(earlier.notBefore, sealedTo?.notBefore)
        val current = assertNotNull(mesh.nodes[R].certificateLookups?.certificateOf(d))
        assertEquals(earlier.notBefore + RENEWAL, current.notBefore)
        assertEquals(listOf("hello"), mesh.texts(D_HELD))
        assertEquals(DeliveryStatus.Delivered, message.status.value)
    }

    private companion object {
        const val S = 0
        const val D = 1
        const val R = 1
        const val D_HELD = 2
        const val RENEWAL = 43_200u
        const val RETRIES_MILLIS = 31_000L
        val ROTATED = 13.hours
        val EXPIRED = 25.hours
        val LIFETIME = 31.minutes
        val DAY_TIMEOUT = 5.minutes
    }
}
