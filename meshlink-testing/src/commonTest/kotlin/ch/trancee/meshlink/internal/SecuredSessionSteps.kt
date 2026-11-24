package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DeliveryStatus
import ch.trancee.meshlink.SecurityProfile
import io.github.menjoo.cucumberkmp.core.runner.StepsBuilder
import io.github.menjoo.cucumberkmp.core.runner.given
import io.github.menjoo.cucumberkmp.core.runner.then
import io.github.menjoo.cucumberkmp.core.runner.whenever
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * End-to-end sessions on seeded virtual air (spec "Sessions"): Alice to Eve in [line], with marks
 * where each node's recorded frames stood before the focal sends, and a stranger linked to one of them.
 */
internal fun StepsBuilder.securedSessionSteps(line: SecuredLine) {
    val scenario = SessionScenario(line)
    sessionGivens(scenario)
    sessionWhens(scenario)
    sessionThens(scenario)
    relayThens(scenario)
    forgeryWhens(scenario)
    forgeryThens(scenario)
}

/** One scenario's [line], its marks and its stranger. */
private class SessionScenario(
    val line: SecuredLine,
) {
    lateinit var stranger: SecuredLine.Hostile

    /** How many `MESSAGE` frames each node had received when the focal sends began. */
    var marks = List(0) { 0 }

    /** How many receipts Dave had relayed, and the inboxes of Alice and Eve, when the forgery came. */
    var receiptsBefore = 0
    var inboxesBefore = 0 to 0

    /** The `message_id` of the forged frame, and the node next to its destination. */
    var forged = ByteArray(0) to DAVE

    fun mark() {
        marks = List(line.length) { line.messages(it).size }
    }

    /** The `MESSAGE` frames node [index] received since the mark, grouped by `message_id` in order. */
    fun since(index: Int): List<List<MessageFrame>> =
        line
            .messages(index)
            .drop(marks[index])
            .groupBy { it.header.messageId.toHexString() }
            .values
            .toList()

    /** Alice sends [count] messages to Eve, one every [millis]; then [settleMillis] pass. */
    fun sendMany(
        count: Int,
        millis: Long,
        settleMillis: Long = SETTLED_MILLIS,
    ) {
        mark()
        repeat(count) { line.send(line.id(EVE), "Message ${it + 1}", settleMillis = millis) }
        line.settle(settleMillis)
    }

    fun delivered(text: String) = assertEquals(DeliveryStatus.Delivered, line.latest.getValue(text), text)
}

private fun StepsBuilder.sessionGivens(scenario: SessionScenario) {
    val line = scenario.line
    given(
        "Alice to Eve are running in a classical line with links only between neighbors, " +
            "Eve's clock {int} seconds ahead",
    ) { seconds: Int ->
        line.start(SecurityProfile.Classical, mapOf(EVE to seconds * MILLIS_PER_SECOND))
    }

    given("a stranger is linked to {word}") { name: String ->
        scenario.stranger = line.hostile(NAMES.indexOf(name))
    }

    given("Alice's message {string} to Eve was Delivered") { text: String ->
        line.send(line.id(EVE), text)
        scenario.delivered(text)
    }

    given("Alice's messages {string} and {string} to Eve were Delivered") { first: String, second: String ->
        scenario.mark()
        listOf(first, second).forEach {
            line.send(line.id(EVE), it)
            scenario.delivered(it)
        }
    }

    given("Alice's {int} messages to Eve, one every {int} milliseconds, were Delivered") { count: Int, millis: Int ->
        // Settles well within the message lifetime, so that the first of them can still be passed on.
        scenario.sendMany(count, millis.toLong(), DELIVERED_MILLIS)
        repeat(count) { scenario.delivered("Message ${it + 1}") }
    }

    given("Eve restarted, forgetting her sessions") {
        line.restart(EVE)
        scenario.mark()
    }
}

private fun StepsBuilder.sessionWhens(scenario: SessionScenario) {
    val line = scenario.line
    whenever("Alice sends {int} messages to Eve, one every {int} seconds") { count: Int, seconds: Int ->
        scenario.sendMany(count, seconds * MILLIS_PER_SECOND)
    }

    whenever("Alice sends {int} messages to Eve, one every second") { count: Int ->
        scenario.sendMany(count, MILLIS_PER_SECOND)
    }

    whenever(
        "Alice sends {string} to Eve and Eve sends {string} to Alice at the same moment",
    ) { toEve: String, toAlice: String ->
        line.send(line.id(EVE), toEve, settleMillis = 0)
        line.send(line.id(ALICE), toAlice, from = EVE)
        line.settle(SETTLED_MILLIS)
        scenario.mark()
    }

    whenever(
        "then Alice sends {string} to Eve and Eve sends {string} to Alice",
    ) { toEve: String, toAlice: String ->
        line.send(line.id(EVE), toEve, settleMillis = 0)
        line.send(line.id(ALICE), toAlice, from = EVE)
        line.settle(SETTLED_MILLIS)
    }

    whenever(
        "Alice sends {string} to Eve 5 hours 59 minutes after \"Hello\", and {string} 6 hours 1 minute after it",
    ) { before: String, after: String ->
        assertEquals(DeliveryStatus.Delivered, line.latest.getValue("Hello"))
        line.settle(BEFORE_MILLIS - DELIVERED_MILLIS)
        scenario.mark()
        line.send(line.id(EVE), before)
        line.settle(AFTER_MILLIS - BEFORE_MILLIS - DELIVERED_MILLIS)
        line.send(line.id(EVE), after)
        line.settle(SETTLED_MILLIS)
    }
}

