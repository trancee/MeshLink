package ch.trancee.meshlink.internal

/**
 * `BROADCAST` (spec "BROADCAST"): the flood [header] and the [container], kind `0x04` in
 * `unsecured` or the signed kind `0x05` in the secured profiles, with at most [PAYLOAD_MAX] payload
 * bytes either way.
 */
public class BroadcastFrame(
    public val header: FloodHeader,
    public val container: Container,
) {
    init {
        require(container is Container.Unsecured || container is Container.SignedBroadcast) {
            "a broadcast carries kind 0x04 or 0x05, not ${container.kind}"
        }
        require(container !is Container.Unsecured || container.plaintext.size <= PAYLOAD_MAX) {
            "payload must be at most $PAYLOAD_MAX bytes"
        }
    }

    /** The frame's bytes. */
    public fun encode(): ByteArray = header.encode(TYPE) + container.encode()

    /**
     * The origin's NodeId, `H("node-id", origin_key)`, if this is a signed broadcast whose signature
     * by its `origin_key` verifies; `null` for any other frame.
     */
    public fun verifiedOrigin(crypto: CryptoProvider): ByteArray? {
        val signed = container as? Container.SignedBroadcast ?: return null
        return if (crypto.ed25519Verify(signed.originKey, signingInput(unsigned()), signed.signature)) {
            Derivations(crypto).nodeId(signed.originKey)
        } else {
            null
        }
    }

    /** Every byte of a signed broadcast before its signature. */
    private fun unsigned(): ByteArray = encode().let { it.copyOf(it.size - SIGNATURE_LENGTH) }

    public companion object {
        /** The frame type, `0x80`. */
        public const val TYPE: Int = 0x80

        /** The most payload bytes a broadcast carries (ADR 0015 decision 2). */
        public const val PAYLOAD_MAX: Int = 1_024

        private const val SIGNATURE_LENGTH = 64
        private const val HOP_LIMIT = 1

        /**
         * `DS("broadcast") ‖ frame[0] ‖ frame[2..]`, the bytes a signed broadcast's signature covers;
         * [unsigned] is every frame byte before the signature. The hop limit, `frame[1]`, is left
         * out because relays lower it.
         */
        public fun signingInput(unsigned: ByteArray): ByteArray =
            Domain.BROADCAST.encoded + unsigned[0] + unsigned.copyOfRange(HOP_LIMIT + 1, unsigned.size)

        /** The broadcast of [payload] under [header] from [identity], signed by its seed. */
        public fun sign(
            crypto: CryptoProvider,
            header: FloodHeader,
            identity: Ed25519KeyPair,
            payload: ByteArray,
        ): BroadcastFrame {
            val key = identity.publicKey
            val blank = BroadcastFrame(header, Container.SignedBroadcast(key, payload, ByteArray(SIGNATURE_LENGTH)))
            val signature = crypto.ed25519Sign(identity.seed, signingInput(blank.unsigned()))
            return BroadcastFrame(header, Container.SignedBroadcast(key, payload, signature))
        }

        private val KINDS = setOf(Container.UNSECURED, Container.SIGNED_BROADCAST)

        /** Decodes one `BROADCAST`; never throws. Any other type is [DecodeError.UNKNOWN_TYPE]. */
        public fun decode(bytes: ByteArray): Decoded<BroadcastFrame> =
            when {
                bytes.isEmpty() -> {
                    Decoded.Invalid(DecodeError.TRUNCATED)
                }

                bytes.u8(0) != TYPE -> {
                    Decoded.Invalid(DecodeError.UNKNOWN_TYPE)
                }

                bytes.size < FloodHeader.LENGTH -> {
                    Decoded.Invalid(DecodeError.TRUNCATED)
                }

                FloodHeader.spent(bytes) -> {
                    Decoded.Invalid(DecodeError.OUT_OF_RANGE)
                }

                else -> {
                    Container.decode(bytes, FloodHeader.LENGTH, KINDS).then {
                        if (it is Container.Unsecured && it.plaintext.size > PAYLOAD_MAX) {
                            Decoded.Invalid(DecodeError.OUT_OF_RANGE)
                        } else {
                            Decoded.Valid(BroadcastFrame(FloodHeader.of(bytes), it))
                        }
                    }
                }
            }
    }
}

/**
 * Any flood frame, types `0x80`–`0xBF` (spec "Routing headers"): its [type], flood [header] and
 * the [body] after it. A node forwards a flood type it doesn't know by its header alone.
 */
public class FloodFrame(
    public val type: Int,
    public val header: FloodHeader,
    public val body: ByteArray,
) {
    init {
        require(type in FIRST..LAST) { "a flood type is 0x80..0xBF, not $type" }
    }

    /** The frame's bytes. */
    public fun encode(): ByteArray = header.encode(type) + body

    public companion object {
        private const val FIRST = 0x80
        private const val LAST = 0xBF

        /**
         * Decodes one flood frame by its header; never throws. A type outside `0x80`–`0xBF` is
         * [DecodeError.UNKNOWN_TYPE].
         */
        public fun decode(bytes: ByteArray): Decoded<FloodFrame> =
            when {
                bytes.isEmpty() -> {
                    Decoded.Invalid(DecodeError.TRUNCATED)
                }

                bytes.u8(0) !in FIRST..LAST -> {
                    Decoded.Invalid(DecodeError.UNKNOWN_TYPE)
                }

                bytes.size < FloodHeader.LENGTH -> {
                    Decoded.Invalid(DecodeError.TRUNCATED)
                }

                FloodHeader.spent(bytes) -> {
                    Decoded.Invalid(DecodeError.OUT_OF_RANGE)
                }

                else -> {
                    val body = bytes.copyOfRange(FloodHeader.LENGTH, bytes.size)
                    Decoded.Valid(FloodFrame(bytes.u8(0), FloodHeader.of(bytes), body))
                }
            }
    }
}
