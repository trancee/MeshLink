package ch.trancee.meshlink.internal

import ch.trancee.meshlink.Preset
import ch.trancee.meshlink.SecurityProfile
import ch.trancee.meshlink.SendResult
import ch.trancee.meshlink.meshLinkConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.math.PI
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

// PROTOTYPE (ticket 53, throwaway): which per-link token bucket (R/s, burst B) keeps EmergencyBroadcast's
// broadcast priority useful for honest broadcasts while one member floods broadcasts?
// Run: PROTO_VARIANTS=... ./gradlew :meshlink-testing:testAndroidHostTest --tests '*BroadcastFloodPrototype*'
@OptIn(ExperimentalCoroutinesApi::class)
class BroadcastFloodPrototype {
    data class Variant(val name: String, val priority: Boolean, val attack: Boolean, val rate: Double, val burst: Double, val drop: Boolean = false)

    @Test
    fun run() {
        val cells = (System.getenv("PROTO_CELLS") ?: "low,medium").split(",")
        val seeds = (System.getenv("PROTO_SEEDS") ?: "1").split(",").map { it.toLong() }
        val all = listOf(
            Variant("fifo-calm", false, false, INF, 0.0),
            Variant("prio-calm", true, false, INF, 0.0),
            Variant("fifo-flood", false, true, INF, 0.0),
            Variant("prio-flood", true, true, INF, 0.0),
            Variant("prio-flood-R0.5B3", true, true, 0.5, 3.0),
            Variant("prio-flood-R1B5", true, true, 1.0, 5.0),
            Variant("prio-flood-R2B5", true, true, 2.0, 5.0),
            Variant("prio-flood-R5B10", true, true, 5.0, 10.0),
            Variant("prio-calm-R1B5", true, false, 1.0, 5.0),
            Variant("drop-flood-R1B5", true, true, 1.0, 5.0, drop = true),
            Variant("fifo-busy", false, false, INF, 0.0),
            Variant("prio-busy", true, false, INF, 0.0),
            Variant("drop-flood-R2B5", true, true, 2.0, 5.0, drop = true),
            Variant("drop-flood-R2B10", true, true, 2.0, 10.0, drop = true),
            Variant("fifodrop-flood-R2B5", false, true, 2.0, 5.0, drop = true),
            Variant("drop-calm-R1B5", true, false, 1.0, 5.0, drop = true),
            Variant("drop-burst-R1B5", true, false, 1.0, 5.0, drop = true),
        )
        val pick = System.getenv("PROTO_VARIANTS")?.split(",")
        val variants = if (pick == null) all else all.filter { it.name in pick }
        println("PROTO cell seed variant | uni ok p50 p95 | honest-bc cover p50 p95 | attack cover | wall")
        for (cell in cells) for (seed in seeds) for (v in variants) {
            val started = TimeSource.Monotonic.markNow()
            val r = run(cell, seed, v)
            println("PROTO $cell $seed ${v.name} | $r | ${started.elapsedNow().inWholeSeconds}s")
        }
    }

