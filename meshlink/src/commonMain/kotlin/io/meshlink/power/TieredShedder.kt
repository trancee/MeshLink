package io.meshlink.power

enum class MemoryPressure { MODERATE, HIGH, CRITICAL }

enum class ShedAction { RELAY_BUFFERS_CLEARED, DEDUP_TRIMMED, CONNECTIONS_DROPPED }

data class ShedResult(val action: ShedAction, val count: Int)

class TieredShedder(
    private val relayBufferCount: Int,
    private val dedupEntries: Int,
    private val connectionCount: Int,
) {
    fun shed(level: MemoryPressure): List<ShedResult> = buildList {
        // Tier 1: always shed relay buffers
        add(ShedResult(ShedAction.RELAY_BUFFERS_CLEARED, relayBufferCount))
        if (level == MemoryPressure.MODERATE) return@buildList

        // Tier 2: also trim dedup
        add(ShedResult(ShedAction.DEDUP_TRIMMED, dedupEntries))
        if (level == MemoryPressure.HIGH) return@buildList

        // Tier 3: also drop connections
        add(ShedResult(ShedAction.CONNECTIONS_DROPPED, connectionCount))
    }
}
