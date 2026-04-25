package ch.trancee.meshlink.benchmark

import ch.trancee.meshlink.routing.DedupSet
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State

/**
 * Benchmarks for the [DedupSet] hot path.
 *
 * - [insertAndLookup10K] — [DedupSet.add] + [DedupSet.isDuplicate] at 10 000-entry capacity.
 *   Eviction fires on [add] when the set is full.
 * - [insertAndLookup25K] — same on a 25 000-entry set (matches [RoutingConfig.dedupCapacity]).
 * - [eviction] — isolated cost of inserting into a full 10 K set (triggers LRU eviction only;
 *   no [isDuplicate] call in the hot path).
 *
 * Clock is pinned to a fixed timestamp so entries never TTL-expire during the benchmark, isolating
 * the LRU eviction path from the TTL eviction path.
 */
@State(Scope.Benchmark)
internal class DedupBenchmark {

    private lateinit var dedup10k: DedupSet
    private lateinit var dedup25k: DedupSet

    /**
     * Keys used by [insertAndLookup10K] / [insertAndLookup25K] / [eviction]. Distinct from the
     * pre-fill keys so each call to [add] inserts a fresh entry and eviction fires on every call.
     */
    private lateinit var insertKeys: Array<ByteArray>

    private var insertIdx: Int = 0

    @Setup
    fun setup() {
        // Pin clock far in the future so no entry ever TTL-expires during the benchmark.
        val farFutureClock = Long.MAX_VALUE / 2
        dedup10k = DedupSet(capacity = 10_000, ttlMillis = Long.MAX_VALUE / 4) { farFutureClock }
        dedup25k = DedupSet(capacity = 25_000, ttlMillis = Long.MAX_VALUE / 4) { farFutureClock }

        // Pre-fill both sets to capacity so every insert triggers eviction.
        repeat(10_000) { i ->
            dedup10k.add(ByteArray(16) { b -> ((i * 17 + b) and 0xFF).toByte() })
        }
        repeat(25_000) { i ->
            dedup25k.add(ByteArray(16) { b -> ((i * 17 + b) and 0xFF).toByte() })
        }

        // 10 000 distinct "new" keys — different hash domain from pre-fill keys.
        insertKeys =
            Array(10_000) { i ->
                ByteArray(16) { b -> ((i * 31 + b + 100_000) and 0xFF).toByte() }
            }
    }

    /**
     * Insert a fresh message ID into the 10 K set (triggers eviction because the set is at
     * capacity) and immediately check [isDuplicate] on the same key (exercises LRU re-insert on
     * hit).
     */
    @Benchmark
    fun insertAndLookup10K(): Boolean {
        val key = insertKeys[insertIdx % 10_000]
        insertIdx++
        dedup10k.add(key)
        return dedup10k.isDuplicate(key)
    }

    /**
     * Same as [insertAndLookup10K] but on the 25 K set which matches the production
     * [RoutingConfig.dedupCapacity] default.
     */
    @Benchmark
    fun insertAndLookup25K(): Boolean {
        val key = insertKeys[insertIdx % 10_000]
        insertIdx++
        dedup25k.add(key)
        return dedup25k.isDuplicate(key)
    }

    /**
     * Isolated eviction cost: insert a fresh entry into the full 10 K set without a subsequent
     * [isDuplicate] call. Measures only the LRU-evict-then-insert path.
     */
    @Benchmark
    fun eviction() {
        val key = insertKeys[insertIdx % 10_000]
        insertIdx++
        dedup10k.add(key)
    }
}
