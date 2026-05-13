package ch.trancee.meshlink.transport

internal enum class BlePowerMode {
    PERFORMANCE,
    BALANCED,
    POWER_SAVER,
    RESERVED,
}

internal class BleDiscoveryPayload
internal constructor(
    internal val protocolVersion: Int,
    internal val powerMode: BlePowerMode,
    internal val meshHash: UShort,
    internal val l2capPsm: UByte,
    keyHash: ByteArray,
) {
    internal val keyHash: ByteArray = keyHash.copyOf()

    init {
        require(protocolVersion in 0..7) { "protocolVersion must be in 0..7" }
        require(this.keyHash.size == KEY_HASH_SIZE_BYTES) {
            "keyHash must be exactly $KEY_HASH_SIZE_BYTES bytes"
        }
        require(l2capPsm.toInt() == 0 || l2capPsm.toInt() in 128..255) {
            "l2capPsm must be 0 or in 128..255"
        }
    }

    internal fun encode(): ByteArray {
        val bytes = ByteArray(PAYLOAD_SIZE_BYTES)
        bytes[0] =
            (((protocolVersion and 0x07) shl 5) or ((powerMode.ordinal and 0x03) shl 3)).toByte()
        bytes[1] = (meshHash.toInt() and 0xFF).toByte()
        bytes[2] = ((meshHash.toInt() shr 8) and 0xFF).toByte()
        bytes[3] = l2capPsm.toByte()
        keyHash.copyInto(bytes, destinationOffset = 4)
        return bytes
    }

    internal fun payloadUuidString(): String {
        return BleDiscoveryContract.uuidStringFromBytes(encode())
    }

    internal companion object {
        internal const val PAYLOAD_SIZE_BYTES: Int = 16
        internal const val KEY_HASH_SIZE_BYTES: Int = 12

        internal fun decode(bytes: ByteArray): BleDiscoveryPayload {
            require(bytes.size == PAYLOAD_SIZE_BYTES) {
                "payload must be exactly $PAYLOAD_SIZE_BYTES bytes"
            }
            val header = bytes[0].toInt() and 0xFF
            val protocolVersion = (header shr 5) and 0x07
            val powerModeOrdinal = (header shr 3) and 0x03
            val meshHash =
                (((bytes[2].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)).toUShort()
            val l2capPsm = bytes[3].toUByte()
            val keyHash = bytes.copyOfRange(4, 16)
            return BleDiscoveryPayload(
                protocolVersion = protocolVersion,
                powerMode = BlePowerMode.entries[powerModeOrdinal],
                meshHash = meshHash,
                l2capPsm = l2capPsm,
                keyHash = keyHash,
            )
        }

        internal fun fromUuidString(uuid: String): BleDiscoveryPayload {
            return decode(BleDiscoveryContract.bytesFromUuidString(uuid))
        }
    }
}

internal object BleDiscoveryContract {
    internal const val CURRENT_PROTOCOL_VERSION: Int = 1
    private const val HEX_DIGITS = "0123456789abcdef"
    internal const val ADVERTISEMENT_SERVICE_UUID: String = "4d455348"
    internal const val ADVERTISEMENT_SERVICE_UUID_EXPANDED: String =
        "4d455348-0000-1000-8000-00805f9b34fb"
    internal const val GATT_FALLBACK_SERVICE_UUID: String = "4d455348-0001-1000-8000-000000000000"
    internal val GATT_CHARACTERISTIC_UUIDS: List<String> =
        listOf(
            "4d455348-0002-1000-8000-000000000000",
            "4d455348-0003-1000-8000-000000000000",
            "4d455348-0004-1000-8000-000000000000",
            "4d455348-0005-1000-8000-000000000000",
            "4d455348-0006-1000-8000-000000000000",
        )

    internal fun advertisedServiceUuids(payload: BleDiscoveryPayload): List<String> {
        return listOf(ADVERTISEMENT_SERVICE_UUID, payload.payloadUuidString())
    }

    internal fun isAdvertisementServiceUuid(uuid: String): Boolean {
        val normalized = uuid.lowercase()
        return normalized == ADVERTISEMENT_SERVICE_UUID ||
            normalized == ADVERTISEMENT_SERVICE_UUID_EXPANDED
    }

    internal fun isSupportedProtocolVersion(protocolVersion: Int): Boolean {
        return protocolVersion == CURRENT_PROTOCOL_VERSION
    }

    internal fun computeMeshHash(appId: String): UShort {
        val hash = fnv1a32(appId.encodeToByteArray())
        val folded =
            (((hash ushr 16) xor (hash and 0xFFFF)) and 0xFFFF).let { if (it == 0) 1 else it }
        return folded.toUShort()
    }

    internal fun uuidStringFromBytes(bytes: ByteArray): String {
        require(bytes.size == 16) { "UUID payload must be 16 bytes" }
        val hex =
            bytes.joinToString(separator = "") { byte ->
                val value = byte.toInt() and 0xFF
                val high = HEX_DIGITS[(value ushr 4) and 0x0F]
                val low = HEX_DIGITS[value and 0x0F]
                "$high$low"
            }
        return buildString(36) {
            append(hex.substring(0, 8))
            append('-')
            append(hex.substring(8, 12))
            append('-')
            append(hex.substring(12, 16))
            append('-')
            append(hex.substring(16, 20))
            append('-')
            append(hex.substring(20, 32))
        }
    }

    internal fun bytesFromUuidString(uuid: String): ByteArray {
        val normalized = uuid.replace("-", "")
        require(normalized.length == 32) { "UUID string must encode exactly 16 bytes" }
        return ByteArray(16) { index ->
            normalized.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun fnv1a32(bytes: ByteArray): Int {
        var hash = 0x811c9dc5.toInt()
        bytes.forEach { byte ->
            hash = hash xor (byte.toInt() and 0xFF)
            hash *= 0x01000193
        }
        return hash
    }
}
