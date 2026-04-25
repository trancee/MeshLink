package ch.trancee.meshlink.benchmark

import ch.trancee.meshlink.routing.RouteEntry
import ch.trancee.meshlink.routing.RoutingConfig
import ch.trancee.meshlink.routing.RoutingEngine
import ch.trancee.meshlink.routing.RoutingTable
import ch.trancee.meshlink.wire.Update
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * Benchmarks for the Babel routing hot path.
 *
 * - [routeLookup500] / [routeLookup2000] — next-hop resolution in tables of different sizes.
 * - [feasibilityCheck] — processUpdate feasibility gate via [RoutingEngine] (covers install and
 *   FD check paths in proportion proportional to 1024 distinct destinations).
 * - [routeDigest500] — O(1) XOR-fold digest read on a 500-entry table.
 *
 * [RoutingEngine] is constructed with a passive [CoroutineScope] — [startTimers] is intentionally
 * not called so no background coroutines are launched during the benchmark.
 */
@State(Scope.Benchmark)
internal class RoutingBenchmark {

    private lateinit var table500: RoutingTable
    private lateinit var table2000: RoutingTable
    private lateinit var destinations500: Array<ByteArray>
    private lateinit var destinations2000: Array<ByteArray>
    private lateinit var engine: RoutingEngine

    /**
     * Pre-built Update messages for 1 024 distinct destinations. The first pass (seqNo=100u)
     * exercises the "no FD yet → unconditional accept" path; subsequent passes exercise the
     * "same seqNo / cost not lower → reject" steady-state path.
     */
    private lateinit var feasUpdates: Array<Update>
    private val feasPeer: ByteArray = ByteArray(12) { 0x55 }
    private var feasIdx: Int = 0
    private var lookupIdx: Int = 0

    @Setup
    fun setup() {
        val now = System.currentTimeMillis()
        val expiry = now + 300_000L

        // ── 500-entry table ────────────────────────────────────────────────────
        table500 = RoutingTable { expiry }
        destinations500 =
            Array(500) { i ->
                ByteArray(12).also { bytes ->
                    bytes[0] = (i shr 8).toByte()
                    bytes[1] = (i and 0xFF).toByte()
                }
            }
        destinations500.forEachIndexed { i, dest ->
            table500.install(makeRoute(dest, seqNo = i.toUShort(), expiry = expiry))
        }

        // ── 2000-entry table ───────────────────────────────────────────────────
        table2000 = RoutingTable { expiry }
        destinations2000 =
            Array(2000) { i ->
                ByteArray(12).also { bytes ->
                    bytes[0] = (i shr 8).toByte()
                    bytes[1] = (i and 0xFF).toByte()
                    bytes[2] = 0x80.toByte()
                }
            }
        destinations2000.forEachIndexed { i, dest ->
            table2000.install(makeRoute(dest, seqNo = i.toUShort(), expiry = expiry))
        }

        // ── RoutingEngine (no timers) ──────────────────────────────────────────
        engine =
            RoutingEngine(
                routingTable = RoutingTable { System.currentTimeMillis() + 300_000L },
                localPeerId = ByteArray(12) { 0x01 },
                localEdPublicKey = ByteArray(32) { it.toByte() },
                localDhPublicKey = ByteArray(32) { (it + 32).toByte() },
                scope = CoroutineScope(Job()),
                clock = { System.currentTimeMillis() },
                config = RoutingConfig(),
            )

        // 1 024 distinct destinations — all install unconditionally on first pass, then hit the
        // "same seqNo / reject" steady-state path on subsequent passes.
        feasUpdates =
            Array(1024) { i ->
                Update(
                    destination =
                        ByteArray(12).also { bytes ->
                            bytes[0] = (i shr 8).toByte()
                            bytes[1] = (i and 0xFF).toByte()
                            bytes[2] = 0xFE.toByte()
                        },
                    metric = 100u,
                    seqNo = 100u,
                    ed25519PublicKey = ByteArray(32) { it.toByte() },
                    x25519PublicKey = ByteArray(32) { (it + 1).toByte() },
                )
            }
    }

    private fun makeRoute(dest: ByteArray, seqNo: UShort, expiry: Long): RouteEntry =
        RouteEntry(
            destination = dest,
            nextHop = ByteArray(12) { 0x02 },
            metric = 1.0,
            seqNo = seqNo,
            feasibilityDistance = 1.0,
            expiresAt = expiry,
            ed25519PublicKey = ByteArray(32) { it.toByte() },
            x25519PublicKey = ByteArray(32) { (it + 1).toByte() },
        )

    /** Next-hop resolution in a 500-entry table. Cycles through all destinations. */
    @Benchmark
    fun routeLookup500(): ByteArray? {
        val idx = lookupIdx % 500
        lookupIdx++
        return table500.lookupNextHop(destinations500[idx])
    }

    /** Next-hop resolution in a 2 000-entry table. */
    @Benchmark
    fun routeLookup2000(): ByteArray? {
        val idx = lookupIdx % 2000
        lookupIdx++
        return table2000.lookupNextHop(destinations2000[idx])
    }

    /**
     * Babel feasibility gate: [RoutingEngine.processUpdate]. First 1 024 invocations exercise the
     * "no FD yet" accept path; subsequent invocations exercise the "same seqNo, reject" path.
     */
    @Benchmark
    fun feasibilityCheck(): Boolean {
        val idx = feasIdx % 1024
        feasIdx++
        return engine.processUpdate(feasPeer, feasUpdates[idx], 1.0)
    }

    /**
     * O(1) route digest read — used by Babel differential Hello to detect table changes. Returns
     * the digest cast to [Int] to avoid JMH name-mangling for inline value class return types.
     */
    @Benchmark fun routeDigest500(): Int = table500.routeDigest().toInt()
}
