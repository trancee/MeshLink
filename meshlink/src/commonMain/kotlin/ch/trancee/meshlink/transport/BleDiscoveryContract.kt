package ch.trancee.meshlink.transport

internal enum class BlePowerMode {
    PERFORMANCE,
    BALANCED,
    POWER_SAVER,
    RESERVED,
}

internal enum class BleDiscoveryPlatformFamily(internal val encodedBits: Int) {
    UNKNOWN(0),
    ANDROID(1),
    IOS(2);

    internal companion object {
        internal fun fromEncodedBits(bits: Int): BleDiscoveryPlatformFamily {
            return entries.firstOrNull { family -> family.encodedBits == bits } ?: UNKNOWN
        }
    }
}

/**
 * Compact 16-byte discovery payload embedded directly into a UUID string.
 *
 * Layout: `[header][meshHash LE][l2capPsm][keyHash[12]]`. The header packs protocol version, power
 * mode, and platform family so the advertisement stays within one UUID-sized payload.
 */
internal class BleDiscoveryPayload
internal constructor(
    internal val protocolVersion: Int,
    internal val powerMode: BlePowerMode,
    internal val meshHash: UShort,
    internal val l2capPsm: UByte,
    keyHash: ByteArray,
    internal val platformFamily: BleDiscoveryPlatformFamily = BleDiscoveryPlatformFamily.UNKNOWN,
) {
    internal val keyHash: ByteArray = keyHash.copyOf()

    init {
        require(protocolVersion in 0..MAX_PROTOCOL_VERSION) {
            "protocolVersion must be in 0..$MAX_PROTOCOL_VERSION"
        }
        require(this.keyHash.size == KEY_HASH_SIZE_BYTES) {
            "keyHash must be exactly $KEY_HASH_SIZE_BYTES bytes"
        }
        require(l2capPsm.toInt() == 0 || l2capPsm.toInt() in MIN_DYNAMIC_PSM..MAX_DYNAMIC_PSM) {
            "l2capPsm must be 0 or in $MIN_DYNAMIC_PSM..$MAX_DYNAMIC_PSM"
        }
    }

    internal fun encode(): ByteArray {
        val bytes = ByteArray(PAYLOAD_SIZE_BYTES)
        bytes[HEADER_BYTE_INDEX] = encodeHeaderByte()
        bytes[MESH_HASH_LOW_BYTE_INDEX] = (meshHash.toInt() and BYTE_MASK).toByte()
        bytes[MESH_HASH_HIGH_BYTE_INDEX] =
            ((meshHash.toInt() shr BITS_PER_BYTE) and BYTE_MASK).toByte()
        bytes[L2CAP_PSM_BYTE_INDEX] = l2capPsm.toByte()
        keyHash.copyInto(bytes, destinationOffset = KEY_HASH_OFFSET_BYTES)
        return bytes
    }

    internal fun payloadUuidString(): String {
        return BleDiscoveryContract.uuidStringFromBytes(encode())
    }

    internal companion object {
        internal const val PAYLOAD_SIZE_BYTES: Int = UUID_BYTE_SIZE
        internal const val KEY_HASH_SIZE_BYTES: Int = 12

        internal fun decode(bytes: ByteArray): BleDiscoveryPayload {
            require(bytes.size == PAYLOAD_SIZE_BYTES) {
                "payload must be exactly $PAYLOAD_SIZE_BYTES bytes"
            }
            val header = bytes[HEADER_BYTE_INDEX].toInt() and BYTE_MASK
            val protocolVersion = (header shr PROTOCOL_VERSION_SHIFT) and PROTOCOL_VERSION_MASK
            val powerModeOrdinal = (header shr POWER_MODE_SHIFT) and POWER_MODE_MASK
            val platformFamilyBits = header and PLATFORM_FAMILY_MASK
            val meshHash = decodeMeshHash(bytes)
            val l2capPsm = bytes[L2CAP_PSM_BYTE_INDEX].toUByte()
            val keyHash = bytes.copyOfRange(KEY_HASH_OFFSET_BYTES, PAYLOAD_SIZE_BYTES)
            return BleDiscoveryPayload(
                protocolVersion = protocolVersion,
                powerMode = BlePowerMode.entries[powerModeOrdinal],
                meshHash = meshHash,
                l2capPsm = l2capPsm,
                keyHash = keyHash,
                platformFamily = BleDiscoveryPlatformFamily.fromEncodedBits(platformFamilyBits),
            )
        }

        internal fun fromUuidString(uuid: String): BleDiscoveryPayload {
            return decode(BleDiscoveryContract.bytesFromUuidString(uuid))
        }

        private fun decodeMeshHash(bytes: ByteArray): UShort {
            return (((bytes[MESH_HASH_HIGH_BYTE_INDEX].toInt() and BYTE_MASK) shl BITS_PER_BYTE) or
                    (bytes[MESH_HASH_LOW_BYTE_INDEX].toInt() and BYTE_MASK))
                .toUShort()
        }
    }

    private fun encodeHeaderByte(): Byte {
        return (((protocolVersion and PROTOCOL_VERSION_MASK) shl PROTOCOL_VERSION_SHIFT) or
                ((powerMode.ordinal and POWER_MODE_MASK) shl POWER_MODE_SHIFT) or
                (platformFamily.encodedBits and PLATFORM_FAMILY_MASK))
            .toByte()
    }
}

