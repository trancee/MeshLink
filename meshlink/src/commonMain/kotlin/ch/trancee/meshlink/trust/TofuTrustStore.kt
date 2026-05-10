package ch.trancee.meshlink.trust

import ch.trancee.meshlink.storage.SecureStorage

internal class TofuTrustStore internal constructor(
    private val secureStorage: SecureStorage,
) {
    internal suspend fun read(peerIdValue: String): TrustRecord? {
        val encoded = secureStorage.read(key(peerIdValue)) ?: return null
        val payload = encoded.decodeToString()
        val separator = payload.indexOf('|')
        if (separator < 0) {
            return null
        }
        return TrustRecord(
            peerIdValue = payload.substring(0, separator),
            identityFingerprint = payload.substring(separator + 1),
        )
    }

    internal suspend fun write(record: TrustRecord): Unit {
        val payload = "${record.peerIdValue}|${record.identityFingerprint}".encodeToByteArray()
        secureStorage.write(key(record.peerIdValue), payload)
    }

    private fun key(peerIdValue: String): String {
        return "trust:$peerIdValue"
    }
}
