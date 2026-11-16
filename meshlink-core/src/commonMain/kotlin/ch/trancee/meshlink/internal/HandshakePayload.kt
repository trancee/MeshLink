package ch.trancee.meshlink.internal

/**
 * The TLVs a link handshake message carries inside Noise (spec "Handshake payload", D-16): none in
 * message 1; `RESOLVING_KEY` (gated meshes) and the required `CERTIFICATE` in messages 2 and 3.
 * Decoding keeps unknown non-critical TLVs, so a payload re-encodes to the bytes it came from.
 */
public class HandshakePayload(
    public val tlvs: List<Tlv>,
) {
    /** The sender's certificate bytes, or `null` if the payload has none. */
    public val certificate: ByteArray? get() = tlvs.firstOrNull { it.type == CERTIFICATE }?.value

    /** The sender's current resolving key, or `null` if the payload has no valid one. */
    public val resolvingKey: ResolvingKey?
        get() =
            tlvs
                .firstOrNull { it.type == RESOLVING_KEY }
                ?.let { (ResolvingKey.decode(it.value) as? Decoded.Valid)?.value }

    /** The payload's bytes. */
    public fun encode(): ByteArray = Tlv.encode(tlvs)

    public companion object {
        private const val RESOLVING_KEY = 0x02
        private const val CERTIFICATE = 0x81
        private val KNOWN = mapOf(RESOLVING_KEY to ResolvingKey.LENGTH, CERTIFICATE to null)

        /**
         * The payload of message 2 or 3: [resolvingKey], which only a gated mesh sends, then
         * [certificate], in ascending TLV order.
         */
        public fun of(
            certificate: ByteArray,
            resolvingKey: ResolvingKey? = null,
        ): HandshakePayload =
            HandshakePayload(
                listOfNotNull(resolvingKey?.let { Tlv(RESOLVING_KEY, it.encode()) }, Tlv(CERTIFICATE, certificate)),
            )

        /** Decodes a payload; never throws. */
        public fun decode(bytes: ByteArray): Decoded<HandshakePayload> =
            when (val tlvs = Tlv.decode(bytes, 0, KNOWN)) {
                is Decoded.Valid -> Decoded.Valid(HandshakePayload(tlvs.value))
                is Decoded.Invalid -> tlvs
            }
    }
}
