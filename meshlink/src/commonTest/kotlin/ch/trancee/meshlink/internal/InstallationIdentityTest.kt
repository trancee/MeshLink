package ch.trancee.meshlink.internal

import ch.trancee.meshlink.crypto.sodium.SodiumCryptoProvider
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class InstallationIdentityTest {
    @Test
    fun inMemoryStoreCreatesTheKeyPairOnceAndThenReturnsIt() {
        // Arrange
        val store = InMemoryIdentityStore()
        val first = store.loadOrCreate(SodiumCryptoProvider)

        // Act
        val second = store.loadOrCreate(NoKeyPairs)

        // Assert
        assertSame(first, second)
    }

    @Test
    fun inMemoryStoreCreatesANewKeyPairAfterDelete() {
        // Arrange
        val store = InMemoryIdentityStore()
        val first = store.loadOrCreate(SodiumCryptoProvider)

        // Act
        val deleted = store.delete()
        val second = store.loadOrCreate(SodiumCryptoProvider)

        // Assert
        assertTrue(deleted)
        assertNotSame(first, second)
    }

    @Test
    fun nodeIdIsTheNodeIdOfTheStoredPublicKey() {
        // Arrange
        val store = InMemoryIdentityStore()

        // Act
        val identity = InstallationIdentity.loadOrCreate(store, SodiumCryptoProvider)

        // Assert: spec "NodeId", NodeId = H("node-id", ed25519_public_key).
        val keyPair = store.loadOrCreate(NoKeyPairs)
        assertContentEquals(Derivations(SodiumCryptoProvider).nodeId(keyPair.publicKey), identity!!.nodeId)
        assertSame(keyPair, identity.keyPair)
    }

    @Test
    fun anUnavailableStoreGivesNoIdentity() {
        // Arrange
        val store = PersistentIdentityStore(FakeSeedSlot(SlotRead.Unavailable))

        // Act
        val identity = InstallationIdentity.loadOrCreate(store, SodiumCryptoProvider)

        // Assert
        assertNull(identity)
    }
}

/** A [CryptoProvider] that fails the test if asked for a new key pair. */
internal object NoKeyPairs : CryptoProvider by SodiumCryptoProvider {
    override fun ed25519KeyPair(): Ed25519KeyPair = error("the store already holds a key pair")
}