private fun StepsBuilder.sessionThens(scenario: SessionScenario) {
    val line = scenario.line
    then("each of Alice's {int} messages was Delivered, and Eve received each once") { count: Int ->
        repeat(count) { scenario.delivered("Message ${it + 1}") }
        val texts = line.inboxes[EVE].filter { it.sender == line.id(ALICE) }.map { it.payload.decodeToString() }
        assertEquals(List(count) { "Message ${it + 1}" }, texts.filter { it.startsWith("Message ") })
    }

    then(
        "Dave relayed the first as a first message to Eve, " +
            "and the other {int} as session frames to one conversation tag",
    ) { others: Int ->
        val messages = scenario.since(DAVE)
        assertEquals(others + 1, messages.size)
        assertKind(Container.IK_INITIATOR, messages.first())
        messages.drop(1).forEach { assertKind(Container.TRANSPORT, it) }
        val tags =
            messages
                .drop(1)
                .flatten()
                .map { it.header.destinationTag.toHexString() }
                .toSet()
        assertEquals(1, tags.size)
        assertTrue(messages.first().none { it.header.destinationTag.toHexString() in tags })
    }

    then("Alice and Eve each sent a first message, and all four messages were Delivered") {
        listOf(ALICE, EVE).forEach { node ->
            assertEquals(1, line.messages(node).count { it.container is Container.IkInitiator }, NAMES[node])
        }
        assertEquals(4, line.history.size)
        line.history.keys.forEach(scenario::delivered)
    }

    then("both later messages traveled in the session that the lower NodeId of Alice and Eve opened") {
        val (lower, higher) =
            if (LinkPolicy.compareNodeIds(line.id(ALICE).toByteArray(), line.id(EVE).toByteArray()) < 0) {
                ALICE to EVE
            } else {
                EVE to ALICE
            }

        // Frames to an initiator go to the reply tag its first message carried as its source tag.
        fun replyTag(of: Int) =
            line
                .messages(if (of == ALICE) EVE else ALICE)
                .single { it.container is Container.IkInitiator }
                .header.sourceTag
        val toLower = scenario.since(lower).flatten().filter { it.container is Container.Transport }
        val toHigher = scenario.since(higher).flatten().filter { it.container is Container.Transport }
        assertTrue(toLower.isNotEmpty() && toHigher.isNotEmpty())
        toLower.forEach { assertTrue(it.header.destinationTag.contentEquals(replyTag(lower))) }
        toHigher.forEach { assertTrue(!it.header.destinationTag.contentEquals(replyTag(higher))) }
    }
}

private fun StepsBuilder.relayThens(scenario: SessionScenario) {
    val line = scenario.line
    then("Dave relayed {string} as a session frame and {string} as a first message") { before: String, after: String ->
        val messages = scenario.since(DAVE)
        assertEquals(2, messages.size, "$before, $after")
        assertKind(Container.TRANSPORT, messages[0])
        assertKind(Container.IK_INITIATOR, messages[1])
    }

    then("Alice's messages {string} and {string} were Delivered") { first: String, second: String ->
        scenario.delivered(first)
        scenario.delivered(second)
    }

    then(
        "Dave relayed the first and the last as first messages, and the {int} between them as session frames",
    ) { between: Int ->
        val messages = scenario.since(DAVE)
        assertEquals(between + 2, messages.size)
        assertKind(Container.IK_INITIATOR, messages.first())
        messages.subList(1, between + 1).forEach { assertKind(Container.TRANSPORT, it) }
        assertKind(Container.IK_INITIATOR, messages.last())
    }

    then(
        "Dave relayed {string} as session frames, then as a first message with the same message ID",
    ) { text: String ->
        line.settle(LAPSED_MILLIS)
        val messages = scenario.since(DAVE)
        assertEquals(1, messages.size, text)
        val (sessions, firsts) = messages.single().partition { it.container.kind == Container.TRANSPORT }
        assertTrue(sessions.size >= 2, "the session frame was retried")
        assertTrue(firsts.isNotEmpty(), "no first message")
        assertKind(Container.IK_INITIATOR, firsts)
        assertEquals(sessions, messages.single().take(sessions.size), "a session frame after the first message")
    }

    then("Alice's message {string} was Delivered, and Eve received it once") { text: String ->
        scenario.delivered(text)
        assertEquals(1, line.inboxes[EVE].count { it.payload.decodeToString() == text })
    }

    then(
        "Dave relayed the session frames to {int} conversation tags, one for each epoch of Alice's clock",
    ) { count: Int ->
        val tags =
            scenario
                .since(DAVE)
                .flatten()
                .filter { it.container is Container.Transport }
                .map { it.header.destinationTag.toHexString() }
                .distinct()
        assertEquals(count, tags.size)
    }
}

