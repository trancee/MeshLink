package io.meshlink.transfer

/**
 * Determines effective chunk payload size based on power mode and MTU.
 */
class ChunkSizePolicy(
    private val headerSize: Int = 21, // WireCodec.CHUNK_HEADER_SIZE
    private val performanceMaxPayload: Int = 8192,
    private val balancedMaxPayload: Int = 4096,
    private val powerSaverMaxPayload: Int = 1024,
) {
    enum class PowerMode { PERFORMANCE, BALANCED, POWER_SAVER }

    /**
     * Effective chunk payload size for the given power mode and negotiated MTU.
     * Returns min(modeMax, mtu - headerSize), at least 1.
     */
    fun effectiveChunkSize(mode: PowerMode, mtu: Int): Int {
        val modeMax = maxPayloadForMode(mode)
        val mtuPayload = mtu - headerSize
        return maxOf(1, minOf(modeMax, mtuPayload))
    }

    /**
     * Maximum payload size for the given power mode (ignoring MTU).
     */
    fun maxPayloadForMode(mode: PowerMode): Int = when (mode) {
        PowerMode.PERFORMANCE -> performanceMaxPayload
        PowerMode.BALANCED -> balancedMaxPayload
        PowerMode.POWER_SAVER -> powerSaverMaxPayload
    }
}
