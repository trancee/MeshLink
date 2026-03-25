package io.meshlink.config

data class MeshLinkConfig(
    val maxMessageSize: Int = 100_000,
    val bufferCapacity: Int = 1_048_576,
    val mtu: Int = 185,
) {
    fun validate(): List<String> {
        val violations = mutableListOf<String>()
        if (mtu <= 21) violations.add("mtu must be > 21 (chunk header size)")
        if (maxMessageSize > bufferCapacity) violations.add("maxMessageSize ($maxMessageSize) exceeds bufferCapacity ($bufferCapacity)")
        if (maxMessageSize <= 0) violations.add("maxMessageSize must be positive")
        if (bufferCapacity <= 0) violations.add("bufferCapacity must be positive")
        if (mtu > maxMessageSize) violations.add("mtu ($mtu) exceeds maxMessageSize ($maxMessageSize)")
        return violations
    }

    companion object {
        fun chatOptimized(overrides: MeshLinkConfigBuilder.() -> Unit = {}): MeshLinkConfig =
            MeshLinkConfigBuilder(maxMessageSize = 10_000, bufferCapacity = 524_288)
                .apply(overrides).build()

        fun fileTransferOptimized(overrides: MeshLinkConfigBuilder.() -> Unit = {}): MeshLinkConfig =
            MeshLinkConfigBuilder(maxMessageSize = 100_000, bufferCapacity = 2_097_152)
                .apply(overrides).build()

        fun powerOptimized(overrides: MeshLinkConfigBuilder.() -> Unit = {}): MeshLinkConfig =
            MeshLinkConfigBuilder(maxMessageSize = 10_000, bufferCapacity = 262_144)
                .apply(overrides).build()
    }
}

fun meshLinkConfig(block: MeshLinkConfigBuilder.() -> Unit = {}): MeshLinkConfig =
    MeshLinkConfigBuilder().apply(block).build()

class MeshLinkConfigBuilder(
    var maxMessageSize: Int = 100_000,
    var bufferCapacity: Int = 1_048_576,
    var mtu: Int = 185,
) {

    fun build(): MeshLinkConfig = MeshLinkConfig(
        maxMessageSize = maxMessageSize,
        bufferCapacity = bufferCapacity,
        mtu = mtu,
    )
}
