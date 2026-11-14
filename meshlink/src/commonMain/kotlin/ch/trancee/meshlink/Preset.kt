package ch.trancee.meshlink

/**
 * A named set of defaults for one kind of app (ADR 0007 decision 6, ADR 0011 decision 12). A preset
 * is local to a node: nodes with different presets still form one mesh.
 *
 * In this release a preset sets only the default message lifetime.
 */
public sealed class Preset(
    internal val messageLifetimeSeconds: Int,
) {
    /** Text chat between people without a network; messages live 30 minutes. */
    public data object OfflineChat : Preset(THIRTY_MINUTES)

    /** Alerts that must reach as many nodes as possible; messages live 1 hour. */
    public data object EmergencyBroadcast : Preset(ONE_HOUR)

    /** Finding who is nearby; messages live 120 seconds. */
    public data object ProximityDiscovery : Preset(TWO_MINUTES)

    /** Games between players in one room; messages live 120 seconds. */
    public data object LocalMultiplayer : Preset(TWO_MINUTES)

    /** Teams spread over an area, such as rescue crews; messages live 1 hour. */
    public data object FieldTeams : Preset(ONE_HOUR)

    private companion object {
        const val TWO_MINUTES = 120
        const val THIRTY_MINUTES = 1_800
        const val ONE_HOUR = 3_600
    }
}
