package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DeliveryStatus
import ch.trancee.meshlink.SecurityProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Store-and-forward (spec "Holding", ADR 0014) on seeded air: a relay holds a frame for a destination out of
 * reach and hands it over when the destination returns, through the `CATCH_UP` held list and `GRAFT`, or when a
 * route to it passes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StoreAndForwardTest {
    @Test
    fun anUnsecuredRelayHandsAHeldMessageToItsReturningDestinationThroughAGraft() =
        runTest(timeout = 5.minutes) {
            handsOver(SecurityProfile.Unsecured)
        }

    @Test
    fun aClassicalRelayHandsAHeldMessageToItsReturningDestinationThroughAGraft() =
        runTest(timeout = 5.minutes) {
            handsOver(SecurityProfile.Classical)
        }

    @Test
    fun theDestinationGetsTheRelaysCopyWithItsSenderGoneAndTheSenderBackClaimsTheHeldReceipt() =
        runTest(timeout = 5.minutes) {
            // Arrange
            val mesh = ChatMesh(this, SecurityProfile.Unsecured, count = 3).start()
            val e = mesh.id(E)
            mesh.leave(E)
            val message = mesh.send(A, e, "hello")
            mesh.leave(A)

            // Act
            mesh.place(E, 2 * SPACING)
            advanceTimeBy(SETTLE_MILLIS)
            val statusWhileAway = message.status.value
            mesh.place(A, 0.0)
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertEquals(listOf("hello"), mesh.texts(E))
            assertTrue(mesh.grafts(E) > 0, "E sent no GRAFT")
            assertTrue(statusWhileAway !is DeliveryStatus.Delivered, "A was away: $statusWhileAway")
            assertEquals(DeliveryStatus.Delivered, message.status.value)
        }

    @Test
    fun aClassicalRelayHoldsAFirstMessageAndASessionFrameThatTheirDestinationClaimsAfterAnEpochChange() =
        runTest(timeout = 5.minutes) {
            // Arrange: A has a conversation with E, B none.
            val mesh = ChatMesh(this, SecurityProfile.Classical, count = 4).start()
            val e = mesh.id(E)
            mesh.place(B, 0.0)
            mesh.place(E, 2 * SPACING)
            advanceTimeBy(SETTLE_MILLIS)
            mesh.send(A, e, "opening")
            assertEquals(listOf("opening"), mesh.texts(E))
            mesh.leave(E)
            mesh.send(A, e, "in session")
            mesh.send(B, e, "first")
            mesh.leave(A)
            mesh.leave(B)

            // Act
            advanceTimeBy(EPOCH_MILLIS)
            mesh.place(E, 2 * SPACING)
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertEquals(listOf("first", "in session", "opening"), mesh.texts(E).sorted())
            assertTrue(mesh.grafts(E) >= 2, "E grafted ${mesh.grafts(E)} frames")
        }

    @Test
    fun aRelayHandsAHeldMessageOnAtOnceWhenItsDestinationsRouteRequestPassesInTheSameEpoch() =
        runTest(timeout = 5.minutes) {
            // Arrange: E, of the medium class while away, comes back next to Q, which never held the message and is
            // next to R; all within the first epoch, as a relay can't tell E's tags of two epochs apart.
            val mesh = ChatMesh(this, SecurityProfile.Unsecured, count = 4).start()
            val a = mesh.id(A)
            val e = mesh.id(E)
            mesh.leave(E)
            mesh.leave(Q)
            val crowd = List(MEDIUM_CROWD) { advertiseFake(mesh.air, capability = null) }
            crowd.forEach { mesh.air.move(it, AWAY + E * AWAY, CROWD_OFFSET) }
            mesh.send(A, e, "hello")
            mesh.leave(A)
            mesh.place(Q, 2 * SPACING)
            advanceTimeBy(SETTLE_MILLIS)
            crowd.forEach { mesh.air.move(it, 3 * SPACING, CROWD_OFFSET) }
            mesh.place(E, 3 * SPACING)
            advanceTimeBy(LINK_MILLIS)
            assertEquals(emptyList(), mesh.texts(E))
            assertEquals(MEDIUM_OFF, lastGap(mesh.platforms[E]), "E's scan off time")

            // Act
            mesh.send(E, a, "anyone there?", settleMillis = LINK_MILLIS)

            // Assert
            assertTrue(testScheduler.currentTime < EPOCH_SECONDS_MILLIS, "past the first epoch")
            assertEquals(listOf("hello"), mesh.texts(E))
            assertEquals(0, mesh.grafts(E), "E grafted")
        }

    @Test
    fun aRelayThatStopsAndStartsAgainHasNothingToHandOver() =
        runTest(timeout = 5.minutes) {
            // Arrange
            val mesh = ChatMesh(this, SecurityProfile.Unsecured, count = 3).start()
            val e = mesh.id(E)
            mesh.leave(E)
            mesh.send(A, e, "hello")
            mesh.leave(A)
            mesh.nodes[R].stop()
            mesh.nodes[R].start()
            advanceTimeBy(SETTLE_MILLIS)

            // Act
            mesh.place(E, 2 * SPACING)
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertEquals(emptyList(), mesh.texts(E))
            assertEquals(0, mesh.grafts(E), "E grafted")
        }

    @Test
    fun anUnsecuredRelaysCopyIsDeletedByTheTombstoneOfAReceiptAnotherRelayCarried() =
        runTest(timeout = 5.minutes) {
            buries(SecurityProfile.Unsecured, WireProfile.UNSECURED)
        }

    @Test
    fun aClassicalRelaysCopyIsDeletedByTheTombstoneOfAReceiptAnotherRelayCarried() =
        runTest(timeout = 5.minutes) {
            buries(SecurityProfile.Classical, WireProfile.CLASSICAL)
        }

    @Test
    fun aPostQuantumRelaysCopyIsDeletedByTheTombstoneOfAReceiptAnotherRelayCarried() =
        runTest(timeout = 5.minutes) {
            buries(SecurityProfile.PostQuantum, WireProfile.POST_QUANTUM)
        }

    // E is away while A sends; R holds the frame; E returns next to R once A's retries have run on.
    private suspend fun TestScope.handsOver(profile: SecurityProfile) {
        // Arrange
        val mesh = ChatMesh(this, profile, count = 3).start()
        val e = mesh.id(E)
        mesh.leave(E)
        val message = mesh.send(A, e, "hello", settleMillis = RETRIES_MILLIS)

        // Act
        mesh.place(E, 2 * SPACING)
        advanceTimeBy(SETTLE_MILLIS)

        // Assert
        assertEquals(listOf("hello"), mesh.texts(E))
        assertTrue(mesh.grafts(E) > 0, "E sent no GRAFT")
        assertEquals(DeliveryStatus.Delivered, message.status.value)
    }

    // R1 and R2, of the medium class amid a crowd, both hold A's message to E; E returns next to R1 and its receipt
    // through R1 carries the tombstone; R2 then meets R1, and F, which met R2 before, meets it again.
    private suspend fun TestScope.buries(
        profile: SecurityProfile,
        wire: WireProfile,
    ) {
        // Arrange
        val mesh = ChatMesh(this, profile, count = 5).start()
        val e = mesh.id(E)
        mesh.leave(E)
        mesh.leave(F)
        mesh.place(R2, -SPACING)
        repeat(MEDIUM_CROWD) { mesh.air.move(advertiseFake(mesh.air, null, profile = wire), 0.0, CROWD_NEAR) }
        advanceTimeBy(SETTLE_MILLIS)
        assertEquals(listOf(MEDIUM_OFF, MEDIUM_OFF), listOf(R, R2).map { lastGap(mesh.platforms[it]) }, "scan off")
        mesh.send(A, e, "hello", settleMillis = RETRIES_MILLIS)
        mesh.leave(A)
        mesh.meetFar(R2, F)
        mesh.leave(F)
        mesh.place(E, 2 * SPACING)
        advanceTimeBy(SETTLE_MILLIS)

        // Act
        mesh.place(R2, 0.0)
        advanceTimeBy(SETTLE_MILLIS)
        mesh.meetFar(R2, F)

        // Assert
        assertEquals(listOf("hello"), mesh.texts(E))
        val seen = mesh.catchUps(F).filter { it.held.isNotEmpty() || it.tombstones.isNotEmpty() }
        assertEquals(listOf(1 to 0, 0 to 1), seen.map { it.held.size to it.tombstones.size })
        assertContentEquals(seen[0].held[0].messageId, seen[1].tombstones[0].messageId)
    }

    private companion object {
        const val A = 0
        const val R = 1
        const val E = 2
        const val B = 3
        const val Q = 3
        const val R2 = 3
        const val F = 4
        const val SPACING = 25.0
        const val AWAY = 1_000.0
        const val SETTLE_MILLIS = 60_000L

        // Past A's backoff retries at 2, 6, 14 and 30 s.
        const val RETRIES_MILLIS = 31_000L

        // Past the next epoch: the tags E claims are no longer its current ones.
        const val EPOCH_MILLIS = 400_000L
        const val EPOCH_SECONDS_MILLIS = 300_000L
        const val LINK_MILLIS = 10_000L
        const val MEDIUM_CROWD = 13
        const val MEDIUM_OFF = 12_000L
        const val CROWD_OFFSET = 20.0

        // In range of A, R1 and R2, not of E at 2 × SPACING.
        const val CROWD_NEAR = 10.0
        const val GRAFT: Byte = 0x0C
        const val CATCH_UP: Byte = 0x0A
    }
}
