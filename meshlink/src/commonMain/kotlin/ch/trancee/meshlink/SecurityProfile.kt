package ch.trancee.meshlink

import ch.trancee.meshlink.internal.WireProfile

/**
 * The protection a mesh gives its traffic (ADR 0007 decision 5). Every node of a mesh uses the same
 * profile: the same mesh ID with another profile is another mesh. There is no default; the app
 * chooses one in [meshLinkConfig].
 */
public sealed interface SecurityProfile {
    /**
     * No encryption and no authentication: anyone in radio range can read, forge and replay
     * traffic. For tests, demos and content that is public anyway.
     */
    public data object Unsecured : SecurityProfile

    /**
     * Authenticated, encrypted links with classical cryptography: each link runs a Noise `XX`
     * handshake (`XXpsk3` with a mesh key) over X25519, ChaCha20-Poly1305 and SHA-256, and each
     * peer proves its NodeId with an Ed25519 identity certificate. Not resistant to a future
     * quantum computer that records traffic today.
     */
    public data object Classical : SecurityProfile

    /**
     * [Classical] hardened against a future quantum computer that records traffic today: each link
     * runs a Noise `XXhfs` handshake (`XXhfs+psk3` with a mesh key), which adds an ML-KEM-768
     * exchange to X25519, and each identity certificate also binds an X-Wing key. A link handshake
     * sends about 5 KB, so it takes several records each way.
     */
    public data object PostQuantum : SecurityProfile
}

/** The profile's one-byte code on the wire (spec "Security profiles"). */
internal val SecurityProfile.wireProfile: WireProfile
    get() =
        when (this) {
            SecurityProfile.Unsecured -> WireProfile.UNSECURED
            SecurityProfile.Classical -> WireProfile.CLASSICAL
            SecurityProfile.PostQuantum -> WireProfile.POST_QUANTUM
        }
