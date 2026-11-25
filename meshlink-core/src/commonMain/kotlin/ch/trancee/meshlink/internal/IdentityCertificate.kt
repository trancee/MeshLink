package ch.trancee.meshlink.internal

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * An identity certificate (spec "Identity certificate"; ADR 0014): a node's [ed25519Key] binds its
 * static [x25519Key], and in `PostQuantum` its [xWingKey], for `[notBefore, notAfter)`, signed with
 * pure Ed25519 over `DS("certificate") ‖ body`. `Unsecured` has no certificate.
 *
 * Instances come only from [decode] and [issue], so every certificate has a valid layout and window.
 */
public class IdentityCertificate private constructor(
    private val bytes: ByteArray,
) {
    /** `Classical` or `PostQuantum`. */
    public val profile: WireProfile = WireProfile.entries.single { it.code == bytes.u8(1) }

    /**
     * [current] if a peer that holds this certificate, one of this node's, should get [current] at Unix second
     * [nowSeconds] (spec "Plaintext extensions"; ADR 0014 decision 10): this certificate is past half its
     * validity and [current] is newer. Otherwise `null`.
     */
    public fun refresh(
        current: IdentityCertificate,
        nowSeconds: Long,
    ): IdentityCertificate? {
        val half = notBefore.toLong() + (notAfter - notBefore).toLong() / 2
        return current.takeIf { nowSeconds >= half && it.notBefore > notBefore }
    }

    /** The 32-byte Ed25519 identity key; the NodeId is its hash. */
    public val ed25519Key: ByteArray get() = bytes.copyOfRange(HEADER_LENGTH, X25519_OFFSET)

    /** The 32-byte static X25519 key. */
    public val x25519Key: ByteArray get() = bytes.copyOfRange(X25519_OFFSET, X25519_OFFSET + KEY_LENGTH)

    /** The 1,216-byte X-Wing key in `PostQuantum`, else `null`. */
    public val xWingKey: ByteArray?
        get() = if (profile == WireProfile.POST_QUANTUM) bytes.copyOfRange(X25519_OFFSET + KEY_LENGTH, times) else null

    /** The first second of validity, in Unix seconds. */
    public val notBefore: UInt get() = bytes.u32(times)

    /** The first second after validity, in Unix seconds. */
    public val notAfter: UInt get() = bytes.u32(times + UInt.SIZE_BYTES)

    /** The 64-byte Ed25519 signature over [signingInput] of the body. */
    public val signature: ByteArray get() = bytes.copyOfRange(bytes.size - SIGNATURE_LENGTH, bytes.size)

    private val times: Int get() = timesOffset(bytes)

    /** The certificate's bytes. */
    public fun encode(): ByteArray = bytes.copyOf()

    public companion object {
        /** The certificate layout version (spec `certificate-version`). */
        public const val VERSION: Int = 0x01

        /** A `Classical` certificate's length in bytes (spec `certificate-classical`). */
        public const val CLASSICAL_LENGTH: Int = 138

        /** A `PostQuantum` certificate's length in bytes (spec `certificate-post-quantum`). */
        public const val POST_QUANTUM_LENGTH: Int = 1_354

        /** The longest validity window in seconds (spec `certificate-validity-max`). */
        public const val VALIDITY_MAX_SECONDS: Long = 86_400

        /** The clock skew tolerated on both edges of the window, in seconds (spec `clock-skew`, D-46). */
        public const val CLOCK_SKEW_SECONDS: Long = 30

        /** The longest validity [issue] accepts. */
        public val VALIDITY_MAX: Duration = 24.hours

        private const val KEY_LENGTH = 32
        private const val SIGNATURE_LENGTH = 64
        private const val HEADER_LENGTH = 2
        private const val MILLIS_PER_SECOND = 1_000L
        private const val U32_MAX = 0xFFFF_FFFFL
        private const val X25519_OFFSET = HEADER_LENGTH + KEY_LENGTH
        private val LENGTHS =
            mapOf(WireProfile.CLASSICAL to CLASSICAL_LENGTH, WireProfile.POST_QUANTUM to POST_QUANTUM_LENGTH)

        /** The [clock]'s wall time in whole Unix seconds, rounded down, also before 1970. */
        private fun unixSeconds(clock: Clock): Long = clock.wallClockMillis().floorDiv(MILLIS_PER_SECOND)

        private fun timesOffset(bytes: ByteArray): Int = bytes.size - SIGNATURE_LENGTH - 2 * UInt.SIZE_BYTES

        /** `DS("certificate") ‖ body`, the bytes the signature covers; [body] is every byte before the signature. */
        public fun signingInput(body: ByteArray): ByteArray = Domain.CERTIFICATE.encoded + body

        /**
         * Decodes [bytes] (validation rules 1 and 2): version, profile, length, then
         * `0 < not_after − not_before ≤ 86,400`. The signature is not checked; never throws.
         */
        public fun decode(bytes: ByteArray): Decoded<IdentityCertificate> =
            layout(bytes).then {
                val times = timesOffset(bytes)
                val window = bytes.u32(times + UInt.SIZE_BYTES).toLong() - bytes.u32(times).toLong()
                if (window in 1..VALIDITY_MAX_SECONDS) {
                    Decoded.Valid(IdentityCertificate(bytes.copyOf()))
                } else {
                    Decoded.Invalid(DecodeError.OUT_OF_RANGE)
                }
            }

        /** The profile of [bytes] if its version, profile and length are valid (validation rule 1). */
        private fun layout(bytes: ByteArray): Decoded<WireProfile> {
            val profile = if (bytes.size < HEADER_LENGTH) null else LENGTHS.keys.firstOrNull { it.code == bytes.u8(1) }
            val error =
                when {
                    bytes.isEmpty() -> DecodeError.TRUNCATED
                    bytes.u8(0) != VERSION -> DecodeError.UNKNOWN_VERSION
                    bytes.size < HEADER_LENGTH -> DecodeError.TRUNCATED
                    profile == null -> DecodeError.UNKNOWN_KIND
                    bytes.size < LENGTHS.getValue(profile) -> DecodeError.TRUNCATED
                    bytes.size > LENGTHS.getValue(profile) -> DecodeError.TRAILING_BYTES
                    else -> return Decoded.Valid(profile)
                }
            return Decoded.Invalid(error)
        }

        /**
         * Issues a certificate binding [identity] to [keys], valid from the [clock]'s current second
         * for [validity]: `Classical` if [keys] has no X-Wing key, else `PostQuantum`.
         *
         * @param validity 1 s to [VALIDITY_MAX], in whole seconds; a fraction is dropped.
         */
        public fun issue(
            crypto: CryptoProvider,
            clock: Clock,
            identity: Ed25519KeyPair,
            keys: StaticKeys,
            validity: Duration = VALIDITY_MAX,
        ): IdentityCertificate {
            val seconds = validity.inWholeSeconds
            val notBefore = unixSeconds(clock)
            require(seconds in 1..VALIDITY_MAX_SECONDS) { "validity must be 1 s to 24 h, not $validity" }
            require(notBefore >= 0 && notBefore + seconds <= U32_MAX) { "the window must fit u32 seconds" }
            val profile = if (keys.xWingKey == null) WireProfile.CLASSICAL else WireProfile.POST_QUANTUM
            val body =
                byteArrayOf(VERSION.toByte(), profile.code.toByte()) + identity.publicKey + keys.x25519Key +
                    (keys.xWingKey ?: ByteArray(0)) + u32(notBefore.toUInt()) + u32((notBefore + seconds).toUInt())
            return IdentityCertificate(body + crypto.ed25519Sign(identity.seed, signingInput(body)))
        }

        /**
         * Validates [bytes] as a certificate of the mesh's [profile] for the peer whose NodeId is
         * [nodeId], at the [clock]'s current second: validation rules 1 to 6 in order. The result names
         * the first rule broken; validation changes no state.
         */
        public fun validate(
            crypto: CryptoProvider,
            bytes: ByteArray,
            profile: WireProfile,
            nodeId: ByteArray,
            clock: Clock,
        ): CertificateCheck {
            require(profile != WireProfile.UNSECURED) { "Unsecured has no certificate" }
            val decoded =
                if (bytes.size >= HEADER_LENGTH && bytes.u8(0) == VERSION && bytes.u8(1) != profile.code) {
                    Decoded.Invalid(DecodeError.UNKNOWN_KIND)
                } else {
                    decode(bytes)
                }
            val certificate =
                when (decoded) {
                    is Decoded.Invalid -> return CertificateCheck.Invalid(decoded.error.code)
                    is Decoded.Valid -> decoded.value
                }
            val now = unixSeconds(clock)
            val body = bytes.copyOf(bytes.size - SIGNATURE_LENGTH)
            val ed25519Key = certificate.ed25519Key
            val rejection =
                when {
                    !crypto.ed25519Verify(ed25519Key, signingInput(body), certificate.signature) -> {
                        CertificateRejection.BAD_SIGNATURE
                    }

                    !Derivations(crypto).nodeId(ed25519Key).contentEquals(nodeId) -> {
                        CertificateRejection.NODE_ID_MISMATCH
                    }

                    now >= certificate.notAfter.toLong() + CLOCK_SKEW_SECONDS -> {
                        CertificateRejection.EXPIRED
                    }

                    now < certificate.notBefore.toLong() - CLOCK_SKEW_SECONDS -> {
                        CertificateRejection.NOT_YET_VALID
                    }

                    else -> {
                        null
                    }
                }
            return rejection?.let { CertificateCheck.Invalid(it.code) } ?: CertificateCheck.Valid(certificate)
        }
    }
}

