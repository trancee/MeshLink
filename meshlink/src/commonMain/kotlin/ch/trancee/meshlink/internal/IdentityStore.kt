@file:OptIn(ExperimentalObjCRefinement::class)

package ch.trancee.meshlink.internal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC

/**
 * Where a platform keeps the installation identity: one long-term Ed25519 key pair per
 * installation (ADR 0003). Every node on one platform shares its store.
 */
@HiddenFromObjC
public interface IdentityStore {
    /**
     * Returns the stored key pair. If there is none, stores the result of [create] and returns it;
     * concurrent callers all get the one stored key pair.
     */
    public fun loadOrCreate(create: () -> Ed25519KeyPair): Ed25519KeyPair
}

/** An [IdentityStore] in memory: the identity lasts as long as the store object. */
@HiddenFromObjC
public class InMemoryIdentityStore : IdentityStore {
    // Empty, or the one stored key pair.
    private val keyPair = MutableStateFlow<List<Ed25519KeyPair>>(emptyList())

    override fun loadOrCreate(create: () -> Ed25519KeyPair): Ed25519KeyPair =
        keyPair.updateAndGet { it.ifEmpty { listOf(create()) } }.single()
}

/** A node's installation identity: its Ed25519 key pair and the [nodeId] derived from it. */
internal class InstallationIdentity(
    val keyPair: Ed25519KeyPair,
    val nodeId: ByteArray,
) {
    companion object {
        /** Loads the identity from [store], creating it with [crypto] if the store is empty. */
        fun loadOrCreate(
            store: IdentityStore,
            crypto: CryptoProvider,
        ): InstallationIdentity {
            val keyPair = store.loadOrCreate(crypto::ed25519KeyPair)
            return InstallationIdentity(keyPair, Derivations(crypto).nodeId(keyPair.publicKey))
        }
    }
}
