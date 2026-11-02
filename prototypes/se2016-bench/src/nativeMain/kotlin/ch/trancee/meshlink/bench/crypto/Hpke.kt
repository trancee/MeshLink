package ch.trancee.meshlink.bench.crypto

/**
 * HPKE base mode (RFC 9180) with X-Wing (0x647a, draft-ietf-hpke-pq-05), HKDF-SHA256 and ChaCha20Poly1305,
 * single-shot seal/open plus the context API needed by the A.5 vectors.
 */
internal object Hpke {
    private const val KEM_ID = 0x647a
    private const val KDF_ID = 0x0001
    private const val AEAD_ID = 0x0003
    private val suiteId = concat("HPKE".encodeToByteArray(), i2osp(KEM_ID, 2), i2osp(KDF_ID, 2), i2osp(AEAD_ID, 2))
    private val version = "HPKE-v1".encodeToByteArray()

    class Context(val key: ByteArray, val baseNonce: ByteArray, val exporterSecret: ByteArray) {
        private var sequence = 0L

        fun seal(aad: ByteArray, plaintext: ByteArray): ByteArray = Sodium.aeadEncrypt(key, nextNonce(), aad, plaintext)

        fun open(aad: ByteArray, ciphertext: ByteArray): ByteArray = Sodium.aeadDecrypt(key, nextNonce(), aad, ciphertext)

        fun export(context: ByteArray, length: Int): ByteArray =
            labeledExpand(exporterSecret, "sec", context, length)

        fun nonceFor(sequenceNumber: Long): ByteArray {
            val nonce = baseNonce.copyOf()
            for (index in 0 until 8) {
                val position = nonce.size - 1 - index
                nonce[position] = (nonce[position].toInt() xor (sequenceNumber ushr (8 * index)).toInt()).toByte()
            }
            return nonce
        }

        private fun nextNonce(): ByteArray = nonceFor(sequence++)
    }

    class Sender(val encapsulatedKey: ByteArray, val context: Context)

    fun setupBaseSender(recipientPublicKey: ByteArray, info: ByteArray, encapsSeed: ByteArray? = null): Sender {
        val encapsulation = Sodium.xwingEncapsulate(recipientPublicKey, encapsSeed)
        return Sender(encapsulation.ciphertext, keySchedule(encapsulation.sharedSecret, info))
    }

    fun setupBaseRecipient(encapsulatedKey: ByteArray, recipientSecretKey: ByteArray, info: ByteArray): Context =
        keySchedule(Sodium.xwingDecapsulate(recipientSecretKey, encapsulatedKey), info)

    /** Single-shot seal: `enc || ciphertext`. */
    fun seal(recipientPublicKey: ByteArray, info: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        val sender = setupBaseSender(recipientPublicKey, info)
        return concat(sender.encapsulatedKey, sender.context.seal(aad, plaintext))
    }

    fun open(recipientSecretKey: ByteArray, info: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray {
        if (sealed.size < Sodium.XWING_CIPHERTEXT_BYTES + Sodium.TAG_BYTES) throw CryptoFailure("sealed box truncated")
        val encapsulatedKey = sealed.copyOfRange(0, Sodium.XWING_CIPHERTEXT_BYTES)
        val context = setupBaseRecipient(encapsulatedKey, recipientSecretKey, info)
        return context.open(aad, sealed.copyOfRange(Sodium.XWING_CIPHERTEXT_BYTES, sealed.size))
    }

    fun keySchedule(sharedSecret: ByteArray, info: ByteArray): Context {
        val empty = ByteArray(0)
        val pskIdHash = labeledExtract(empty, "psk_id_hash", empty)
        val infoHash = labeledExtract(empty, "info_hash", info)
        val scheduleContext = concat(byteArrayOf(0), pskIdHash, infoHash)
        val secret = labeledExtract(sharedSecret, "secret", empty)
        return Context(
            key = labeledExpand(secret, "key", scheduleContext, 32),
            baseNonce = labeledExpand(secret, "base_nonce", scheduleContext, 12),
            exporterSecret = labeledExpand(secret, "exp", scheduleContext, 32),
        )
    }

    /**
     * hpke-pq-05 X-Wing DeriveKeyPair: `seed = SHAKE256.LabeledDerive(ikm, "DeriveKeyPair", "", 32)` with the KEM
     * suite_id, where LabeledDerive(ikm, label, ctx, L) = SHAKE256(ikm || "HPKE-v1" || suite_id ||
     * I2OSP(len(label), 2) || label || I2OSP(L, 2) || ctx, L).
     */
    fun deriveKeyPair(ikm: ByteArray): KeyPair {
        val label = "DeriveKeyPair".encodeToByteArray()
        val kemSuiteId = concat("KEM".encodeToByteArray(), i2osp(KEM_ID, 2))
        val labeled = concat(ikm, version, kemSuiteId, i2osp(label.size, 2), label, i2osp(32, 2))
        return Sodium.xwingSeedKeypair(Sodium.shake256(labeled, 32))
    }

    private fun labeledExtract(salt: ByteArray, label: String, ikm: ByteArray): ByteArray =
        Sodium.hkdfExtract(salt, concat(version, suiteId, label.encodeToByteArray(), ikm))

    private fun labeledExpand(prk: ByteArray, label: String, info: ByteArray, length: Int): ByteArray =
        Sodium.hkdfExpand(prk, concat(i2osp(length, 2), version, suiteId, label.encodeToByteArray(), info), length)

    private fun i2osp(value: Int, length: Int): ByteArray =
        ByteArray(length) { index -> (value ushr (8 * (length - 1 - index))).toByte() }
}

/** ADR 0012 identity certificate: fixed layout, Ed25519 signature over a domain prefix and every preceding byte. */
internal class Certificate(val bytes: ByteArray) {
    val identityKey: ByteArray get() = bytes.copyOfRange(2, 34)
    val noiseStaticKey: ByteArray get() = bytes.copyOfRange(34, 66)
    val xwingKey: ByteArray get() = bytes.copyOfRange(66, 66 + Sodium.XWING_PUBLIC_KEY_BYTES)

    fun verify(): Boolean {
        if (bytes.size != SIZE) return false
        val body = bytes.copyOfRange(0, SIZE - Sodium.SIGNATURE_BYTES)
        return Sodium.ed25519Verify(identityKey, concat(DOMAIN, body), bytes.copyOfRange(SIZE - Sodium.SIGNATURE_BYTES, SIZE))
    }

    companion object {
        const val SIZE = 2 + 32 + 32 + Sodium.XWING_PUBLIC_KEY_BYTES + 4 + 4 + Sodium.SIGNATURE_BYTES
        private val DOMAIN = "MeshLink identity certificate v1".encodeToByteArray()

        fun issue(identity: KeyPair, noiseStaticPublic: ByteArray, xwingPublic: ByteArray): Certificate {
            val validity = ByteArray(8) { if (it < 4) 0 else 0x7f }
            val body = concat(byteArrayOf(1, 2), identity.public, noiseStaticPublic, xwingPublic, validity)
            return Certificate(concat(body, Sodium.ed25519Sign(identity.secret, concat(DOMAIN, body))))
        }
    }
}

/** A post-quantum installation identity: Ed25519 identity key, X25519 Noise static, X-Wing static, certificate. */
internal class Identity(val signing: KeyPair, val noiseStatic: KeyPair, val xwing: KeyPair) {
    val certificate: Certificate = Certificate.issue(signing, noiseStatic.public, xwing.public)

    companion object {
        fun generate(): Identity = Identity(Sodium.ed25519Keypair(), Sodium.x25519Keypair(), Sodium.xwingKeypair())
    }
}
