package ch.trancee.meshlink.internal

/**
 * HPKE base mode (RFC 9180) with KEM X-Wing (`0x647A`, draft-ietf-hpke-pq-05), KDF HKDF-SHA256
 * (`0x0001`) and AEAD ChaCha20Poly1305 (`0x0003`), computed with [crypto] (ADR 0004). The
 * first-message seal (spec "First messages") is [seal]: `enc ‖ ct` with empty associated data.
 */
public class Hpke(
    private val crypto: CryptoProvider,
) {
    /** A sender's setup: the [enc] for the recipient and the sending [context]. */
    public class Sender(
        public val enc: ByteArray,
        public val context: HpkeContext,
    )

    /**
     * `SetupBaseS` to the X-Wing [publicKey] with the 64-byte encapsulation [seed], or
     * [CryptoFailure.INVALID_PUBLIC_KEY].
     */
    public fun sender(
        publicKey: ByteArray,
        info: ByteArray,
        seed: ByteArray,
    ): CryptoResult<Sender> = sender(crypto.xWingEncapsulate(publicKey, seed), info)

    /**
     * `SetupBaseR` with the 32-byte X-Wing [secretKey], or [CryptoFailure.INVALID_CIPHERTEXT] for an
     * [enc] whose X25519 part has low order.
     */
    public fun receiver(
        secretKey: ByteArray,
        enc: ByteArray,
        info: ByteArray,
    ): CryptoResult<HpkeContext> =
        when (val shared = crypto.xWingDecapsulate(secretKey, enc)) {
            is CryptoResult.Ok -> CryptoResult.Ok(keySchedule(shared.value, info))
            is CryptoResult.Failed -> shared
        }

    /** `enc ‖ ct`: [plaintext] sealed to [publicKey] with fresh randomness and empty associated data. */
    public fun seal(
        publicKey: ByteArray,
        info: ByteArray,
        plaintext: ByteArray,
    ): CryptoResult<ByteArray> = seal(sender(crypto.xWingEncapsulate(publicKey), info), plaintext)

    /** `enc ‖ ct` as [seal], with the 64-byte encapsulation [seed]. */
    public fun seal(
        publicKey: ByteArray,
        info: ByteArray,
        plaintext: ByteArray,
        seed: ByteArray,
    ): CryptoResult<ByteArray> = seal(sender(publicKey, info, seed), plaintext)

    /**
     * The plaintext of an `enc ‖ ct` [payload] with empty associated data. A payload too short for
     * `enc` and a tag fails as [CryptoFailure.AUTHENTICATION_FAILED], like a tag that doesn't verify.
     */
    public fun open(
        secretKey: ByteArray,
        info: ByteArray,
        payload: ByteArray,
    ): CryptoResult<ByteArray> {
        if (payload.size < OVERHEAD) return CryptoResult.Failed(CryptoFailure.AUTHENTICATION_FAILED)
        val enc = payload.copyOf(ENC_LENGTH)
        return when (val receiver = receiver(secretKey, enc, info)) {
            is CryptoResult.Ok -> receiver.value.open(EMPTY, payload.copyOfRange(ENC_LENGTH, payload.size))
            is CryptoResult.Failed -> receiver
        }
    }

    private fun sender(
        encapsulation: CryptoResult<KemEncapsulation>,
        info: ByteArray,
    ): CryptoResult<Sender> =
        when (encapsulation) {
            is CryptoResult.Ok -> {
                val context = keySchedule(encapsulation.value.sharedSecret, info)
                CryptoResult.Ok(Sender(encapsulation.value.ciphertext, context))
            }

            is CryptoResult.Failed -> {
                encapsulation
            }
        }

    private fun seal(
        sender: CryptoResult<Sender>,
        plaintext: ByteArray,
    ): CryptoResult<ByteArray> =
        when (sender) {
            is CryptoResult.Ok -> CryptoResult.Ok(sender.value.enc + sender.value.context.seal(EMPTY, plaintext))
            is CryptoResult.Failed -> sender
        }

    /** RFC 9180 `KeySchedule` in base mode: empty `psk` and `psk_id`. */
    private fun keySchedule(
        sharedSecret: ByteArray,
        info: ByteArray,
    ): HpkeContext {
        val labels = HpkeLabels(crypto)
        val pskIdHash = labels.extract(EMPTY, "psk_id_hash", EMPTY)
        val infoHash = labels.extract(EMPTY, "info_hash", info)
        val context = byteArrayOf(MODE_BASE) + pskIdHash + infoHash
        val secret = labels.extract(sharedSecret, "secret", EMPTY)
        return HpkeContext(
            labels,
            key = labels.expand(secret, "key", context, KEY_LENGTH),
            baseNonce = labels.expand(secret, "base_nonce", context, NONCE_LENGTH),
            exporterSecret = labels.expand(secret, "exp", context, HASH_LENGTH),
        )
    }

    public companion object {
        /** X-Wing's `enc`, in bytes. */
        public const val ENC_LENGTH: Int = 1120

        /** What [seal] adds to a plaintext: `enc` and the 16-byte tag, 1,136 bytes. */
        public const val OVERHEAD: Int = ENC_LENGTH + 16
    }
}

