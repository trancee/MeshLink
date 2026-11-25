package ch.trancee.meshlink.internal

/**
 * `LOOKUP_REQUEST` (spec "Certificate lookup"): the flood [header], the requester's fresh [salt],
 * the [lookupTag] `trunc_8(H("lookup-tag", salt ‖ NodeId))` of the NodeId it looks for and its
 * fresh X25519 [ephemeralKey].
 */
public class LookupRequest(
    public val header: FloodHeader,
    public val salt: ByteArray,
    public val lookupTag: ByteArray,
    public val ephemeralKey: ByteArray,
) {
    init {
        require(salt.size == TAG && lookupTag.size == TAG) { "salt and lookup_tag must be $TAG bytes" }
        require(ephemeralKey.size == KEY) { "ephemeral_key must be $KEY bytes" }
    }

    /** The frame's bytes. */
    public fun encode(): ByteArray = header.encode(TYPE) + salt + lookupTag + ephemeralKey

    public companion object {
        /** The frame type, `0x82`. */
        public const val TYPE: Int = 0x82

        /** The frame's size. */
        public const val LENGTH: Int = 66

        private const val SALT_AT = FloodHeader.LENGTH
        private const val TAG_AT = SALT_AT + TAG
        private const val KEY_AT = TAG_AT + TAG

        /** Decodes one `LOOKUP_REQUEST`; never throws. Any other type is [DecodeError.UNKNOWN_TYPE]. */
        public fun decode(bytes: ByteArray): Decoded<LookupRequest> =
            when {
                bytes.isEmpty() -> {
                    Decoded.Invalid(DecodeError.TRUNCATED)
                }

                bytes.u8(0) != TYPE -> {
                    Decoded.Invalid(DecodeError.UNKNOWN_TYPE)
                }

                bytes.size < LENGTH -> {
                    Decoded.Invalid(DecodeError.TRUNCATED)
                }

                bytes.size > LENGTH -> {
                    Decoded.Invalid(DecodeError.TRAILING_BYTES)
                }

                FloodHeader.spent(bytes) -> {
                    Decoded.Invalid(DecodeError.OUT_OF_RANGE)
                }

                else -> {
                    Decoded.Valid(
                        LookupRequest(
                            FloodHeader.of(bytes),
                            bytes.copyOfRange(SALT_AT, TAG_AT),
                            bytes.copyOfRange(TAG_AT, KEY_AT),
                            bytes.copyOfRange(KEY_AT, LENGTH),
                        ),
                    )
                }
            }
    }
}

/**
 * `LOOKUP_REPLY` (spec "Certificate lookup"): the unicast [header] whose destination is the
 * request's salt, the responder's X25519 [ephemeralKey] and the [sealedCertificate], the AEAD
 * ciphertext of one identity certificate.
 */
public class LookupReply(
    public val header: UnicastHeader,
    public val ephemeralKey: ByteArray,
    public val sealedCertificate: ByteArray,
) {
    init {
        require(ephemeralKey.size == KEY) { "ephemeral_key must be $KEY bytes" }
        require(sealedCertificate.size in SEALED) { "sealed_certificate must be one of $SEALED bytes" }
    }

    /** The frame's bytes. */
    public fun encode(): ByteArray {
        val length = u16(sealedCertificate.size)
        return header.encode(TYPE) + ephemeralKey + length + sealedCertificate
    }

    public companion object {
        /** The frame type, `0x44`. */
        public const val TYPE: Int = 0x44

        /** The frame's size with a sealed classical certificate. */
        public const val CLASSICAL_LENGTH: Int = 223

        /** The frame's size with a sealed post-quantum certificate. */
        public const val POST_QUANTUM_LENGTH: Int = 1_439

        private const val KEY_AT = UnicastHeader.LENGTH
        private const val SEALED_LENGTH_AT = KEY_AT + KEY
        private const val SEALED_AT = SEALED_LENGTH_AT + 2
        private val SEALED = setOf(CLASSICAL_LENGTH - SEALED_AT, POST_QUANTUM_LENGTH - SEALED_AT)

        /** Decodes one `LOOKUP_REPLY`; never throws. Any other type is [DecodeError.UNKNOWN_TYPE]. */
        public fun decode(bytes: ByteArray): Decoded<LookupReply> =
            when {
                bytes.isEmpty() -> {
                    Decoded.Invalid(DecodeError.TRUNCATED)
                }

                bytes.u8(0) != TYPE -> {
                    Decoded.Invalid(DecodeError.UNKNOWN_TYPE)
                }

                bytes.size < SEALED_AT -> {
                    Decoded.Invalid(DecodeError.TRUNCATED)
                }

                else -> {
                    sealed(bytes, bytes.u16(SEALED_LENGTH_AT))
                }
            }

        private fun sealed(
            bytes: ByteArray,
            length: Int,
        ): Decoded<LookupReply> =
            when {
                length !in SEALED -> {
                    Decoded.Invalid(DecodeError.OUT_OF_RANGE)
                }

                bytes.size < SEALED_AT + length -> {
                    Decoded.Invalid(DecodeError.TRUNCATED)
                }

                bytes.size > SEALED_AT + length -> {
                    Decoded.Invalid(DecodeError.TRAILING_BYTES)
                }

                UnicastHeader.spent(bytes) -> {
                    Decoded.Invalid(DecodeError.OUT_OF_RANGE)
                }

                else -> {
                    Decoded.Valid(
                        LookupReply(
                            UnicastHeader.of(bytes),
                            bytes.copyOfRange(KEY_AT, SEALED_LENGTH_AT),
                            bytes.copyOfRange(SEALED_AT, bytes.size),
                        ),
                    )
                }
            }
    }
}

private const val TAG = 8
private const val KEY = 32
