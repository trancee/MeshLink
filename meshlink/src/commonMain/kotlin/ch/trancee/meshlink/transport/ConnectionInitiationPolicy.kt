package ch.trancee.meshlink.transport

import kotlin.random.Random

/**
 * Spec §4 tie-breaking logic for deciding which peer in a BLE pair should play Central (initiate
 * the connection).
 *
 * Rules (applied in order):
 * 1. The peer with the *lower* powerMode integer (0=Performance, 1=Balanced, 2=PowerSaver)
 *    initiates — the more capable device takes the Central role.
 * 2. When powerModes are equal, the peer whose keyHash is lexicographically *higher* (unsigned byte
 *    comparison) initiates.
 * 3. If both keyHashes are identical, neither initiates (tie → false).
 */
internal object ConnectionInitiationPolicy {

    /**
     * Returns true if the local device should initiate the connection to the remote device.
     *
     * @param localKeyHash 12-byte SHA-256(Ed25519Pub ‖ X25519Pub) for the local peer.
     * @param localPowerMode PowerMode integer (0–2) from the local advertisement payload.
     * @param remoteKeyHash 12-byte key-hash for the remote peer.
     * @param remotePowerMode PowerMode integer (0–2) from the remote advertisement payload.
     * @throws IllegalArgumentException if either keyHash is not exactly 12 bytes.
     */
    fun shouldInitiate(
        localKeyHash: ByteArray,
        localPowerMode: Int,
        remoteKeyHash: ByteArray,
        remotePowerMode: Int,
    ): Boolean {
        if (localKeyHash.size != 12)
            throw IllegalArgumentException(
                "localKeyHash must be 12 bytes, got ${localKeyHash.size}"
            )
        if (remoteKeyHash.size != 12)
            throw IllegalArgumentException(
                "remoteKeyHash must be 12 bytes, got ${remoteKeyHash.size}"
            )

        if (localPowerMode != remotePowerMode) return localPowerMode < remotePowerMode

        // Same powerMode → higher keyHash (unsigned lexicographic) initiates.
        for (i in localKeyHash.indices) {
            val localByte = localKeyHash[i].toInt() and 0xFF
            val remoteByte = remoteKeyHash[i].toInt() and 0xFF
            if (localByte != remoteByte) return localByte > remoteByte
        }
        return false // identical keyHashes → tie, neither initiates
    }

    /**
     * Returns a deterministic stagger delay in milliseconds (0 ≤ result < 2000) derived from the
     * XOR of the two peers' key-hash seeds. XOR commutativity ensures both peers compute the same
     * value and avoid thundering-herd reconnects.
     *
     * @param localKeyHash 12-byte key-hash for the local peer.
     * @param remoteKeyHash 12-byte key-hash for the remote peer.
     * @throws IllegalArgumentException if either keyHash is not exactly 12 bytes.
     */
    fun staggerDelayMillis(localKeyHash: ByteArray, remoteKeyHash: ByteArray): Long {
        if (localKeyHash.size != 12)
            throw IllegalArgumentException(
                "localKeyHash must be 12 bytes, got ${localKeyHash.size}"
            )
        if (remoteKeyHash.size != 12)
            throw IllegalArgumentException(
                "remoteKeyHash must be 12 bytes, got ${remoteKeyHash.size}"
            )
        val localSeed = bytesToLong(localKeyHash)
        val remoteSeed = bytesToLong(remoteKeyHash)
        return Random(localSeed xor remoteSeed).nextLong(2_000L)
    }

    /** Big-endian encoding of the first 8 bytes of [bytes] into a 64-bit Long seed. */
    private fun bytesToLong(bytes: ByteArray): Long {
        var result = 0L
        for (i in 0 until 8) {
            result = (result shl 8) or (bytes[i].toLong() and 0xFF)
        }
        return result
    }
}
