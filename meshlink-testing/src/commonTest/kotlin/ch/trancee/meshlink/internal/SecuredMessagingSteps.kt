package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DeliveryStatus
import ch.trancee.meshlink.Reachability
import ch.trancee.meshlink.SecurityProfile
import io.github.menjoo.cucumberkmp.core.runner.StepsBuilder
import io.github.menjoo.cucumberkmp.core.runner.given
import io.github.menjoo.cucumberkmp.core.runner.then
import io.github.menjoo.cucumberkmp.core.runner.whenever
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Secured sends on seeded virtual air (spec "First messages"): Alice to Eve in a line, each node
 * recording the frames its links carried to it and the messages it received.
 */
internal fun StepsBuilder.securedMessagingSteps() {
    val line = SecuredLine(SEED)
    after { line.close() }
    securedGivens(line)
    securedWhens(line)
    securedThens(line)
    securedSessionSteps(line)
}

private fun StepsBuilder.securedGivens(line: SecuredLine) {
    given("Alice to Eve are running in a {word} line with links only between neighbors") { name: String ->
        line.start(if (name == "post-quantum") SecurityProfile.PostQuantum else SecurityProfile.Classical)
        line.nodes.forEachIndexed { index, node ->
            assertEquals(listOf(index - 1, index + 1).count { it in 0 until line.length }, node.peers.value.size)
        }
    }

    given("Alice is in the medium density class") { line.medium() }

    given("Alice holds Eve's certificate, has no route to Eve, and does not list Eve as a peer") {
        line.lookUp(EVE)
        assertNotNull(line.certificate(EVE))
        assertNull(
            line.nodes[ALICE]
                .peers.value
                .firstOrNull { it.nodeId == line.id(EVE) },
        )
    }
}

private fun StepsBuilder.securedWhens(line: SecuredLine) {
    whenever("Alice sends {string} to Eve, holding no certificate of Eve") { text: String ->
        assertNull(line.certificate(EVE))
        line.send(line.id(EVE), text)
    }

    whenever("Alice sends {string} to a node of another mesh") { text: String -> line.send(line.outsider(), text) }

    whenever("Alice sends {string} to Eve") { text: String -> line.send(line.id(EVE), text) }

    whenever(
        "Alice sends {string} to a node of another mesh, and then {string} to Eve",
    ) { first: String, second: String ->
        line.send(line.outsider(), first)
        line.send(line.id(EVE), second)
    }
}

private fun StepsBuilder.securedThens(line: SecuredLine) {
    then("Alice's message stayed Queued until Alice held Eve's certificate, was then Sent, and then Delivered") {
        assertEquals(
            listOf(
                DeliveryStatus.Queued to false,
                DeliveryStatus.Sent to true,
                DeliveryStatus.Delivered to true,
            ),
            line.statuses,
        )
    }

    then("Alice's message stays Queued until 30 seconds after its expiry, and is then Expired") {
        val lifetimeMillis = line.nodes[ALICE].config.messageLifetimeSeconds * MILLIS_PER_SECOND
        line.settle(lifetimeMillis + SKEW_MILLIS)
        // The expiry is in whole seconds, rounded down from the moment of the send.
        val expiry = line.sentAtMillis / MILLIS_PER_SECOND * MILLIS_PER_SECOND + lifetimeMillis
        val expected =
            listOf(DeliveryStatus.Queued to line.sentAtMillis, DeliveryStatus.Expired to expiry + SKEW_MILLIS)
        assertEquals(expected, line.changes)
    }

    then("Eve receives {string} from Alice once") { text: String ->
        val received = line.inboxes[EVE].map { it.sender to it.payload.decodeToString() }
        assertEquals(listOf(line.id(ALICE) to text), received)
    }

    then("Bob, Carol and Dave relayed the message only as {int}-byte frames without its text") { size: Int ->
        val text = line.sent.encodeToByteArray().toHexString()
        listOf(BOB, CAROL, DAVE).forEach { relay ->
            val messages = line.received[relay].frames().filter { it.isMessage() }
            assertTrue(messages.isNotEmpty(), "relay ${'A' + relay}")
            assertEquals(setOf(size), messages.map { it.size }.toSet(), "relay ${'A' + relay}")
            assertTrue(messages.none { text in it.toHexString() }, "relay ${'A' + relay} saw the text")
        }
    }

    then("Alice's message {string} is still Queued") { text: String ->
        assertEquals(DeliveryStatus.Queued, line.latest.getValue(text))
    }

    then("Alice lists Eve as a routed peer {int} hops away") { hops: Int ->
        val eve =
            line.nodes[ALICE]
                .peers.value
                .single { it.nodeId == line.id(EVE) }
        assertEquals(Reachability.Routed(hops), eve.reachability)
        assertTrue(eve.secured)
    }
}

private fun ByteArray.isMessage(): Boolean =
    (UnicastFrame.decode(this) as? Decoded.Valid)?.let { it.value.type == MessageFrame.TYPE } == true

private const val SEED = 45L
private const val ALICE = 0
private const val BOB = 1
private const val CAROL = 2
private const val DAVE = 3
private const val EVE = 4
private const val SKEW_MILLIS = 30_000L
private const val MILLIS_PER_SECOND = 1_000L
