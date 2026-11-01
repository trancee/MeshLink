package bakeoff

class ByteWriter(initialCapacity: Int = 64) {
    private var buffer = ByteArray(initialCapacity)
    private var size = 0

    private fun ensure(extra: Int) {
        if (size + extra > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + extra))
    }

    fun byte(value: Int): ByteWriter {
        ensure(1)
        buffer[size++] = value.toByte()
        return this
    }

    fun short(value: Int): ByteWriter = byte(value ushr 8).byte(value)

    fun int(value: Int): ByteWriter = byte(value ushr 24).byte(value ushr 16).byte(value ushr 8).byte(value)

    fun long(value: Long): ByteWriter = int((value ushr 32).toInt()).int(value.toInt())

    fun bytes(value: ByteArray): ByteWriter {
        ensure(value.size)
        value.copyInto(buffer, size)
        size += value.size
        return this
    }

    fun toByteArray(): ByteArray = buffer.copyOf(size)
}

class ByteReader(private val bytes: ByteArray) {
    private var position = 0

    private fun next(): Int {
        if (position >= bytes.size) throw ProtocolException("truncated at $position")
        return bytes[position++].toInt() and 0xff
    }

    fun short(): Int = (next() shl 8) or next()

    fun int(): Int = (next() shl 24) or (next() shl 16) or (next() shl 8) or next()

    fun long(): Long = (int().toLong() shl 32) or (int().toLong() and 0xffffffffL)

    fun rest(): ByteArray = bytes.copyOfRange(position, bytes.size).also { position = bytes.size }
}

class Message(val type: MessageType, val payload: ByteArray)

const val MESSAGE_HEADER_SIZE: Int = 5
private const val MAX_MESSAGE_SIZE = 4 shl 20

/** Stream framing shared by GATT and CoC: type (1) + big-endian length (4) + payload. */
fun encodeMessage(type: MessageType, payload: ByteArray): ByteArray =
    ByteWriter(MESSAGE_HEADER_SIZE + payload.size).byte(type.code).int(payload.size).bytes(payload).toByteArray()

class Deframer {
    private var buffer = ByteArray(8192)
    private var start = 0
    private var end = 0

    fun feed(bytes: ByteArray): List<Message> {
        append(bytes)
        val messages = ArrayList<Message>()
        while (end - start >= MESSAGE_HEADER_SIZE) {
            val length = ByteReader(buffer.copyOfRange(start + 1, start + MESSAGE_HEADER_SIZE)).int()
            if (length < 0 || length > MAX_MESSAGE_SIZE) throw ProtocolException("bad message length $length")
            if (end - start < MESSAGE_HEADER_SIZE + length) break
            val type = MessageType.fromCode(buffer[start].toInt() and 0xff)
            val payloadStart = start + MESSAGE_HEADER_SIZE
            messages += Message(type, buffer.copyOfRange(payloadStart, payloadStart + length))
            start = payloadStart + length
        }
        return messages
    }

    private fun append(bytes: ByteArray) {
        if (end + bytes.size > buffer.size) {
            val live = end - start
            val target = if (live + bytes.size > buffer.size) ByteArray(maxOf(buffer.size * 2, live + bytes.size)) else buffer
            buffer.copyInto(target, 0, start, end)
            buffer = target
            start = 0
            end = live
        }
        bytes.copyInto(buffer, end)
        end += bytes.size
    }
}
