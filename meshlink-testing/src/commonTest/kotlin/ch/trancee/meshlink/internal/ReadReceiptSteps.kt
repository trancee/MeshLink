package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DeliveryStatus
import ch.trancee.meshlink.DiagnosticEvent
import ch.trancee.meshlink.ReadReceiptRequest
import ch.trancee.meshlink.SecurityProfile
import ch.trancee.meshlink.SendOptions
import io.github.menjoo.cucumberkmp.core.runner.StepsBuilder
import io.github.menjoo.cucumberkmp.core.runner.given
import io.github.menjoo.cucumberkmp.core.runner.then
import io.github.menjoo.cucumberkmp.core.runner.whenever
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Read receipts on seeded virtual air (spec "Read receipts"): Alice to Eve in [line], Alice asking for
 * read receipts by default.
 */
internal fun StepsBuilder.readReceiptSteps(line: SecuredLine) {
    readGivens(line)
    readWhens(line)
    readThens(line)
    batchThens(line)
}

private fun StepsBuilder.readGivens(line: SecuredLine) {
    val start = "Alice to Eve are running in a {word} line, where Alice asks for read receipts by default"
    given("$start and every node but Dave sends them") { name: String ->
        line.startReading(name) { it != DAVE }
    }

    given("$start and every node sends them") { name: String -> line.startReading(name) { true } }

    given("$start, every node sends them, and Eve's delivery receipts are lost") { name: String ->
        line.crypto = { index, crypto -> if (index == EVE) LosingDeliveryReceipts(crypto) else crypto }
        line.startReading(name) { true }
    }

    given("Alice's {int} messages to Eve were Delivered") { count: Int ->
        repeat(count) { line.send(line.id(EVE), "Message ${it + 1}", settleMillis = 0) }
        line.settle(SETTLED_MILLIS)
        repeat(count) { assertEquals(DeliveryStatus.Delivered, line.latest.getValue("Message ${it + 1}")) }
    }
}

private fun StepsBuilder.readWhens(line: SecuredLine) {
    whenever(
        "Alice sends {string} to Eve as by default, {string} to Eve without asking, and {string} to Dave asking",
    ) { asked: String, notAsked: String, refused: String ->
        line.send(line.id(EVE), asked)
        line.send(line.id(EVE), notAsked, options = SendOptions(readReceipt = ReadReceiptRequest.DoNotRequest))
        line.send(line.id(DAVE), refused, options = SendOptions(readReceipt = ReadReceiptRequest.Request))
    }

    whenever("Eve and Dave mark every message they received read") {
        listOf(EVE, DAVE).forEach { reader -> line.inboxes[reader].forEach { it.markRead() } }
        line.settle(READ_MILLIS)
    }

    whenever("Eve marks all {int} read at once") { count: Int ->
        assertEquals(count, line.inboxes[EVE].size)
        line.inboxes[EVE].forEach { it.markRead() }
    }

    whenever("Eve marks {string} read") { text: String ->
        line.read(text)
        line.settle(READ_MILLIS)
    }

    whenever("Eve marks {string} read {int} seconds later, after its lifetime of {int} seconds") {
        text: String,
        seconds: Int,
        lifetime: Int,
        ->
        assertEquals(lifetime, line.nodes[EVE].config.messageLifetimeSeconds)
        line.settle(seconds * MILLIS_PER_SECOND)
        line.read(text)
        line.settle(READ_MILLIS)
    }

    whenever("Alice's {int} messages to Bob were Delivered, and Bob read none of them") { count: Int ->
        repeat(count) { line.send(line.id(BOB), "Message ${it + 1}", settleMillis = SPACED_MILLIS) }
        line.settle(SETTLED_MILLIS)
        repeat(count) { assertEquals(DeliveryStatus.Delivered, line.latest.getValue("Message ${it + 1}")) }
    }
}

