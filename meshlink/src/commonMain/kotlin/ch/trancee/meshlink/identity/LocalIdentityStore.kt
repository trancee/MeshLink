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
        val storedKeys = readStoredKeys(appId = appId, secureStorage = secureStorage)

        return when {
            storedKeys.isEmpty() ->
                createAndPersistIdentity(
                    appId = appId,
                    secureStorage = secureStorage,
                    provider = provider,
                )
            storedKeys.isIncomplete() -> throwIncompleteIdentityFailure(appId)
            else ->
                LocalIdentity.fromNoiseIdentity(
                    noiseIdentity = storedKeys.toNoiseIdentity(),
                    provider = provider,
                )
        }
    }

    private suspend fun readStoredKeys(appId: String, secureStorage: SecureStorage): StoredKeys {
        return StoredKeys(
            ed25519PrivateKey = secureStorage.read(key(appId, "ed25519-private")),
            ed25519PublicKey = secureStorage.read(key(appId, "ed25519-public")),
            x25519PrivateKey = secureStorage.read(key(appId, "x25519-private")),
            x25519PublicKey = secureStorage.read(key(appId, "x25519-public")),
        )
    }

    private suspend fun createAndPersistIdentity(
        appId: String,
        secureStorage: SecureStorage,
        provider: CryptoProvider,
    ): LocalIdentity {
        val identity = LocalIdentity.fromNoiseIdentity(NoiseIdentity.generate(provider), provider)
        persist(appId = appId, secureStorage = secureStorage, identity = identity)
        return identity
    }

    private fun throwIncompleteIdentityFailure(appId: String): Nothing {
        throw MeshLinkException.StorageFailure(
            message = "Stored local identity is incomplete for appId=$appId"
        )
    }

    private suspend fun persist(
        appId: String,
        secureStorage: SecureStorage,
        identity: LocalIdentity,
    ): Unit {
        secureStorage.write(
            key(appId, "ed25519-private"),
            identity.noiseIdentity.ed25519KeyPair.privateKey,
        )
        secureStorage.write(
            key(appId, "ed25519-public"),
            identity.noiseIdentity.ed25519KeyPair.publicKey,
        )
        secureStorage.write(
            key(appId, "x25519-private"),
            identity.noiseIdentity.x25519KeyPair.privateKey,
        )
        secureStorage.write(
            key(appId, "x25519-public"),
            identity.noiseIdentity.x25519KeyPair.publicKey,
        )
    }

    private fun key(appId: String, suffix: String): String {
        return "identity:$appId:$suffix"
    }

    private fun requireSized(value: ByteArray, expectedSize: Int, label: String): ByteArray {
        if (value.size != expectedSize) {
            throw MeshLinkException.StorageFailure(
                message = "Stored local identity $label key has invalid size ${value.size}"
            )
        }
        return value.copyOf()
    }

    private data class StoredKeys(
        val ed25519PrivateKey: ByteArray?,
        val ed25519PublicKey: ByteArray?,
        val x25519PrivateKey: ByteArray?,
        val x25519PublicKey: ByteArray?,
    ) {
        fun isEmpty(): Boolean {
            return allValues().all { value -> value == null }
        }

        fun isIncomplete(): Boolean {
            return allValues().any { value -> value == null }
        }

        fun toNoiseIdentity(): NoiseIdentity {
            return NoiseIdentity(
                ed25519KeyPair =
                    Ed25519KeyPair(
                        privateKey =
                            requireSized(
                                value = checkNotNull(ed25519PrivateKey),
                                expectedSize = KEY_SIZE_BYTES,
                                label = "ed25519 private",
                            ),
                        publicKey =
                            requireSized(
                                value = checkNotNull(ed25519PublicKey),
                                expectedSize = KEY_SIZE_BYTES,
                                label = "ed25519 public",
                            ),
                    ),
                x25519KeyPair =
                    X25519KeyPair(
                        privateKey =
                            requireSized(
                                value = checkNotNull(x25519PrivateKey),
                                expectedSize = KEY_SIZE_BYTES,
                                label = "x25519 private",
                            ),
                        publicKey =
                            requireSized(
                                value = checkNotNull(x25519PublicKey),
                                expectedSize = KEY_SIZE_BYTES,
                                label = "x25519 public",
                            ),
                    ),
            )
        }

        private fun allValues(): List<ByteArray?> {
            return listOf(ed25519PrivateKey, ed25519PublicKey, x25519PrivateKey, x25519PublicKey)
        }
    }

    private const val KEY_SIZE_BYTES: Int = 32
}
