package io.meshlink.config

data class MeshLinkConfig(
    val maxMessageSize: Int = 100_000,
    val bufferCapacity: Int = 1_048_576,
    val mtu: Int = 185,
)

fun meshLinkConfig(block: MeshLinkConfigBuilder.() -> Unit = {}): MeshLinkConfig =
    MeshLinkConfigBuilder().apply(block).build()

class MeshLinkConfigBuilder {
    var maxMessageSize: Int = 100_000
    var bufferCapacity: Int = 1_048_576
    var mtu: Int = 185

    fun build(): MeshLinkConfig = MeshLinkConfig(
        maxMessageSize = maxMessageSize,
        bufferCapacity = bufferCapacity,
        mtu = mtu,
    )
}