private fun StepsBuilder.readThens(line: SecuredLine) {
    then("Alice's message {string} is Read, and {string} and {string} stay Delivered") {
        read: String,
        first: String,
        second: String,
        ->
        assertEquals(DeliveryStatus.Read, line.latest.getValue(read))
        listOf(first, second).forEach { assertEquals(DeliveryStatus.Delivered, line.latest.getValue(it), it) }
    }

    then("Eve received {string} asking for a read receipt and {string} not") { asked: String, notAsked: String ->
        val requested = line.inboxes[EVE].associate { it.payload.decodeToString() to it.readReceiptRequested }
        assertEquals(mapOf(asked to true, notAsked to false), requested)
    }

    mapOf("a first message" to Container.IkInitiator::class, "a session frame" to Container.Transport::class)
        .forEach { (kind, container) ->
            then("Alice's message {string} is Read, and Alice received the read receipt as $kind") { text: String ->
                assertEquals(DeliveryStatus.Read, line.latest.getValue(text))
                assertTrue(container.isInstance(line.reads(ALICE).single().container))
            }
        }

    then("Alice received the read receipt as a first message") {
        assertIs<Container.IkInitiator>(line.reads(ALICE).single().container)
    }

    then(
        "Alice received no delivery receipt, and her message {string} went from Queued and Sent to Read",
    ) { text: String ->
        assertTrue(line.receipts(ALICE).isEmpty())
        val read = listOf(DeliveryStatus.Queued, DeliveryStatus.Sent, DeliveryStatus.Read)
        assertEquals(read, line.history.getValue(text))
    }

    then("Alice reported once that she stopped watching her first message for its read receipt") {
        val evicted = line.diagnostics[ALICE].filterIsInstance<DiagnosticEvent.ReadTrackingEvicted>().single()
        assertEquals(
            line
                .messages(BOB)
                .first()
                .header.messageId
                .toList(),
            evicted.messageId.toList(),
        )
        assertEquals(DeliveryStatus.Delivered, line.latest.getValue("Message 1"))
    }
}

private fun StepsBuilder.batchThens(line: SecuredLine) {
    then("{double} seconds later Alice had received {int} read receipt, and {int} of her messages were Read") {
        seconds: Double,
        receipts: Int,
        read: Int,
        ->
        line.settle((seconds * MILLIS_PER_SECOND).toLong())
        assertEquals(receipts, line.reads(ALICE).size)
        assertEquals(read, line.latest.values.count { it == DeliveryStatus.Read })
    }

    then("{int} second after that Alice had received {int} read receipts, and all {int} were Read") {
        seconds: Int,
        receipts: Int,
        read: Int,
        ->
        line.settle(seconds * MILLIS_PER_SECOND)
        assertEquals(receipts, line.reads(ALICE).size)
        assertEquals(read, line.latest.values.count { it == DeliveryStatus.Read })
    }
}

private suspend fun SecuredLine.startReading(
    name: String,
    sends: (index: Int) -> Boolean,
) {
    configure = { index ->
        readReceipts.send = sends(index)
        readReceipts.requestByDefault = index == ALICE
    }
    start(PROFILES.getValue(name))
}

private fun SecuredLine.read(text: String) = inboxes[EVE].single { it.payload.decodeToString() == text }.markRead()

/**
 * [crypto] for Eve, whose links never carry her `DELIVERY_RECEIPT`s as such: each becomes a frame of an
 * unknown unicast type, which relays pass on and its destination ignores.
 */
private class LosingDeliveryReceipts(
    private val crypto: CryptoProvider,
) : CryptoProvider by crypto {
    override fun chaCha20Poly1305Seal(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray {
        val lost = DeliveryReceipt.decode(plaintext) is Decoded.Valid
        val sealed = if (lost) plaintext.copyOf().also { it[0] = UNKNOWN_TYPE } else plaintext
        return crypto.chaCha20Poly1305Seal(key, nonce, sealed, associatedData)
    }
}

private val PROFILES =
    mapOf(
        "unsecured" to SecurityProfile.Unsecured,
        "classical" to SecurityProfile.Classical,
        "post-quantum" to SecurityProfile.PostQuantum,
    )
private const val ALICE = 0
private const val BOB = 1
private const val DAVE = 3
private const val EVE = 4
private const val UNKNOWN_TYPE: Byte = 0x45
private const val READ_MILLIS = 10_000L
private const val SETTLED_MILLIS = 30_000L
private const val SPACED_MILLIS = 100L
private const val MILLIS_PER_SECOND = 1_000L
