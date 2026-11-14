package ch.trancee.meshlink.internal

import ch.trancee.meshlink.crypto.sodium.SodiumCryptoProvider
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertSame

class InstallationIdentityTest {
    @Test
    fun storeCreatesTheKeyPairOnceAndThenReturnsIt() {
        // Arrange
        val store = InMemoryIdentityStore()
        var created = 0
        val create = {
            created++
            SodiumCryptoProvider.ed25519KeyPair()
        }

        // Act
        val first = store.loadOrCreate(create)
        val second = store.loadOrCreate(create)

        // Assert
        assertSame(first, second)
        assertEquals(1, created)
    }

    @Test
    fun nodeIdIsTheNodeIdOfTheStoredPublicKey() {
        // Arrange
        val store = InMemoryIdentityStore()

        // Act
        val identity = InstallationIdentity.loadOrCreate(store, SodiumCryptoProvider)

        // Assert: spec "NodeId", NodeId = H("node-id", ed25519_public_key).
        assertContentEquals(Derivations(SodiumCryptoProvider).nodeId(identity.keyPair.publicKey), identity.nodeId)
        assertSame(identity.keyPair, store.loadOrCreate { error("the store already holds a key pair") })
    }

    @Test
    fun loadingAgainFromTheSameStoreKeepsTheNodeId() {
        // Arrange
        val store = InMemoryIdentityStore()
        val first = InstallationIdentity.loadOrCreate(store, SodiumCryptoProvider)

        // Act
        val second = InstallationIdentity.loadOrCreate(store, SodiumCryptoProvider)

        // Assert
        assertContentEquals(first.nodeId, second.nodeId)
    }
}
