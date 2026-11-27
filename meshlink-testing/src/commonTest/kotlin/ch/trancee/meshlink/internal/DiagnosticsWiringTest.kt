package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DiagnosticEvent
import ch.trancee.meshlink.SecurityProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.minutes

/**
 * The diagnostics of certificate lookups and of a full inbox, from running nodes on virtual air (ADR 0020
 * decision 7): the units that report them are tested alone; these show the node wires them to its stream.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsWiringTest {
    @Test
    fun aLookupAnsweredFourHopsAwayAndOneNobodyAnswersAreReportedWithTheirRequests() =
        runTest(timeout = 5.minutes) {
            val line = SecuredLine(SEED)
            try {
                // Arrange
                line.configure = { diagnostics.debug = true }
                line.start(SecurityProfile.Classical)
                val outsider = line.outsider()

                // Act
                line.send(line.id(EVE), "Hi")
                line.send(outsider, "Anyone there?", settleMillis = UNANSWERED_MILLIS)

                // Assert
                val lookups = line.diagnostics[ALICE].filter { it.code in LOOKUP_CODES }
                val eve = assertIs<DiagnosticEvent.CertificateLookup>(lookups.first()).peer.toString()
                val other = assertIs<DiagnosticEvent.CertificateLookupUnanswered>(lookups.last()).peer.toString()
                assertEquals(
                    listOf(
                        "ML6002 CertificateLookup(peer=E, attempts=1) x1",
                        "ML6003 CertificateLookupUnanswered(peer=O, attempts=$UNANSWERED_ATTEMPTS) x1",
                    ),
                    lookups.map { it.toString().replace(eve, "E").replace(other, "O") },
                )
            } finally {
                line.close()
            }
        }

    @Test
    fun aMessageOverTheSendersShareOfAnUncollectedInboxIsReportedAsInboxFull() =
        runTest {
            // Arrange
            val air = air()
            val nodes = List(2) { node(air.platform(StandardTestDispatcher(testScheduler))) }
            nodes.forEach { it.start() }
            advanceTimeBy(SETTLE_MILLIS)
            val seen = diagnostics(nodes[1], "ML7003")
            val bob =
                nodes[0]
                    .peers.value
                    .single()
                    .nodeId

            // Act: the third payload would take Alice's share of Bob's inbox over a quarter of 1 MiB.
            repeat(SENDS) { nodes[0].send(bob, ByteArray(PAYLOAD_BYTES)) }
            advanceTimeBy(INBOX_FULL_MILLIS)

            // Assert: Alice's retries of the refused message are refused, and reported, again.
            assertEquals("ML7003 InboxFull(bound=Sender) x1", seen.first().toString())
        }

    private companion object {
        const val SEED = 46L
        const val ALICE = 0
        const val EVE = 4
        const val SENDS = 3
        const val PAYLOAD_BYTES = 100_000

        // The three payloads cross the link within 8 s, as the kept link's central or as its credited peripheral:
        // the random NodeIds decide which (`VirtualAir`).
        const val INBOX_FULL_MILLIS = 20_000L

        // Past the message's 30-minute lifetime: the lookup's deadline.
        const val UNANSWERED_MILLIS = 1_900_000L

        // The requests the lookup sent before its deadline, on this seed.
        const val UNANSWERED_ATTEMPTS = 6
        val LOOKUP_CODES = setOf("ML6002", "ML6003")
    }
}
