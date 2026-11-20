package ch.trancee.meshlink.internal

// An end-to-end layer that owns [own], carries [kinds], takes MESSAGE frames while [accepting], and records them
// and found routes.
internal class FakeEndpoint(
    private val own: ByteArray?,
) : Endpoint {
    val delivered = mutableListOf<ByteArray>()
    val found = mutableListOf<ByteArray>()
    var accepting = true
    var kinds = setOf(Container.UNSECURED)

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

    override fun tag(): ByteArray? = own

    override fun found(tag: ByteArray) {
        found += tag
    }

    override fun carries(kind: Int): Boolean = kind in kinds
}
