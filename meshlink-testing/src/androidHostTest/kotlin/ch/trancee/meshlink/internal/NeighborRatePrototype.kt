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

// PROTOTYPE (ticket 74, throwaway): does a per-neighbor limit on relayed floods (drop over budget) stop one
// flooding member without costing honest traffic, and is per-origin keying for broadcasts better than per link?
// Run: PROTO_CELLS=low PROTO_SEEDS=1 PROTO_SCENARIOS=flood-bc PROTO_LIMITERS=none,link \
//   ./gradlew :meshlink-testing:testAndroidHostTest --tests '*NeighborRatePrototype*'
@OptIn(ExperimentalCoroutinesApi::class)
class NeighborRatePrototype {
    @Test
    fun run() {
        val cells = (System.getenv("PROTO_CELLS") ?: "low,medium").split(",")
        val seeds = (System.getenv("PROTO_SEEDS") ?: "1").split(",").map { it.toLong() }
        val scenarios = (System.getenv("PROTO_SCENARIOS") ?: "calm,burst,flood-bc,flood-rot,flood-uni").split(",")
        val limiters = (System.getenv("PROTO_LIMITERS") ?: "none,link,origin,originlink").split(",")
        println("PROTO cell seed scenario limiter | members | uni ok/sent p50 p95 | honest-bc cover p50 p95 | attack-bc cover | drops | wall")
        for (cell in cells) for (seed in seeds) for (s in scenarios) for (l in limiters) {
            val started = TimeSource.Monotonic.markNow()
            val r = run(cell, seed, s, l)
            println("PROTO $cell $seed $s $l | $r | ${started.elapsedNow().inWholeSeconds}s")
        }
    }

    private fun limiter(name: String) {
        RateProto.reset()
        RateProto.rotate = false
        RateProto.broadcastKey = if (name == "none") "none" else name
        val limited = name != "none"
        RateProto.unicastRate = if (limited) UNI_RATE else INF
        RateProto.unicastBurst = UNI_BURST
        RateProto.requestRate = if (limited) REQ_RATE else INF
        RateProto.requestBurst = REQ_BURST
        System.getenv("PROTO_LINK")?.split("/")?.let { RateProto.linkRate = it[0].toDouble(); RateProto.linkBurst = it[1].toDouble() }
        System.getenv("PROTO_ORIGIN")?.split("/")?.let { RateProto.originRate = it[0].toDouble(); RateProto.originBurst = it[1].toDouble() }
        System.getenv("PROTO_AGG")?.split("/")?.let { RateProto.aggregateRate = it[0].toDouble(); RateProto.aggregateBurst = it[1].toDouble() }
        System.getenv("PROTO_UNI")?.split("/")?.let { if (limited) RateProto.unicastRate = it[0].toDouble(); RateProto.unicastBurst = it[1].toDouble() }
    }

    private fun run(cell: String, seed: Long, scenario: String, lim: String): String {
        var out = ""
        limiter(lim)
        RateProto.rotate = scenario == "flood-rot"
        runTest(timeout = 60.minutes) {
            RateProto.nowMillis = { testScheduler.currentTime }
            val meanPeers = if (cell == "low") 6.0 else 25.0
            val random = Random(seed * 7919)
            val side = sqrt(N * PI * VirtualAir.DEFAULT_RANGE * VirtualAir.DEFAULT_RANGE / meanPeers)
            val pos = List(N) { random.nextDouble(side) to random.nextDouble(side) }
            val config = meshLinkConfig(Preset.EmergencyBroadcast, SecurityProfile.Unsecured) { meshId = MESH }.build()
            val mesh = VirtualMesh(this, seed, pos, recording = false, custom = config)
            mesh.air.airtimeBytesPerSecond = AIRTIME
            mesh.start()
            testScheduler.advanceTimeBy(SETTLE_MILLIS)
            RateProto.drops.clear()
            val comp = mesh.components()
            val big = comp.toList().groupingBy { it }.eachCount().maxBy { it.value }.key
            val members = (0 until N).filter { comp[it] == big }
            val attacker = members[0]
            val honest = members[members.size / 2]
            val sentAt = mutableMapOf<Int, Long>()
            val uniTo = mutableMapOf<Int, Int>()
            val honestSent = mutableMapOf<Int, Pair<Int, Long>>()
            var seq = 0
            var attackSent = 0
            fun payload(kind: Int, id: Int, size: Int) = ByteArray(size).also {
                it[0] = kind.toByte(); it[1] = (id shr 16).toByte(); it[2] = (id shr 8).toByte(); it[3] = id.toByte()
            }
            val traffic = backgroundScope.launch {
                if (scenario == "flood-bc" || scenario == "flood-rot") launch {
                    while (true) {
                        mesh.nodes[attacker].broadcast(payload(2, attackSent++, ATTACK_BYTES))
                        delay(1_000L / ATTACK_PER_SECOND)
                    }
                }
                if (scenario == "flood-uni") launch {
                    var a = 0
                    while (true) {
                        mesh.nodes[attacker].send(RateProto.nodeId(random.nextBytes(32)), payload(3, a++, UNICAST_BYTES))
                        delay(1_000L / ATTACK_PER_SECOND)
                    }
                }
                if (scenario == "burst") {
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
                    delay(UNICAST_EVERY_MILLIS)
                    val from = members.filter { it != attacker }.random(random)
                    val to = members.filter { it != from && it != attacker }.random(random)
                    val id = seq++
                    val dest = mesh.idOf(to) ?: continue
                    if (mesh.nodes[from].send(dest, payload(0, id, UNICAST_BYTES)) is SendResult.Accepted) {
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
            fun p(l: List<Long>, q: Double) = if (l.isEmpty()) "-" else l.sorted()[((l.size - 1) * q).toInt()].toString()
            val honestTargets = honestSent.size * (members.size - 1)
            out = "%d | %d/%d %s %s | %.3f %s %s | %.3f | %s".format(
                members.size, uniLat.size, sentAt.size, p(uniLat, 0.5), p(uniLat, 0.95),
                bcCover.toDouble() / maxOf(1, honestTargets), p(bcLat, 0.5), p(bcLat, 0.95),
                attackCover.toDouble() / maxOf(1, attackSent * (members.size - 1)),
                RateProto.drops.toSortedMap(),
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
        const val UNI_RATE = 10.0
        const val UNI_BURST = 40.0
        const val REQ_RATE = 5.0
        const val REQ_BURST = 20.0
    }
}
