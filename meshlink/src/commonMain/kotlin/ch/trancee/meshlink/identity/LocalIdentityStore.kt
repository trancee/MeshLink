package ch.trancee.meshlink.identity

import ch.trancee.meshlink.api.MeshLinkException
import ch.trancee.meshlink.crypto.CryptoProvider
import ch.trancee.meshlink.crypto.Ed25519KeyPair
import ch.trancee.meshlink.crypto.NoiseIdentity
import ch.trancee.meshlink.crypto.X25519KeyPair
import ch.trancee.meshlink.storage.SecureStorage

internal object LocalIdentityStore {
    internal suspend fun loadOrCreate(
        appId: String,
        secureStorage: SecureStorage,
        provider: CryptoProvider,
    ): LocalIdentity {
        val storedEd25519PrivateKey = secureStorage.read(key(appId, "ed25519-private"))
        val storedEd25519PublicKey = secureStorage.read(key(appId, "ed25519-public"))
        val storedX25519PrivateKey = secureStorage.read(key(appId, "x25519-private"))
        val storedX25519PublicKey = secureStorage.read(key(appId, "x25519-public"))

        val storedValues = listOf(
            storedEd25519PrivateKey,
            storedEd25519PublicKey,
            storedX25519PrivateKey,
            storedX25519PublicKey,
        )
        if (storedValues.all { value -> value == null }) {
            val identity = LocalIdentity.fromNoiseIdentity(NoiseIdentity.generate(provider), provider)
            persist(appId = appId, secureStorage = secureStorage, identity = identity)
            return identity
        }
        if (storedValues.any { value -> value == null }) {
            throw MeshLinkException.StorageFailure(
                message = "Stored local identity is incomplete for appId=$appId",
            )
        }

        return LocalIdentity.fromNoiseIdentity(
            noiseIdentity = NoiseIdentity(
                ed25519KeyPair = Ed25519KeyPair(
                    privateKey = requireSized(storedEd25519PrivateKey!!, KEY_SIZE_BYTES, "ed25519 private"),
                    publicKey = requireSized(storedEd25519PublicKey!!, KEY_SIZE_BYTES, "ed25519 public"),
                ),
                x25519KeyPair = X25519KeyPair(
                    privateKey = requireSized(storedX25519PrivateKey!!, KEY_SIZE_BYTES, "x25519 private"),
                    publicKey = requireSized(storedX25519PublicKey!!, KEY_SIZE_BYTES, "x25519 public"),
                ),
            ),
            provider = provider,
        )
    }

    private suspend fun persist(appId: String, secureStorage: SecureStorage, identity: LocalIdentity): Unit {
        secureStorage.write(key(appId, "ed25519-private"), identity.noiseIdentity.ed25519KeyPair.privateKey)
        secureStorage.write(key(appId, "ed25519-public"), identity.noiseIdentity.ed25519KeyPair.publicKey)
        secureStorage.write(key(appId, "x25519-private"), identity.noiseIdentity.x25519KeyPair.privateKey)
        secureStorage.write(key(appId, "x25519-public"), identity.noiseIdentity.x25519KeyPair.publicKey)
    }

    private fun key(appId: String, suffix: String): String {
        return "identity:$appId:$suffix"
    }

    private fun requireSized(value: ByteArray, expectedSize: Int, label: String): ByteArray {
        if (value.size != expectedSize) {
            throw MeshLinkException.StorageFailure(
                message = "Stored local identity $label key has invalid size ${value.size}",
            )
        }
        return value.copyOf()
    }

    private const val KEY_SIZE_BYTES: Int = 32
}
