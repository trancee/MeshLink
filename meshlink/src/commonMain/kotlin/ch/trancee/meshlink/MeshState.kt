package ch.trancee.meshlink

/**
 * Where a [MeshLink] node is in its lifecycle (ADR 0007 decision 2), read from [MeshLink.state]:
 * [Stopped] → [Starting] → [Running] or [Blocked] → [Stopping] → [Stopped].
 */
public sealed interface MeshState {
    /** Not started, stopped, or closed. The node uses no radio. */
    public data object Stopped : MeshState

    /** [MeshLink.start] is in progress. */
    public data object Starting : MeshState

    /** Started, and the radio has everything it needs. */
    public data object Running : MeshState

    /**
     * Started, but the radio can't work until every requirement in [requirements] is met. The node
     * moves to [Running] by itself once they are; the app never needs to call [MeshLink.start] again.
     */
    public data class Blocked(
        /** What is missing; never empty. */
        public val requirements: Set<Requirement>,
    ) : MeshState

    /** [MeshLink.stop] is in progress. */
    public data object Stopping : MeshState
}

/**
 * Something the radio needs and doesn't have (ADR 0007 decision 3). MeshLink never asks the user
 * for anything itself: the app shows the prompt or settings screen.
 */
public sealed interface Requirement {
    /** Bluetooth is turned off. */
    public data object BluetoothOff : Requirement

    /** The app lacks the permission [kind]. */
    public data class PermissionDenied(
        /** The missing permission. */
        public val kind: PermissionKind,
    ) : Requirement

    /** This device can't run MeshLink, for example the iOS simulator, which has no Bluetooth. */
    public data object Unsupported : Requirement

    /** Another MeshLink node in this app holds the radio. */
    public data object RadioInUse : Requirement
}

/** A permission the radio needs, for [Requirement.PermissionDenied]. */
public enum class PermissionKind {
    /** Android 12 (API 31) and later: `BLUETOOTH_SCAN`. */
    BluetoothScan,

    /** Android 12 (API 31) and later: `BLUETOOTH_ADVERTISE`. */
    BluetoothAdvertise,

    /** Android 12 (API 31) and later: `BLUETOOTH_CONNECT`. */
    BluetoothConnect,

    /** Android 8 to 11 (API 26–30): the location permission that Bluetooth scanning needs. */
    Location,

    /** iOS: the Bluetooth authorization (`NSBluetoothAlwaysUsageDescription`). */
    Bluetooth,
}
