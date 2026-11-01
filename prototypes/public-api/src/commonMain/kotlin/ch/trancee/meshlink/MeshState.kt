package ch.trancee.meshlink

/**
 * Lifecycle state of the local node, observed through [MeshLink.state].
 *
 * @sample ch.trancee.meshlink.samples.observeState
 */
public sealed class MeshState {
    /** Not participating in the mesh. Initial state, and the state after [MeshLink.stop]. */
    public data object Stopped : MeshState()

    /** [MeshLink.start] is in progress. */
    public data object Starting : MeshState()

    /** Discovering peers and carrying messages. */
    public data object Running : MeshState()

    /**
     * Started, but waiting for [requirements]; the node moves to [Running] by itself once all
     * of them are met. The application is responsible for requesting permissions.
     */
    public data class Blocked(public val requirements: Set<Requirement>) : MeshState()

    /** [MeshLink.stop] is in progress. */
    public data object Stopping : MeshState()
}

/** A platform condition that keeps a started node [MeshState.Blocked]. */
public sealed class Requirement {
    /** The Bluetooth radio is switched off. */
    public data object BluetoothOff : Requirement()

    /** The user has not granted the permission [kind]. */
    public data class PermissionDenied(public val kind: PermissionKind) : Requirement()

    /** The platform has no usable Bluetooth Low Energy support, for example the iOS simulator. */
    public data object Unsupported : Requirement()

    /** Another component holds the radio resources MeshLink needs. */
    public data object RadioInUse : Requirement()
}

/** Platform permission groups MeshLink depends on. */
public enum class PermissionKind {
    /** Android 12+ "Nearby devices" group; iOS Bluetooth authorization. */
    NearbyDevices,

    /** Location, required for Bluetooth scanning on Android 11 and older. */
    Location,
}

/**
 * Protection level shared by every node of a mesh. Nodes with different profiles never join
 * each other rather than downgrade.
 */
public enum class SecurityProfile {
    /** No encryption or authentication. Emits [DiagnosticCode.SecurityProfileNone] at every start. */
    None,

    /** Classical (pre-quantum) authenticated encryption. */
    Classical,

    /** Hybrid post-quantum authenticated encryption. */
    PostQuantum,
}
