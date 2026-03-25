package io.meshlink.model

data class Message(
    val senderId: ByteArray,
    val payload: ByteArray,
)
