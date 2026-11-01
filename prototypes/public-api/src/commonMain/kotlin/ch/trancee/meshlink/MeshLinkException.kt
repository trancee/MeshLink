package ch.trancee.meshlink

/**
 * Root of every exception MeshLink throws.
 *
 * MeshLink throws only for caller bugs, and only from functions annotated with
 * `@Throws(MeshLinkException::class)`, so Swift sees them as `Error`. Expected outcomes are sealed
 * results ([SendResult], [DeliveryStatus], [MeshState.Blocked]) and every Flow is exception-free.
 */
public sealed class MeshLinkException(message: String) : Exception(message) {
    /**
     * [MeshLinkConfig.Builder.build] found an invalid setting.
     *
     * @property setting name of the offending setting, as printed by [MeshLinkConfig.describe].
     * @property problem what is wrong with it.
     */
    public class InvalidConfig(public val setting: String, public val problem: String) :
        MeshLinkException("Invalid setting '$setting': $problem")

    /** [NodeId.fromHex] received text that is not a node identity. */
    public class InvalidNodeId(public val input: String) :
        MeshLinkException("Not a node identity: '${input.take(INPUT_PREVIEW)}'")

    /** [MeshLink.create] was called while another instance on the same platform is still open. */
    public class InstanceAlreadyOpen :
        MeshLinkException("Another MeshLink instance is open in this process; close it first")

    /** The instance was used after [MeshLink.close]. */
    public class Closed : MeshLinkException("This MeshLink instance is closed")

    private companion object {
        const val INPUT_PREVIEW = 40
    }
}