private fun StepsBuilder.forgeryWhens(scenario: SessionScenario) {
    val line = scenario.line

    fun before() {
        scenario.receiptsBefore = line.receipts(DAVE).size
        scenario.inboxesBefore = line.inboxes[ALICE].size to line.inboxes[EVE].size
    }

    fun second(): MessageFrame = scenario.since(DAVE)[1].first()

    whenever("the stranger passes Alice's first message to Eve again") {
        val first = line.messages(DAVE).first { it.container is Container.IkInitiator }
        scenario.receiptsBefore = line.receipts(DAVE).size
        scenario.stranger.write(first.encode())
    }

    suspend fun pass(
        frame: ByteArray,
        next: Int = DAVE,
    ) {
        scenario.forged = frame.copyOfRange(MESSAGE_ID_AT, MESSAGE_ID_END) to next
        scenario.stranger.write(frame)
    }

    whenever("the stranger passes on Alice's message {string} again, under a new message ID") { _: String ->
        before()
        pass(second().renamed().encode())
    }

    whenever("the stranger passes on Alice's second message again, under a new message ID") {
        before()
        pass(second().renamed().encode())
    }

    whenever(
        "the stranger passes on Alice's message {string} with its last byte changed, under a new message ID",
    ) { _: String ->
        before()
        val frame = second().renamed().encode()
        frame[frame.lastIndex] = (frame.last().toInt() xor 1).toByte()
        pass(frame)
    }

    whenever("the stranger passes on Eve's answer to {string} acknowledging another message ID instead") { _: String ->
        before()
        val (_, answer) = line.receipts(DAVE).first { it.second.container is Container.IkResponder }
        val other = answer.tombstones.map { Tombstone(it.messageId.renamed(), it.k) }
        pass(DeliveryReceipt(answer.header.renamed(), other, answer.container).encode(), BOB)
    }
}

private fun StepsBuilder.forgeryThens(scenario: SessionScenario) {
    val line = scenario.line
    then("Eve answered both copies with the same session answer, byte for byte") {
        val answers = line.receipts(DAVE).map { it.second }.filter { it.container is Container.IkResponder }
        assertEquals(scenario.receiptsBefore + 1, line.receipts(DAVE).size)
        assertEquals(2, answers.size)
        assertTrue(answers[0].container.encode().contentEquals(answers[1].container.encode()))
    }

    then("Alice and Eve received nothing more, and Dave relayed no more receipts") {
        val (id, next) = scenario.forged
        val relayed = line.messages(next).map { it.header } + line.receipts(next).map { it.second.header }
        assertTrue(relayed.any { it.messageId.contentEquals(id) }, "the forged frame reached ${NAMES[next]}")
        assertEquals(scenario.inboxesBefore, line.inboxes[ALICE].size to line.inboxes[EVE].size)
        assertEquals(scenario.receiptsBefore, line.receipts(DAVE).size)
    }

    then("Alice's next message {string} to Eve travels in the same session and is Delivered") { text: String ->
        val again = scenario.since(DAVE)[1].first()
        scenario.mark()
        line.send(line.id(EVE), text)
        val later = scenario.since(DAVE).single().first()
        assertKind(Container.TRANSPORT, listOf(later))
        assertTrue(later.header.destinationTag.contentEquals(again.header.destinationTag))
        scenario.delivered(text)
    }

    then("Alice's next message {string} to Eve travels as a session frame and is Delivered") { text: String ->
        scenario.mark()
        line.send(line.id(EVE), text)
        assertKind(Container.TRANSPORT, scenario.since(DAVE).single())
        scenario.delivered(text)
    }
}

private fun assertKind(
    kind: Int,
    frames: List<MessageFrame>,
) = frames.forEach { assertEquals(kind, it.container.kind) }

private fun MessageFrame.renamed() = MessageFrame(header.renamed(), commitment, container)

private fun UnicastHeader.renamed() = UnicastHeader(hopLimit, messageId.renamed(), expiry, destinationTag, sourceTag)

private fun ByteArray.renamed() = copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }

private val NAMES = listOf("Alice", "Bob", "Carol", "Dave", "Eve")
private const val ALICE = 0
private const val BOB = 1
private const val DAVE = 3
private const val EVE = 4
private const val MILLIS_PER_SECOND = 1_000L

// The message_id in a routed frame: after the type and hop_limit bytes.
private const val MESSAGE_ID_AT = 2
private const val MESSAGE_ID_END = 14
private const val BEFORE_MILLIS = 21_540_000L
private const val AFTER_MILLIS = 21_660_000L
private const val DELIVERED_MILLIS = 5_000L
private const val SETTLED_MILLIS = 30_000L
private const val LAPSED_MILLIS = 600_000L
