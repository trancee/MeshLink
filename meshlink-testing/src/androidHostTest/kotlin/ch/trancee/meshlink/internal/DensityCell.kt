package ch.trancee.meshlink.internal

import ch.trancee.meshlink.NodeId
import ch.trancee.meshlink.SendResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/**
 * A cell of the scheduled density suite: [nodes] phones in a square sized so each has on average
 * [meanPeers] others in range (routing simulation, "Simulator model"). 50 phones cannot average 100.
 */
internal enum class Cell(
    val nodes: Int,
    val meanPeers: Double,
) {
    N50_LOW(50, 6.0),
    N50_MEDIUM(50, 25.0),
    N100_LOW(100, 6.0),
    N100_MEDIUM(100, 25.0),
    N100_HIGH(100, 100.0),
    N200_LOW(200, 6.0),
    N200_MEDIUM(200, 25.0),
    N200_HIGH(200, 100.0),
    ;

    /** The cell's name in reports and on the command line, such as `n200-high`. */
    val label: String get() = name.lowercase().replace('_', '-')

    companion object {
        fun labeled(label: String): Cell = entries.single { it.label == label.trim() }
    }
}

/** A sender's true local density class by present peers in range: low below 10, medium below 50, high from 50. */
internal enum class SenderDensity {
    LOW,
    MEDIUM,
    HIGH,
    ;

    /** The least share of reachable recipients that must receive a message from this class (criterion 1). */
    val band: Double get() = if (this == HIGH) HIGH_BAND else LOW_MEDIUM_BAND

    companion object {
        fun of(peersInRange: Int): SenderDensity =
            when {
                peersInRange < MEDIUM_FROM -> LOW
                peersInRange < HIGH_FROM -> MEDIUM
                else -> HIGH
            }

        private const val MEDIUM_FROM = 10
        private const val HIGH_FROM = 50
        private const val LOW_MEDIUM_BAND = 0.99
        private const val HIGH_BAND = 0.95
    }
}

/** Reachable recipients, deliveries and duplicates of one kind of message, by [SenderDensity]. */
internal class Tally {
    val recipients: IntArray = IntArray(SenderDensity.entries.size)
    val delivered: IntArray = IntArray(SenderDensity.entries.size)
    val duplicates: IntArray = IntArray(SenderDensity.entries.size)

    operator fun plusAssign(other: Tally) {
        SenderDensity.entries.indices.forEach {
            recipients[it] += other.recipients[it]
            delivered[it] += other.delivered[it]
            duplicates[it] += other.duplicates[it]
        }
    }
}

/** What one seed of a cell measured. */
internal class SeedRun(
    val seed: Long,
    val wallMillis: Long,
    val unicast: Tally,
    val broadcast: Tally,
    val rejected: Int,
    val idleControlShare: Double,
    val busiestBytesPerSecond: Double,
) {
    companion object
}

/** A message the run sent: [to] is -1 for a broadcast; [recipients] could reach it at send time. */
private class CellMessage(
    val to: Int,
    val atMillis: Long,
    val density: SenderDensity,
    val recipients: List<Int>,
)

/**
 * Runs [cell] with [seed]: random waypoint mobility, link drops, churn, a 2 min warm-up whose second
 * minute measures idle control, then 15 min of chat and broadcast traffic, each message followed until
 * it arrives or its lifetime ends (routing simulation, "Simulator model").
 */
internal fun runCell(
    cell: Cell,
    seed: Long,
): SeedRun {
    var run: SeedRun? = null
    runTest(timeout = SEED_TIMEOUT_MINUTES.minutes) { run = CellRun(this, cell, seed).run() }
    return checkNotNull(run)
}

