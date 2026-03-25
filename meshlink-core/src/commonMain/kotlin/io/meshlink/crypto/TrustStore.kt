package io.meshlink.crypto

enum class TrustMode { STRICT, SOFT_REPIN }

sealed interface VerifyResult {
    data object FirstSeen : VerifyResult
    data object Trusted : VerifyResult
    data class KeyChanged(val previousKey: ByteArray) : VerifyResult
}

class TrustStore private constructor(val mode: TrustMode, private val pins: MutableMap<String, ByteArray>) {

    constructor(mode: TrustMode = TrustMode.STRICT) : this(mode, mutableMapOf())

    fun verify(peerId: String, publicKey: ByteArray): VerifyResult {
        val pinned = pins[peerId]
        if (pinned == null) {
            pins[peerId] = publicKey.copyOf()
            return VerifyResult.FirstSeen
        }
        if (pinned.contentEquals(publicKey)) {
            return VerifyResult.Trusted
        }
        return when (mode) {
            TrustMode.STRICT -> VerifyResult.KeyChanged(pinned.copyOf())
            TrustMode.SOFT_REPIN -> {
                pins[peerId] = publicKey.copyOf()
                VerifyResult.FirstSeen
            }
        }
    }

    fun repin(peerId: String, publicKey: ByteArray) {
        pins[peerId] = publicKey.copyOf()
    }

    data class Snapshot(val mode: TrustMode, val pins: Map<String, ByteArray>)

    fun snapshot(): Snapshot = Snapshot(mode, pins.mapValues { it.value.copyOf() })

    companion object {
        fun restore(snapshot: Snapshot): TrustStore =
            TrustStore(snapshot.mode, snapshot.pins.mapValues { it.value.copyOf() }.toMutableMap())
    }
}
