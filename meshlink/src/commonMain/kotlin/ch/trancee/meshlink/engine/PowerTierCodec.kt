package ch.trancee.meshlink.engine

import ch.trancee.meshlink.power.PowerTier

/**
 * Encodes and decodes [PowerTier] values as a single byte for compact wire representation.
 *
 * Encoding: 0x00 = PERFORMANCE, 0x01 = BALANCED, 0x02 = POWER_SAVER. Decoding falls back to
 * BALANCED on an empty array or an unknown byte value.
 */
internal object PowerTierCodec {

    fun encode(tier: PowerTier): ByteArray =
        when (tier) {
            PowerTier.PERFORMANCE -> byteArrayOf(0x00)
            PowerTier.BALANCED -> byteArrayOf(0x01)
            PowerTier.POWER_SAVER -> byteArrayOf(0x02)
        }

    fun decode(data: ByteArray): PowerTier {
        if (data.isEmpty()) return PowerTier.BALANCED
        return when (data[0]) {
            0x00.toByte() -> PowerTier.PERFORMANCE
            0x01.toByte() -> PowerTier.BALANCED
            0x02.toByte() -> PowerTier.POWER_SAVER
            else -> PowerTier.BALANCED
        }
    }
}
