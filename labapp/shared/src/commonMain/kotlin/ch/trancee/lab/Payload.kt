package ch.trancee.lab

/** The SHA-256 digest of [bytes]. */
internal expect fun sha256(bytes: ByteArray): ByteArray

/** Appends [line] and a newline to the file [name] in [directory], creating both if needed. */
internal expect fun appendLine(
    directory: String,
    name: String,
    line: String,
)

/** The lab message: a text body followed by its SHA-256, so the receiver checks it arrived intact. */
internal object Payload {
    private const val DIGEST_BYTES = 32

    fun create(body: String): ByteArray {
        val bytes = body.encodeToByteArray()
        return bytes + sha256(bytes)
    }

    fun intact(payload: ByteArray): Boolean {
        val split = payload.size - DIGEST_BYTES
        if (split < 0) return false
        return sha256(payload.copyOfRange(0, split)).contentEquals(payload.copyOfRange(split, payload.size))
    }

    fun hex(bytes: ByteArray): String = bytes.toHexString()
}