    private fun run(cell: String, seed: Long, v: Variant): String {
        var out = ""
        FloodPrototype.reset()
        FloodPrototype.ratePerSecond = v.rate
        FloodPrototype.burst = v.burst
        FloodPrototype.drop = v.drop
        runTest(timeout = 60.minutes) {
            FloodPrototype.nowMillis = { testScheduler.currentTime }
            val meanPeers = if (cell == "low") 6.0 else 25.0
            val random = Random(seed * 7919)
            val side = sqrt(N * PI * VirtualAir.DEFAULT_RANGE * VirtualAir.DEFAULT_RANGE / meanPeers)
            val pos = List(N) { random.nextDouble(side) to random.nextDouble(side) }
            val config = meshLinkConfig(Preset.EmergencyBroadcast, SecurityProfile.Unsecured) {
                meshId = MESH
                broadcastPriority = v.priority
            }.build()
            val mesh = VirtualMesh(this, seed, pos, recording = false, config = config)
            mesh.air.airtimeBytesPerSecond = AIRTIME
            mesh.start()
            testScheduler.advanceTimeBy(SETTLE_MILLIS)
            val comp = mesh.components()
            val big = comp.toList().groupingBy { it }.eachCount().maxBy { it.value }.key
            val members = (0 until N).filter { comp[it] == big }
            val attacker = members[0]
            val honest = members[members.size / 2]
            val sentAt = mutableMapOf<Int, Long>()
            val uniTo = mutableMapOf<Int, Int>()
            val honestSent = mutableMapOf<Int, Pair<Int, Long>>()
            var seq = 0
            fun payload(kind: Int, id: Int, size: Int) = ByteArray(size).also {
                it[0] = kind.toByte(); it[1] = (id shr 16).toByte(); it[2] = (id shr 8).toByte(); it[3] = id.toByte()
            }
            val traffic = backgroundScope.launch {
                if (v.attack) launch {
                    var a = 0
                    while (true) {
                        mesh.nodes[attacker].broadcast(payload(2, a++, ATTACK_BYTES))
                        delay(1_000L / ATTACK_PER_SECOND)
                    }
                }
                if ("burst" in v.name) {
                    members.forEach { m ->
                        repeat(2) { k ->
                            launch {
                                delay(random.nextLong(10_000))
                                val id = m * 100 + k
                                if (mesh.nodes[m].broadcast(payload(1, id, HONEST_BYTES)) is SendResult.Accepted) {
                                    honestSent[id] = m to testScheduler.currentTime
                                }
                            }
                        }
                    }
                } else launch {
                    var h = 0
                    while (true) {
                        delay(HONEST_EVERY_MILLIS)
                        val id = h++
                        if (mesh.nodes[honest].broadcast(payload(1, id, HONEST_BYTES)) is SendResult.Accepted) {
                            honestSent[id] = honest to testScheduler.currentTime
                        }
                    }
                }
                while (true) {
                    delay(if ("busy" in v.name) BUSY_EVERY_MILLIS else UNICAST_EVERY_MILLIS)
                    val from = members.filter { it != attacker }.random(random)
                    val to = members.filter { it != from && it != attacker }.random(random)
                    val id = seq++
                    val dest = mesh.idOf(to) ?: continue
                    if (mesh.nodes[from].send(dest, payload(0, id, if ("busy" in v.name) BUSY_BYTES else UNICAST_BYTES)) is SendResult.Accepted) {
                        sentAt[id] = testScheduler.currentTime
                        uniTo[id] = to
                    }
                }
            }
            testScheduler.advanceTimeBy(TRAFFIC_MILLIS)
            traffic.cancel()
            testScheduler.advanceTimeBy(DRAIN_MILLIS)

            fun idOf(p: ByteArray) = ((p[1].toInt() and 0xff) shl 16) or ((p[2].toInt() and 0xff) shl 8) or (p[3].toInt() and 0xff)
            val uniLat = mutableListOf<Long>()
            sentAt.forEach { (id, at) ->
                mesh.inboxes[uniTo.getValue(id)].firstOrNull { !it.message.broadcast && it.message.payload[0] == 0.toByte() && idOf(it.message.payload) == id }
                    ?.let { uniLat += it.atMillis - at }
            }
            val bcLat = mutableListOf<Long>()
            var bcCover = 0
            honestSent.forEach { (id, sent) ->
                val (origin, at) = sent
                members.filter { it != origin }.forEach { m ->
                    mesh.inboxes[m].firstOrNull { it.message.broadcast && it.message.payload[0] == 1.toByte() && idOf(it.message.payload) == id }
                        ?.let { bcCover++; bcLat += it.atMillis - at }
                }
            }
            val attackCover = members.filter { it != attacker }.sumOf { m -> mesh.inboxes[m].count { it.message.broadcast && it.message.payload[0] == 2.toByte() } }
            val attackSent = mesh.inboxes.maxOf { box -> box.filter { it.message.payload[0] == 2.toByte() }.maxOfOrNull { idOf(it.message.payload) } ?: -1 } + 1
            fun p(l: List<Long>, q: Double) = if (l.isEmpty()) "-" else l.sorted()[((l.size - 1) * q).toInt()].toString()
            val honestTargets = honestSent.size * (members.size - 1)
            out = "members=${members.size} | %d/%d %s %s | %.3f %s %s | %.3f".format(
                uniLat.size, sentAt.size, p(uniLat, 0.5), p(uniLat, 0.95),
                bcCover.toDouble() / maxOf(1, honestTargets), p(bcLat, 0.5), p(bcLat, 0.95),
                attackCover.toDouble() / maxOf(1, attackSent * (members.size - 1)),
            )
            mesh.close()
        }
        return out
    }

    private companion object {
        const val INF = Double.POSITIVE_INFINITY
        const val N = 30
        const val AIRTIME = 10_000
        const val SETTLE_MILLIS = 90_000L
        const val TRAFFIC_MILLIS = 180_000L
        const val DRAIN_MILLIS = 120_000L
        const val ATTACK_PER_SECOND = 20
        const val ATTACK_BYTES = 900
        const val HONEST_EVERY_MILLIS = 20_000L
        const val HONEST_BYTES = 200
        const val UNICAST_EVERY_MILLIS = 1_000L
        const val UNICAST_BYTES = 200
        const val BUSY_EVERY_MILLIS = 250L
        const val BUSY_BYTES = 4_000
    }
}