/**
 * An HPKE encryption context (RFC 9180 section 5.2): its [key], [baseNonce] and [exporterSecret].
 * Its sequence number counts successful [seal]s or [open]s; a sender seals with one context and its
 * recipient opens with another, in the same order.
 */
public class HpkeContext internal constructor(
    private val labels: HpkeLabels,
    public val key: ByteArray,
    public val baseNonce: ByteArray,
    public val exporterSecret: ByteArray,
) {
    private var sequence = 0L

    /** `ContextS.Seal(aad, pt)`: [plaintext] with its 16-byte tag appended. */
    public fun seal(
        associatedData: ByteArray,
        plaintext: ByteArray,
    ): ByteArray {
        val ciphertext = labels.crypto.chaCha20Poly1305Seal(key, nonce(), plaintext, associatedData)
        sequence++
        return ciphertext
    }

    /** `ContextR.Open(aad, ct)`; a failed tag leaves the sequence number unchanged. */
    public fun open(
        associatedData: ByteArray,
        ciphertext: ByteArray,
    ): CryptoResult<ByteArray> {
        val opened = labels.crypto.chaCha20Poly1305Open(key, nonce(), ciphertext, associatedData)
        if (opened is CryptoResult.Ok) sequence++
        return opened
    }

    /** `Context.Export(exporter_context, L)`: [length] bytes. */
    public fun export(
        exporterContext: ByteArray,
        length: Int,
    ): ByteArray = labels.expand(exporterSecret, "sec", exporterContext, length)

    /** `base_nonce XOR I2OSP(seq, 12)`. */
    private fun nonce(): ByteArray {
        val nonce = baseNonce.copyOf()
        for (index in 0 until Long.SIZE_BYTES) {
            val shift = index * Byte.SIZE_BITS
            val at = nonce.size - 1 - index
            nonce[at] = (nonce[at].toInt() xor (sequence ushr shift).toInt()).toByte()
        }
        return nonce
    }
}

/** RFC 9180 `LabeledExtract` and `LabeledExpand` for the suite X-Wing, HKDF-SHA256, ChaCha20Poly1305. */
internal class HpkeLabels(
    val crypto: CryptoProvider,
) {
    fun extract(
        salt: ByteArray,
        label: String,
        ikm: ByteArray,
    ): ByteArray = crypto.hkdfSha256Extract(salt, VERSION + SUITE + label.encodeToByteArray() + ikm)

    fun expand(
        prk: ByteArray,
        label: String,
        info: ByteArray,
        length: Int,
    ): ByteArray {
        val labeled = byteArrayOf((length ushr Byte.SIZE_BITS).toByte(), length.toByte()) + VERSION + SUITE
        return crypto.hkdfSha256Expand(prk, labeled + label.encodeToByteArray() + info, length)
    }
}

private const val MODE_BASE: Byte = 0x00
private const val KEY_LENGTH = 32
private const val NONCE_LENGTH = 12
private const val HASH_LENGTH = 32
private val EMPTY = ByteArray(0)
private val VERSION = "HPKE-v1".encodeToByteArray()

/** `suite_id = "HPKE" ‖ I2OSP(kem_id, 2) ‖ I2OSP(kdf_id, 2) ‖ I2OSP(aead_id, 2)`. */
private val SUITE = "HPKE".encodeToByteArray() + byteArrayOf(0x64, 0x7A, 0x00, 0x01, 0x00, 0x03)
