package ch.trancee.meshlink

/**
 * The root of every exception MeshLink throws (ADR 0007 decision 11). MeshLink throws only for
 * caller bugs, and every public function that can throw declares it with `@Throws`. Expected
 * outcomes are result values, never exceptions.
 */
public sealed class MeshLinkException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** [MeshLinkConfigBuilder.build] rejected the value of [setting]. */
    public class InvalidConfig(
        /** The name of the rejected setting, as in [MeshLinkConfig.describe]. */
        public val setting: String,
        message: String,
        cause: Throwable? = null,
    ) : MeshLinkException(message, cause)

    /** A call on a [MeshLink] node after [MeshLink.close]. */
    public class Closed : MeshLinkException("the MeshLink node is closed")
}
