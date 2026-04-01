package io.meshlink.wire

/**
 * A single TLV (Type-Length-Value) extension entry.
 *
 * Wire layout: [tag: 1 byte][length: 2 bytes UShort LE][value: length bytes]
 *
 * Tags 0x00–0x7F are reserved for protocol use.
 * Tags 0x80–0xFF are available for application use.
 */
data class TlvEntry(
    val tag: UByte,
    val value: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TlvEntry) return false
        return tag == other.tag && value.contentEquals(other.value)
    }

    override fun hashCode(): Int = 31 * tag.hashCode() + value.contentHashCode()
}

internal object TlvCodec {

    private const val TLV_HEADER_SIZE = 3 // 1 tag + 2 length
    internal const val EXTENSION_LENGTH_SIZE = 2 // UShort LE prefix

    /**
     * Encodes a list of TLV entries into a byte array including the
     * 2-byte extension-length prefix.
     *
     * Returns a 2-byte array [0x00, 0x00] when [entries] is empty.
     */
    fun encode(entries: List<TlvEntry>): ByteArray {
        val bodySize = entries.sumOf { TLV_HEADER_SIZE + it.value.size }
        val result = ByteArray(EXTENSION_LENGTH_SIZE + bodySize)
        putUShortLE(result, 0, bodySize.toUShort())
        var offset = EXTENSION_LENGTH_SIZE
        for (entry in entries) {
            result[offset] = entry.tag.toByte()
            putUShortLE(result, offset + 1, entry.value.size.toUShort())
            entry.value.copyInto(result, offset + TLV_HEADER_SIZE)
            offset += TLV_HEADER_SIZE + entry.value.size
        }
        return result
    }

    /**
     * Decodes TLV entries starting at [offset] in [data].
     *
     * Reads the 2-byte extension-length prefix, then parses individual
     * TLV entries. Unknown tags are preserved (not dropped) so callers
     * can forward them.
     *
     * @return pair of (parsed entries, total bytes consumed including prefix)
     * @throws IllegalArgumentException if data is truncated
     */
    fun decode(data: ByteArray, offset: Int): Pair<List<TlvEntry>, Int> {
        require(offset + EXTENSION_LENGTH_SIZE <= data.size) {
            "TLV extension area truncated: need $EXTENSION_LENGTH_SIZE bytes at offset $offset, have ${data.size - offset}"
        }
        val extensionLength = getUShortLE(data, offset).toInt()
        require(offset + EXTENSION_LENGTH_SIZE + extensionLength <= data.size) {
            "TLV extension body truncated: declared $extensionLength bytes, have ${data.size - offset - EXTENSION_LENGTH_SIZE}"
        }
        if (extensionLength == 0) {
            return emptyList<TlvEntry>() to EXTENSION_LENGTH_SIZE
        }
        val entries = mutableListOf<TlvEntry>()
        val end = offset + EXTENSION_LENGTH_SIZE + extensionLength
        var pos = offset + EXTENSION_LENGTH_SIZE
        while (pos < end) {
            require(pos + TLV_HEADER_SIZE <= end) {
                "TLV entry header truncated at offset $pos"
            }
            val tag = data[pos].toUByte()
            val valueLen = getUShortLE(data, pos + 1).toInt()
            pos += TLV_HEADER_SIZE
            require(pos + valueLen <= end) {
                "TLV entry value truncated: tag=0x${tag.toString(16)}, declared $valueLen bytes, have ${end - pos}"
            }
            entries.add(TlvEntry(tag, data.copyOfRange(pos, pos + valueLen)))
            pos += valueLen
        }
        return entries to (EXTENSION_LENGTH_SIZE + extensionLength)
    }

    /**
     * Returns the wire size of the extension area (including the 2-byte prefix).
     */
    fun wireSize(entries: List<TlvEntry>): Int =
        EXTENSION_LENGTH_SIZE + entries.sumOf { TLV_HEADER_SIZE + it.value.size }

    // --- LE helpers (self-contained to avoid coupling to WireCodec) ---

    private fun putUShortLE(buf: ByteArray, offset: Int, value: UShort) {
        val v = value.toInt()
        buf[offset] = v.toByte()
        buf[offset + 1] = (v shr 8).toByte()
    }

    private fun getUShortLE(buf: ByteArray, offset: Int): UShort =
        (
            (buf[offset].toInt() and 0xFF) or
                ((buf[offset + 1].toInt() and 0xFF) shl 8)
            ).toUShort()
}