/**
 * Discovery constants and helpers shared by Android and iOS transport seams.
 *
 * The transport exposes the discovery payload as a UUID string because both platforms already treat
 * service UUIDs as the most portable advertisement seam.
 */
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
    internal const val GATT_WRITE_CHARACTERISTIC_UUID: String =
        "4d455348-0002-1000-8000-000000000000"
    internal const val GATT_NOTIFY_CHARACTERISTIC_UUID: String =
        "4d455348-0003-1000-8000-000000000000"
    internal const val GATT_CONTROL_CHARACTERISTIC_UUID: String =
        "4d455348-0004-1000-8000-000000000000"
    internal const val GATT_MTU_CHARACTERISTIC_UUID: String = "4d455348-0005-1000-8000-000000000000"
    internal const val GATT_SERVICE_ID_CHARACTERISTIC_UUID: String =
        "4d455348-0006-1000-8000-000000000000"

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
            (((hash ushr MESH_HASH_FOLD_SHIFT) xor (hash and USHORT_MASK)) and USHORT_MASK).let {
                if (it == 0) NON_ZERO_HASH_FALLBACK else it
            }
        return folded.toUShort()
    }

    internal fun uuidStringFromBytes(bytes: ByteArray): String {
        require(bytes.size == UUID_BYTE_SIZE) { "UUID payload must be $UUID_BYTE_SIZE bytes" }
        // Preserve byte order exactly; the encoded UUID text is only a transport
        // wrapper around the discovery payload, not a semantic UUID structure.
        val hex =
            bytes.joinToString(separator = "") { byte ->
                val value = byte.toInt() and BYTE_MASK
                val high = HEX_DIGITS[(value ushr NIBBLE_BITS) and NIBBLE_MASK]
                val low = HEX_DIGITS[value and NIBBLE_MASK]
                "$high$low"
            }
        return buildString(UUID_STRING_LENGTH) {
            append(hex.substring(0, UUID_GROUP_1_END))
            append('-')
            append(hex.substring(UUID_GROUP_1_END, UUID_GROUP_2_END))
            append('-')
            append(hex.substring(UUID_GROUP_2_END, UUID_GROUP_3_END))
            append('-')
            append(hex.substring(UUID_GROUP_3_END, UUID_GROUP_4_END))
            append('-')
            append(hex.substring(UUID_GROUP_4_END, UUID_GROUP_5_END))
        }
    }

    internal fun bytesFromUuidString(uuid: String): ByteArray {
        val normalized = uuid.replace("-", "")
        require(normalized.length == UUID_HEX_LENGTH) {
            "UUID string must encode exactly $UUID_BYTE_SIZE bytes"
        }
        return ByteArray(UUID_BYTE_SIZE) { index ->
            normalized
                .substring(index * HEX_BYTE_LENGTH, index * HEX_BYTE_LENGTH + HEX_BYTE_LENGTH)
                .toInt(HEX_RADIX)
                .toByte()
        }
    }

    /**
     * Decodes only the two meshHash bytes from a discovery payload UUID string, without allocating
     * the full 16-byte payload array or [BleDiscoveryPayload] instance.
     *
     * In a BLE-dense environment the overwhelming majority of scan results carry a foreign meshHash
     * and are rejected immediately by the caller. Fully decoding+allocating a [BleDiscoveryPayload]
     * (16-byte array, keyHash copy) for every one of those rejections is unnecessary main-thread
     * work -- `ScanCallback.onScanResult` is delivered on the main looper with no way to redirect
     * it to a background thread via the public API, so every avoidable allocation here directly
     * competes with connection-critical BLE callback delivery. Returns null when the string isn't a
     * well-formed payload UUID so the caller can fall back to the full parse (and its existing
     * "invalid encoding" diagnostics) unchanged.
     */
    internal fun peekMeshHashOrNull(uuid: String): UShort? {
        val normalized = uuid.replace("-", "")
        if (normalized.length != UUID_HEX_LENGTH) return null
        return runCatching {
                val low =
                    normalized
                        .substring(
                            MESH_HASH_LOW_BYTE_INDEX * HEX_BYTE_LENGTH,
                            MESH_HASH_LOW_BYTE_INDEX * HEX_BYTE_LENGTH + HEX_BYTE_LENGTH,
                        )
                        .toInt(HEX_RADIX)
                val high =
                    normalized
                        .substring(
                            MESH_HASH_HIGH_BYTE_INDEX * HEX_BYTE_LENGTH,
                            MESH_HASH_HIGH_BYTE_INDEX * HEX_BYTE_LENGTH + HEX_BYTE_LENGTH,
                        )
                        .toInt(HEX_RADIX)
                (((high and BYTE_MASK) shl BITS_PER_BYTE) or (low and BYTE_MASK)).toUShort()
            }
            .getOrNull()
    }

    private fun fnv1a32(bytes: ByteArray): Int {
        var hash = FNV_OFFSET_BASIS.toInt()
        bytes.forEach { byte ->
            hash = hash xor (byte.toInt() and BYTE_MASK)
            hash *= FNV_PRIME
        }
        return hash
    }
}

