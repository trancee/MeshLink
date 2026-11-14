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
}

/** The profile's one-byte code on the wire (spec "Security profiles"). */
internal val SecurityProfile.wireProfile: WireProfile
    get() =
        when (this) {
            SecurityProfile.Unsecured -> WireProfile.UNSECURED
        }
