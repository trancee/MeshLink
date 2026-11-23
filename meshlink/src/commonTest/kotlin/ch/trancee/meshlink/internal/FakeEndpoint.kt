package ch.trancee.meshlink.internal

// An end-to-end layer that owns [own], carries [kinds], takes MESSAGE frames while [accepting], and records them,
// later copies, delivery and read receipts and found routes.
internal class FakeEndpoint(
    private val own: ByteArray?,
) : Endpoint,
    Acknowledger {
    val delivered = mutableListOf<ByteArray>()
    val found = mutableListOf<ByteArray>()
    val repeated = mutableListOf<ByteArray>()
    val acknowledged = mutableListOf<ByteArray>()
    val read = mutableListOf<ByteArray>()
    var accepting = true
    var kinds = setOf(Container.UNSECURED)

    override val acknowledger: Acknowledger = this

    override fun owns(
        tag: ByteArray,
        nowSeconds: Long,
    ): Boolean = own != null && tag.contentEquals(own)

    override fun deliver(
        frame: ByteArray,
        nowSeconds: Long,
    ): Boolean {
        delivered += frame
        return accepting
    }

    override fun repeated(
        frame: ByteArray,
        nowSeconds: Long,
    ) {
        repeated += frame
    }

    override fun acknowledged(
        frame: ByteArray,
        nowSeconds: Long,
    ) {
        acknowledged += frame
    }

    override fun read(
        frame: ByteArray,
        nowSeconds: Long,
    ) {
        read += frame
    }

    override fun tag(): ByteArray? = own

    override fun found(tag: ByteArray) {
        found += tag
    }

    override fun carries(kind: Int): Boolean = kind in kinds
}
