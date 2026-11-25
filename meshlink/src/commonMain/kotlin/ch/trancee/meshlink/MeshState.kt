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

    /**
     * Started, and the radio has everything it needs. [limitations] lists what the node can't do on
     * this device and still runs without; empty on a full node.
     */
    public data class Running(
        /** What the node runs without; empty when it runs with every role. */
        public val limitations: Set<Limitation>,
    ) : MeshState

    /**
     * Started, but the radio can't work until every requirement in [requirements] is met. The node
     * moves to [Running] by itself once they are; the app never needs to call [MeshLink.start] again.
     * The exception is [Requirement.IdentityUnavailable], always the only requirement when present:
     * the node retries it on the next [MeshLink.start].
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

    /**
     * The installation identity can't be read, or a new one can't be stored (ADR 0003): for
     * example, on iOS before the first unlock after a restart, or after Android lost the Keystore
     * key that encrypts it. The node uses no radio, and doesn't retry by itself: the next
     * [MeshLink.start] tries again. MeshLink never replaces a stored identity on its own; if it
     * stays unreadable, [MeshLink.resetIdentity] deletes it so the next start creates a new one,
     * with a new [NodeId].
     */
    public data object IdentityUnavailable : Requirement
}

/**
 * Something a [MeshState.Running] node does without on this device (ADR 0007 decision 2). Unlike a
 * [Requirement], a limitation doesn't stop the node, and the app can't fix it.
 */
public sealed interface Limitation {
    /**
     * The node scans and connects to peers as central, but never advertises or accepts links
     * (ADR 0010 decision 6). Peers can't find it, so it joins the mesh only by connecting to them,
     * and two central-only nodes never link.
     */
    public data class CentralOnly(
        /** Why the node can't advertise. */
        public val reason: CentralOnlyReason,
    ) : Limitation
}

/** Why a node is [Limitation.CentralOnly]. */
public enum class CentralOnlyReason {
    /** Android: the Bluetooth adapter has no LE advertiser while Bluetooth is on. */
    NoAdvertiser,

    /**
     * Advertising failed to start or stopped during this run. The node tries again on its next
     * start, after Bluetooth turns off and on, or after a requirement comes and goes.
     */
    AdvertisingFailed,

    /**
     * Android 8.0 and 8.1 (API 26–27): the platform drops the upper half of the 32-bit MESH service
     * UUID it advertises, so no peer would recognize the advertisement.
     */
    LegacyUuidBug,
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
