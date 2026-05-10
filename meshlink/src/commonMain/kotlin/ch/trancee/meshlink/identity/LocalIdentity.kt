package ch.trancee.meshlink.identity

import ch.trancee.meshlink.api.PeerId

internal class LocalIdentity internal constructor(
    internal val peerId: PeerId,
    internal val identityFingerprint: String,
) {
    internal companion object {
        internal fun fromAppId(appId: String): LocalIdentity {
            return LocalIdentity(
                peerId = PeerId(appId),
                identityFingerprint = appId.reversed(),
            )
        }

        internal fun fromPeerId(peerId: PeerId, identitySeed: String): LocalIdentity {
            return LocalIdentity(
                peerId = peerId,
                identityFingerprint = identitySeed.reversed(),
            )
        }
    }
}