@OptIn(ExperimentalCoroutinesApi::class)
private class CellRun(
    private val scope: TestScope,
    cell: Cell,
    private val seed: Long,
) {
    private val random = Random(seed xor TRAFFIC_SALT)
    private val n = cell.nodes
    private val side = sqrt(n * PI * VirtualAir.DEFAULT_RANGE * VirtualAir.DEFAULT_RANGE / cell.meanPeers)
    private val x = DoubleArray(n) { random.nextDouble(side) }
    private val y = DoubleArray(n) { random.nextDouble(side) }
    private val mesh = VirtualMesh(scope, seed, List(n) { x[it] to y[it] }, recording = false)
    private val present = BooleanArray(n) { true }
    private val written = LongArray(n)
    private var counting = false
    private val ids = arrayOfNulls<NodeId>(n)
    private val sent = mutableListOf<CellMessage>()
    private var rejected = 0

    suspend fun run(): SeedRun {
        val started = TimeSource.Monotonic.markNow()
        val index = mesh.platforms.withIndex().associate { (i, platform) -> platform.radio to i }
        mesh.air.onWrite = { radio, bytes -> if (counting) written[index.getValue(radio)] += bytes.size.toLong() }
        mesh.start()
        mesh.dropLinksAtRandom(DROP_MEAN_MILLIS)
        walk()
        churn()

        scope.testScheduler.advanceTimeBy(WARM_UP_MILLIS - IDLE_WINDOW_MILLIS)
        val idleShare = measureIdleControl()
        val contacts = List(n) { contactsOf(it) }

        written.fill(0)
        counting = true
        traffic(contacts)
        counting = false
        val busiest = written.max().toDouble() / (TRAFFIC_MILLIS / MILLIS)

        drain()
        val (unicast, broadcast) = tally()
        mesh.close()
        return SeedRun(seed, started.elapsedNow().inWholeMilliseconds, unicast, broadcast, rejected, idleShare, busiest)
    }

    // Random waypoint: each phone walks at U(0.8, 1.8) m/s to a random point, then picks the next.
    private fun walk() {
        val targetX = DoubleArray(n) { random.nextDouble(side) }
        val targetY = DoubleArray(n) { random.nextDouble(side) }
        val speed = DoubleArray(n) { random.nextDouble(SPEED_MIN, SPEED_MAX) }
        scope.backgroundScope.launch {
            while (true) {
                delay(MILLIS)
                for (i in 0 until n) {
                    val distance = hypot(targetX[i] - x[i], targetY[i] - y[i])
                    if (distance <= speed[i]) {
                        x[i] = targetX[i]
                        y[i] = targetY[i]
                        targetX[i] = random.nextDouble(side)
                        targetY[i] = random.nextDouble(side)
                        speed[i] = random.nextDouble(SPEED_MIN, SPEED_MAX)
                    } else {
                        x[i] += (targetX[i] - x[i]) * speed[i] / distance
                        y[i] += (targetY[i] - y[i]) * speed[i] / distance
                    }
                    mesh.air.move(mesh.platforms[i].radio, x[i], y[i])
                }
            }
        }
    }

    // Each phone leaves after Exp(10 min), stays away 30 s and comes back.
    private fun churn() {
        for (i in 0 until n) {
            val leaves = Random(seed * n + i)
            scope.backgroundScope.launch {
                while (true) {
                    delay(exponential(leaves, CHURN_MEAN_MILLIS))
                    present[i] = false
                    mesh.nodes[i].stop()
                    delay(AWAY_MILLIS)
                    mesh.nodes[i].start()
                    present[i] = true
                }
            }
        }
    }

    // Bytes over a traffic-free minute, as a share of the capacity of the links open in it.
    private fun measureIdleControl(): Double {
        written.fill(0)
        counting = true
        var linkSeconds = 0L
        repeat((IDLE_WINDOW_MILLIS / MILLIS).toInt()) {
            scope.testScheduler.advanceTimeBy(MILLIS)
            linkSeconds +=
                mesh.platforms.sumOf { platform ->
                    platform.radio.ends.count { it is VirtualCentralEnd && it.open }
                }
        }
        counting = false
        return if (linkSeconds == 0L) 0.0 else written.sum() / (linkSeconds * LINK_CAPACITY_BYTES_PER_SECOND)
    }

    // Two near contacts, in range at the start if any, and one far contact out of range.
    private fun contactsOf(i: Int): List<Int> {
        val others = (0 until n).filter { it != i }.shuffled(random)
        val (near, far) = others.partition { inRange(i, it) }
        return (near + far).take(NEAR_CONTACTS) + (far.lastOrNull() ?: others.last())
    }

    private fun traffic(contacts: List<List<Int>>) {
        val start = scope.testScheduler.currentTime
        val events = mutableListOf<Pair<Long, Int>>()
        for (i in 0 until n) {
            var at = exponential(random, CHAT_GAP_MILLIS)
            while (at < TRAFFIC_MILLIS) {
                events += at to i
                at += exponential(random, CHAT_GAP_MILLIS)
            }
        }
        for (at in 0 until TRAFFIC_MILLIS step BROADCAST_GAP_MILLIS) events += at to BROADCAST
        events.sortedBy { it.first }.forEach { (offset, from) ->
            scope.testScheduler.advanceTimeBy(start + offset - scope.testScheduler.currentTime)
            if (from == BROADCAST) broadcast() else chat(from, contacts[from].random(random))
        }
        scope.testScheduler.advanceTimeBy(start + TRAFFIC_MILLIS - scope.testScheduler.currentTime)
    }

    private fun chat(
        from: Int,
        to: Int,
    ) {
        if (!present[from]) return
        val id = ids[to] ?: mesh.idOf(to)?.also { ids[to] = it } ?: return
        val component = components()
        val reachable = present[to] && component[to] == component[from]
        val result = mesh.nodes[from].send(id, payload())
        if (result !is SendResult.Accepted) rejected++
        val recipients = if (reachable) listOf(to) else emptyList()
        sent += CellMessage(to, scope.testScheduler.currentTime, density(from), recipients)
    }

    private fun broadcast() {
        val from = (0 until n).filter { present[it] }.randomOrNull(random) ?: return
        val component = components()
        val recipients = (0 until n).filter { it != from && present[it] && component[it] == component[from] }
        val result = mesh.nodes[from].broadcast(payload())
        if (result !is SendResult.Accepted) rejected++
        sent += CellMessage(BROADCAST, scope.testScheduler.currentTime, density(from), recipients)
    }

    // A payload that starts with the index of its message, so arrivals map back to it.
    private fun payload(): ByteArray =
        random.nextBytes(PAYLOAD_BYTES).also { bytes ->
            val index = sent.size
            for (b in 0 until Int.SIZE_BYTES) bytes[b] = (index ushr (Byte.SIZE_BITS * b)).toByte()
        }

    private fun indexOf(payload: ByteArray): Int =
        (0 until Int.SIZE_BYTES).sumOf { b -> (payload[b].toInt() and BYTE_MASK) shl (Byte.SIZE_BITS * b) }

    // Follows the messages until every reachable recipient has its copy or the last lifetime ends.
    private fun drain() {
        val last = sent.maxOfOrNull { it.atMillis } ?: return
        val deadline = last + mesh.config.messageLifetimeSeconds * MILLIS
        while (scope.testScheduler.currentTime < deadline && !allArrived()) {
            scope.testScheduler.advanceTimeBy(DRAIN_STEP_MILLIS)
        }
        scope.testScheduler.advanceTimeBy(DUPLICATE_WATCH_MILLIS)
    }

    private fun allArrived(): Boolean {
        val arrived = arrivals()
        return sent.withIndex().all { (index, message) -> message.recipients.all { (index to it) in arrived } }
    }

    // How often each recipient received each message within its lifetime.
    private fun arrivals(): Map<Pair<Int, Int>, Int> {
        val lifetimeMillis = mesh.config.messageLifetimeSeconds * MILLIS
        val counts = mutableMapOf<Pair<Int, Int>, Int>()
        mesh.inboxes.forEachIndexed { node, inbox ->
            inbox.forEach { arrival ->
                val index = indexOf(arrival.message.payload)
                val message = sent.getOrNull(index)
                if (message != null && arrival.atMillis <= message.atMillis + lifetimeMillis) {
                    counts.merge(index to node, 1, Int::plus)
                }
            }
        }
        return counts
    }

    private fun tally(): Pair<Tally, Tally> {
        val arrived = arrivals()
        val unicast = Tally()
        val broadcast = Tally()
        sent.forEachIndexed { index, message ->
            val tally = if (message.to == BROADCAST) broadcast else unicast
            val c = message.density.ordinal
            message.recipients.forEach { recipient ->
                val count = arrived[index to recipient] ?: 0
                tally.recipients[c]++
                if (count > 0) tally.delivered[c]++
                if (count > 1) tally.duplicates[c]++
            }
        }
        return unicast to broadcast
    }

    private fun inRange(
        a: Int,
        b: Int,
    ): Boolean = hypot(x[a] - x[b], y[a] - y[b]) <= VirtualAir.DEFAULT_RANGE

    // The sender's true local density: present phones in range.
    private fun density(i: Int): SenderDensity = SenderDensity.of(presentPeersInRange(i))

    private fun presentPeersInRange(i: Int): Int = (0 until n).count { it != i && present[it] && inRange(i, it) }

    // Each phone's component of present phones joined by chains of phones in range of each other.
    private fun components(): IntArray {
        val parent = IntArray(n) { it }

        fun root(i: Int): Int {
            var r = i
            while (parent[r] != r) r = parent[r]
            return r
        }
        for (a in 0 until n) {
            if (!present[a]) continue
            for (b in a + 1 until n) {
                if (present[b] && inRange(a, b)) parent[root(a)] = root(b)
            }
        }
        return IntArray(n) { root(it) }
    }

    private companion object {
        const val TRAFFIC_SALT = 0x5EED_CE11L
        const val MILLIS = 1_000L
        const val WARM_UP_MILLIS = 120_000L
        const val IDLE_WINDOW_MILLIS = 60_000L
        const val TRAFFIC_MILLIS = 900_000L
        const val DRAIN_STEP_MILLIS = 5_000L
        const val DUPLICATE_WATCH_MILLIS = 60_000L
        const val DROP_MEAN_MILLIS = 45_000L
        const val CHURN_MEAN_MILLIS = 600_000.0
        const val AWAY_MILLIS = 30_000L
        const val CHAT_GAP_MILLIS = 240_000.0
        const val BROADCAST_GAP_MILLIS = 15_000L
        const val SPEED_MIN = 0.8
        const val SPEED_MAX = 1.8
        const val NEAR_CONTACTS = 2
        const val PAYLOAD_BYTES = 256
        const val BYTE_MASK = 0xFF
        const val BROADCAST = -1

        // The slowest measured 2M GATT throughput (bake-off smoke, U(62, 89) KB/s).
        const val LINK_CAPACITY_BYTES_PER_SECOND = 62_000.0
    }
}

private fun exponential(
    random: Random,
    mean: Double,
): Long = (-ln(1 - random.nextDouble()) * mean).toLong()

private const val SEED_TIMEOUT_MINUTES = 80