/**
 * The static public keys an [IdentityCertificate] binds: the 32-byte [x25519Key] and, in
 * `PostQuantum` only, the 1,216-byte [xWingKey].
 */
public class StaticKeys(
    public val x25519Key: ByteArray,
    public val xWingKey: ByteArray?,
) {
    init {
        require(x25519Key.size == X25519_LENGTH) { "X25519 key must be $X25519_LENGTH bytes, not ${x25519Key.size}" }
        xWingKey?.let { key ->
            require(key.size == X_WING_LENGTH) { "X-Wing key must be $X_WING_LENGTH bytes, not ${key.size}" }
        }
    }

    private companion object {
        const val X25519_LENGTH = 32
        const val X_WING_LENGTH = 1_216
    }
}

/** The outcome of [IdentityCertificate.validate]: [Valid] or [Invalid]. */
public sealed interface CertificateCheck {
    /** Every validation rule holds for [certificate]. */
    public class Valid(
        public val certificate: IdentityCertificate,
    ) : CertificateCheck

    /** A rule failed; [code] is the vectors README error code of a [DecodeError] or a [CertificateRejection]. */
    public class Invalid(
        public val code: String,
    ) : CertificateCheck
}

/** Why a certificate with a valid layout was rejected (validation rules 3 to 6). */
public enum class CertificateRejection(
    public val code: String,
) {
    /** The Ed25519 signature doesn't verify. */
    BAD_SIGNATURE("bad-signature"),

    /** The certificate's identity key hashes to another NodeId than the peer claimed. */
    NODE_ID_MISMATCH("node-id-mismatch"),

    /** `now ≥ not_after + 30 s`. */
    EXPIRED("expired"),

    /** `now < not_before − 30 s`. */
    NOT_YET_VALID("not-yet-valid"),
}
