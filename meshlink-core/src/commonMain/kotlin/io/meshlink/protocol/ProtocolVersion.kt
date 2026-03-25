package io.meshlink.protocol

data class ProtocolVersion(val major: Int, val minor: Int) : Comparable<ProtocolVersion> {
    override fun compareTo(other: ProtocolVersion): Int =
        compareValuesBy(this, other, { it.major }, { it.minor })

    fun negotiate(remote: ProtocolVersion): ProtocolVersion? {
        if (kotlin.math.abs(major - remote.major) > 1) return null
        return minOf(this, remote)
    }
}
