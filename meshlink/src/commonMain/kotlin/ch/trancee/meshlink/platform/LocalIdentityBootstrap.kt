package ch.trancee.meshlink.platform

import ch.trancee.meshlink.crypto.CryptoProvider
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.identity.LocalIdentityStore
import ch.trancee.meshlink.storage.SecureStorage
import kotlinx.coroutines.runBlocking

internal fun loadOrCreateLocalIdentityBlocking(
    appId: String,
    secureStorage: SecureStorage,
    provider: CryptoProvider,
): LocalIdentity {
    return runBlocking {
        LocalIdentityStore.loadOrCreate(
            appId = appId,
            secureStorage = secureStorage,
            provider = provider,
        )
    }
}