/**
 * Deterministic tie-breaker for discovery-driven L2CAP connection ownership.
 *
 * Mixed Android/iOS pairs force Android to initiate. Same-platform pairs fall back to an unsigned
 * lexical comparison of the advertisement key hash.
 */
internal fun shouldLocalPeerInitiateL2capConnection(
    localKeyHash: ByteArray,
    localPlatformFamily: BleDiscoveryPlatformFamily,
    remoteKeyHash: ByteArray,
    remotePlatformFamily: BleDiscoveryPlatformFamily,
): Boolean {
    return when {
        localPlatformFamily == BleDiscoveryPlatformFamily.ANDROID &&
            remotePlatformFamily == BleDiscoveryPlatformFamily.IOS -> true
        localPlatformFamily == BleDiscoveryPlatformFamily.IOS &&
            remotePlatformFamily == BleDiscoveryPlatformFamily.ANDROID -> false
        else -> compareUnsignedKeyHashes(localKeyHash, remoteKeyHash) < 0
    }
}

/**
 * Keep the GATT side bearer available whenever both peers are known BLE peers. GATT must always be
 * available as the universal fallback bearer; L2CAP is preferred opportunistically for regular data
 * frames when a link happens to already be connected. See
 * [ch.trancee.meshlink.engine.DirectWireFrame] and
 * `ch.trancee.meshlink.engine.resolveGattDataBearerMode` for the frame-type-aware bearer selection
 * policy.
 */
internal fun shouldUseMixedPlatformGattNotifyBearer(
    localPlatformFamily: BleDiscoveryPlatformFamily,
    remotePlatformFamily: BleDiscoveryPlatformFamily,
): Boolean {
    return localPlatformFamily != BleDiscoveryPlatformFamily.UNKNOWN &&
        remotePlatformFamily != BleDiscoveryPlatformFamily.UNKNOWN
}

