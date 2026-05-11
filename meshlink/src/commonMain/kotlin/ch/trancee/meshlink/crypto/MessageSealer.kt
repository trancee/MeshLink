package ch.trancee.meshlink.crypto

import ch.trancee.meshlink.api.MeshLinkException
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.trust.TrustRecord
import ch.trancee.meshlink.wire.ReadBuffer
import ch.trancee.meshlink.wire.WriteBuffer

internal object MessageSealer {
    internal fun seal(
        plaintext: ByteArray,
        senderIdentity: LocalIdentity,
        recipientTrust: TrustRecord,
    ): ByteArray {
        val provider = senderIdentity.cryptoProvider
        val associatedData =
            associatedData(
                senderEd25519PublicKey = senderIdentity.ed25519PublicKey,
                senderX25519PublicKey = senderIdentity.x25519PublicKey,
                recipientEd25519PublicKey = recipientTrust.ed25519PublicKey,
                recipientX25519PublicKey = recipientTrust.x25519PublicKey,
            )
        val sharedSecret =
            provider.x25519(
                privateKey = senderIdentity.noiseIdentity.x25519KeyPair.privateKey,
                publicKey = recipientTrust.x25519PublicKey,
            )
        requireContributorySharedSecret(sharedSecret)
        val key =
            deriveEncryptionKey(
                provider = provider,
                sharedSecret = sharedSecret,
                associatedData = associatedData,
            )
        val nonce = provider.randomBytes(NONCE_SIZE_BYTES)
        val ciphertext =
            provider.chacha20Poly1305Seal(
                key = key,
                nonce = nonce,
                aad = associatedData,
                plaintext = plaintext,
            )
        val signature =
            provider.ed25519Sign(
                privateKey = senderIdentity.noiseIdentity.ed25519KeyPair.privateKey,
                message = associatedData + nonce + ciphertext,
            )

        val buffer = WriteBuffer()
        buffer.writeByte(CURRENT_VERSION.toByte())
        buffer.writeIntLittleEndian(nonce.size)
        buffer.writeBytes(nonce)
        buffer.writeIntLittleEndian(ciphertext.size)
        buffer.writeBytes(ciphertext)
        buffer.writeIntLittleEndian(signature.size)
        buffer.writeBytes(signature)
        return buffer.toByteArray()
    }

    internal fun open(
        sealedPayload: ByteArray,
        recipientIdentity: LocalIdentity,
        senderTrust: TrustRecord,
    ): ByteArray {
        val buffer = ReadBuffer(sealedPayload)
        val version = buffer.readByte().toInt() and 0xFF
        if (version != CURRENT_VERSION) {
            throw MeshLinkException.CryptoFailure("Unsupported message sealer version $version")
        }
        val nonce = buffer.readBytes(buffer.readIntLittleEndian())
        val ciphertext = buffer.readBytes(buffer.readIntLittleEndian())
        val signature = buffer.readBytes(buffer.readIntLittleEndian())

        val provider = recipientIdentity.cryptoProvider
        val associatedData =
            associatedData(
                senderEd25519PublicKey = senderTrust.ed25519PublicKey,
                senderX25519PublicKey = senderTrust.x25519PublicKey,
                recipientEd25519PublicKey = recipientIdentity.ed25519PublicKey,
                recipientX25519PublicKey = recipientIdentity.x25519PublicKey,
            )
        val isValidSignature =
            provider.ed25519Verify(
                publicKey = senderTrust.ed25519PublicKey,
                message = associatedData + nonce + ciphertext,
                signature = signature,
            )
        if (!isValidSignature) {
            throw MeshLinkException.CryptoFailure("Signed payload verification failed")
        }

        val sharedSecret =
            provider.x25519(
                privateKey = recipientIdentity.noiseIdentity.x25519KeyPair.privateKey,
                publicKey = senderTrust.x25519PublicKey,
            )
        requireContributorySharedSecret(sharedSecret)
        val key =
            deriveEncryptionKey(
                provider = provider,
                sharedSecret = sharedSecret,
                associatedData = associatedData,
            )
        return provider.chacha20Poly1305Open(
            key = key,
            nonce = nonce,
            aad = associatedData,
            ciphertext = ciphertext,
        )
    }

    internal fun seal(plaintext: ByteArray, identityFingerprint: String): ByteArray {
        return xor(plaintext, identityFingerprint.encodeToByteArray())
    }

    internal fun open(ciphertext: ByteArray, identityFingerprint: String): ByteArray {
        return xor(ciphertext, identityFingerprint.encodeToByteArray())
    }

    private fun deriveEncryptionKey(
        provider: CryptoProvider,
        sharedSecret: ByteArray,
        associatedData: ByteArray,
    ): ByteArray {
        val salt = provider.sha256(NOISE_K_DOMAIN.encodeToByteArray() + associatedData)
        return hkdfSha256(
            provider = provider,
            salt = salt,
            ikm = sharedSecret,
            info = MESSAGE_KEY_INFO.encodeToByteArray(),
            outputLength = KEY_SIZE_BYTES,
        )
    }

    private fun associatedData(
        senderEd25519PublicKey: ByteArray,
        senderX25519PublicKey: ByteArray,
        recipientEd25519PublicKey: ByteArray,
        recipientX25519PublicKey: ByteArray,
    ): ByteArray {
        val buffer = WriteBuffer()
        buffer.writeIntLittleEndian(senderEd25519PublicKey.size)
        buffer.writeBytes(senderEd25519PublicKey)
        buffer.writeIntLittleEndian(senderX25519PublicKey.size)
        buffer.writeBytes(senderX25519PublicKey)
        buffer.writeIntLittleEndian(recipientEd25519PublicKey.size)
        buffer.writeBytes(recipientEd25519PublicKey)
        buffer.writeIntLittleEndian(recipientX25519PublicKey.size)
        buffer.writeBytes(recipientX25519PublicKey)
        return buffer.toByteArray()
    }

    private fun requireContributorySharedSecret(sharedSecret: ByteArray): Unit {
        if (sharedSecret.isEmpty() || sharedSecret.all { byte -> byte == 0.toByte() }) {
            throw MeshLinkException.CryptoFailure("Derived X25519 shared secret is invalid")
        }
    }

    private fun xor(input: ByteArray, key: ByteArray): ByteArray {
        if (key.isEmpty()) {
            return input.copyOf()
        }
        return ByteArray(input.size) { index ->
            val value = input[index].toInt() xor key[index % key.size].toInt()
            value.toByte()
        }
    }

    private const val CURRENT_VERSION: Int = 1
    private const val KEY_SIZE_BYTES: Int = 32
    private const val NONCE_SIZE_BYTES: Int = 12
    private const val NOISE_K_DOMAIN: String = "MeshLink_Noise_K_25519_ChaChaPoly_SHA256"
    private const val MESSAGE_KEY_INFO: String = "meshlink-e2e-message-key-v1"
}
