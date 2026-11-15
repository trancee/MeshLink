@file:OptIn(ExperimentalObjCRefinement::class)

package ch.trancee.meshlink.internal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC

/**
 * Where a platform keeps the installation identity: one long-term Ed25519 key pair per
 * installation (ADR 0003). Every node on one platform shares its store, and calls it only on the
 * platform's serial dispatcher.
 */
@HiddenFromObjC
public interface IdentityStore {
    /**
     * Returns the stored key pair. If the store is empty, creates one with [crypto], stores it,
     * and returns it. Returns `null` if the stored identity can't be read or the new one can't be
     * stored; a stored identity that can't be read is left as it is, never replaced.
     */
    public fun loadOrCreate(crypto: CryptoProvider): Ed25519KeyPair?

    /** Deletes the stored identity, if any. Returns `false` if the platform refused to delete it. */
    public fun delete(): Boolean
}

/** An [IdentityStore] in memory: the identity lasts as long as the store object. */
@HiddenFromObjC
public class InMemoryIdentityStore : IdentityStore {
    // Empty, or the one stored key pair.
    private val keyPair = MutableStateFlow<List<Ed25519KeyPair>>(emptyList())

    override fun loadOrCreate(crypto: CryptoProvider): Ed25519KeyPair =
        keyPair.updateAndGet { it.ifEmpty { listOf(crypto.ed25519KeyPair()) } }.single()

    override fun delete(): Boolean {
        keyPair.value = emptyList()
        return true
    }
}

/** What a [SeedSlot] holds. */
internal sealed interface SlotRead {
    /** Nothing: no identity was ever stored, or it was deleted. */
    data object Absent : SlotRead

    /** The stored [seed]; the reader wipes it after use. */
    class Present(
        val seed: ByteArray,
    ) : SlotRead

    /** Something the slot can't read now, or can't read at all: never overwritten. */
    data object Unavailable : SlotRead
}

/** The platform's protected storage for the 32-byte Ed25519 seed (ADR 0003). */
internal interface SeedSlot {
    /** What the slot holds. */
    fun read(): SlotRead

    /** Stores [seed] in the empty slot, without keeping [seed]. Returns `false` if it can't. */
    fun write(seed: ByteArray): Boolean

    /** Empties the slot. Returns `false` if it can't. */
    fun erase(): Boolean
}

/** An [IdentityStore] that keeps the seed of the key pair in [slot]. */
internal class PersistentIdentityStore(
    private val slot: SeedSlot,
) : IdentityStore {
    override fun loadOrCreate(crypto: CryptoProvider): Ed25519KeyPair? =
        when (val read = slot.read()) {
            SlotRead.Absent -> crypto.ed25519KeyPair().takeIf { slot.write(it.seed) }
            is SlotRead.Present -> restore(read.seed, crypto)
            SlotRead.Unavailable -> null
        }

    override fun delete(): Boolean = slot.erase()

    private fun restore(
        seed: ByteArray,
        crypto: CryptoProvider,
    ): Ed25519KeyPair? =
        try {
            if (seed.size == SEED_SIZE) crypto.ed25519KeyPair(seed) else null
        } finally {
            seed.fill(0)
        }

    private companion object {
        const val SEED_SIZE = 32
    }
}

/** A node's installation identity: its Ed25519 key pair and the [nodeId] derived from it. */
internal class InstallationIdentity(
    val keyPair: Ed25519KeyPair,
    val nodeId: ByteArray,
) {
    companion object {
        /**
         * Loads the identity from [store], creating it with [crypto] if the store is empty, or
         * returns `null` if the store can't give one.
         */
        fun loadOrCreate(
            store: IdentityStore,
            crypto: CryptoProvider,
        ): InstallationIdentity? {
            val keyPair = store.loadOrCreate(crypto) ?: return null
            return InstallationIdentity(keyPair, Derivations(crypto).nodeId(keyPair.publicKey))
        }
    }
}