internal fun shouldInitiateDiscoveryDrivenL2capConnection(
    localPlatformFamily: BleDiscoveryPlatformFamily,
    remotePlatformFamily: BleDiscoveryPlatformFamily,
    gattSideLinkReady: Boolean,
): Boolean {
    return true
}

/**
 * Bearer mode for an outbound direct-wire frame, decided once per frame by
 * `ch.trancee.meshlink.engine.resolveGattDataBearerMode` based on frame type:
 * - [GATT_ONLY]: handshake/control frames always use GATT. GATT is the one bearer every known BLE
 *   peer pairing keeps available (see [shouldUseMixedPlatformGattNotifyBearer]), so control traffic
 *   never has to race an L2CAP link that may not exist yet or may be mid-(re)negotiation.
 * - [L2CAP_PREFERRED_WITH_GATT_FALLBACK]: regular data frames use an already-connected L2CAP link
 *   when one exists (higher throughput), and otherwise fall back to GATT.
 *
 * Letting a single frame be offered to both bearers at once was the root cause of a hardware-only
 * bug: a handshake message delivered over both an active L2CAP link and a simultaneously-active
 * GATT side-link was processed once and then rejected as a duplicate
 * (`transport.handshake.message2.unexpected`, HOP_SESSION_FAILED), stalling the exchange. Each
 * frame must resolve to exactly one bearer mode up front instead. See
 * docs/explanation/reference-app-physical-integration-findings.md for the full investigation.
 */
internal enum class GattDataBearerMode {
    GATT_ONLY,
    L2CAP_PREFERRED_WITH_GATT_FALLBACK,
}

private fun compareUnsignedKeyHashes(left: ByteArray, right: ByteArray): Int {
    val length = minOf(left.size, right.size)
    for (index in 0 until length) {
        val leftByte = left[index].toInt() and BYTE_MASK
        val rightByte = right[index].toInt() and BYTE_MASK
        if (leftByte != rightByte) {
            return leftByte.compareTo(rightByte)
        }
    }
    return left.size.compareTo(right.size)
}

private const val BITS_PER_BYTE: Int = 8
private const val BYTE_MASK: Int = 0xFF
private const val HEX_BYTE_LENGTH: Int = 2
private const val HEX_RADIX: Int = 16
private const val MAX_DYNAMIC_PSM: Int = 255
private const val MAX_PROTOCOL_VERSION: Int = 7
private const val MESH_HASH_FOLD_SHIFT: Int = 16
private const val MIN_DYNAMIC_PSM: Int = 128
private const val NIBBLE_BITS: Int = 4
private const val NIBBLE_MASK: Int = 0x0F
private const val NON_ZERO_HASH_FALLBACK: Int = 1
private const val POWER_MODE_MASK: Int = 0x03
private const val POWER_MODE_SHIFT: Int = 3
private const val PROTOCOL_VERSION_MASK: Int = 0x07
private const val PROTOCOL_VERSION_SHIFT: Int = 5
private const val PLATFORM_FAMILY_MASK: Int = 0x07
private const val HEADER_BYTE_INDEX: Int = 0
private const val MESH_HASH_LOW_BYTE_INDEX: Int = 1
private const val MESH_HASH_HIGH_BYTE_INDEX: Int = 2
private const val L2CAP_PSM_BYTE_INDEX: Int = 3
private const val KEY_HASH_OFFSET_BYTES: Int = 4
private const val UUID_BYTE_SIZE: Int = 16
private const val UUID_HEX_LENGTH: Int = 32
private const val UUID_STRING_LENGTH: Int = 36
private const val UUID_GROUP_1_END: Int = 8
private const val UUID_GROUP_2_END: Int = 12
private const val UUID_GROUP_3_END: Int = 16
private const val UUID_GROUP_4_END: Int = 20
private const val UUID_GROUP_5_END: Int = 32
private const val USHORT_MASK: Int = 0xFFFF
private const val FNV_OFFSET_BASIS: UInt = 0x811C9DC5u
private const val FNV_PRIME: Int = 0x01000193
