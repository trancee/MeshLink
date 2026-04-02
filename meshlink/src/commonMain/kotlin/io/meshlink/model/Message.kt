package io.meshlink.model

data class Message(
    val senderId: ByteArray,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Message) return false
        return senderId.contentEquals(other.senderId) && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int = 31 * senderId.contentHashCode() + payload.contentHashCode()
}
