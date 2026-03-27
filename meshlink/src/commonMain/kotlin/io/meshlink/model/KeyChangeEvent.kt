package io.meshlink.model

data class KeyChangeEvent(
    val peerId: ByteArray,
    val previousKey: ByteArray,
    val newKey: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KeyChangeEvent) return false
        return peerId.contentEquals(other.peerId) &&
            previousKey.contentEquals(other.previousKey) &&
            newKey.contentEquals(other.newKey)
    }

    override fun hashCode(): Int {
        var result = peerId.contentHashCode()
        result = 31 * result + previousKey.contentHashCode()
        result = 31 * result + newKey.contentHashCode()
        return result
    }
}
