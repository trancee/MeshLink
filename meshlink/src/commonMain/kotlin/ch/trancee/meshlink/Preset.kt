package ch.trancee.meshlink

import ch.trancee.meshlink.internal.BroadcastFrame
import ch.trancee.meshlink.internal.MessageContent

/**
 * A named set of defaults for one kind of app (ADR 0007 decision 6, ADR 0011 decisions 11 and 12). It
 * sets every [PresetSettings] value; [meshLinkConfig]'s block overrides them. A preset is local to a node:
 * nodes with different presets still form one mesh, and every node relays every other node's frames.
 *
 * No preset sets background behavior: the host app owns background modes and foreground services
 * (ADR 0011 decision 13).
 */
public sealed class Preset(
    internal val values: PresetValues,
) {
    /**
     * Text chat between people without a network: unicast and broadcast, holding for others, read receipts,
     * 30 min lifetime, 128 KiB messages.
     */
    public data object OfflineChat : Preset(chat(lifetimeSeconds = THIRTY_MINUTES))

    /**
     * Alerts that must reach as many nodes as possible: [PresetSettings.broadcastPriority] on, holding for
     * others, no read receipts, 1 h lifetime, 4 KiB messages.
     */
    public data object EmergencyBroadcast : Preset(
        chat(lifetimeSeconds = ONE_HOUR).copy(
            broadcastPriority = true,
            readReceiptsRequestByDefault = false,
            readReceiptsSend = false,
            maxUnicastPayload = MessageContent.PAYLOAD_SMALL_MAX,
        ),
    )

    /**
     * Finding who is nearby: direct-only unicast, no holding, no read receipts, 120 s lifetime, 4 KiB
     * messages, [Power.Responsive].
     */
    public data object ProximityDiscovery : Preset(
        quick(maxUnicastPayload = MessageContent.PAYLOAD_SMALL_MAX).copy(routedUnicast = false),
    )

    /**
     * Games between players in one room: density pinned to [DensityClass.Low] for the lowest latency, no
     * holding, no read receipts, 120 s lifetime, 128 KiB messages, [Power.Responsive].
     */
    public data object LocalMultiplayer : Preset(
        quick(maxUnicastPayload = MessageContent.PAYLOAD_MAX).copy(density = Density.Pinned(DensityClass.Low)),
    )

    /**
     * Teams spread over an area, such as rescue crews: unicast and broadcast, holding for others, read
     * receipts, 1 h lifetime, 128 KiB messages.
     */
    public data object FieldTeams : Preset(chat(lifetimeSeconds = ONE_HOUR))

    /**
     * An app's own preset: [base]'s values with `overrides` applied. [meshLinkConfig]'s
     * [MeshLinkConfigBuilder.build] validates the result.
     *
     * ```kotlin
     * val quietChat = Preset.Custom("Quiet chat", Preset.OfflineChat) { readReceipts.send = false }
     * ```
     */
    public class Custom(
        /** The preset's name, as [MeshLinkConfig.describe] prints it. */
        public val name: String,
        /** The preset whose values this one starts from. */
        public val base: Preset,
        overrides: PresetSettings.() -> Unit,
    ) : Preset(PresetSettings(base.values).apply(overrides).knobs.values()) {
        /** `Custom("<name>", base = <base>)`, as [MeshLinkConfig.describe] prints it. */
        override fun toString(): String = "Custom(\"$name\", base = $base)"
    }
}

/** Every value a [Preset] sets; see [PresetSettings] for each. */
internal data class PresetValues(
    val density: Density,
    val power: Power,
    val routedUnicast: Boolean,
    val broadcastOrigination: Boolean,
    val broadcastPriority: Boolean,
    val holding: Boolean,
    val messageLifetimeSeconds: Int,
    val readReceiptsRequestByDefault: Boolean,
    val readReceiptsSend: Boolean,
    val maxUnicastPayload: Int,
    val maxBroadcastPayload: Int,
)

private const val TWO_MINUTES = 120
private const val THIRTY_MINUTES = 1_800
private const val ONE_HOUR = 3_600

// The values of a preset for conversations; the other presets start from them.
private fun chat(lifetimeSeconds: Int): PresetValues =
    PresetValues(
        density = Density.Automatic,
        power = Power.Balanced,
        routedUnicast = true,
        broadcastOrigination = true,
        broadcastPriority = false,
        holding = true,
        messageLifetimeSeconds = lifetimeSeconds,
        readReceiptsRequestByDefault = true,
        readReceiptsSend = true,
        maxUnicastPayload = MessageContent.PAYLOAD_MAX,
        maxBroadcastPayload = BroadcastFrame.PAYLOAD_MAX,
    )

// The values of a preset for short-lived exchanges in one place.
private fun quick(maxUnicastPayload: Int): PresetValues =
    chat(lifetimeSeconds = TWO_MINUTES).copy(
        power = Power.Responsive,
        holding = false,
        readReceiptsRequestByDefault = false,
        readReceiptsSend = false,
        maxUnicastPayload = maxUnicastPayload,
    )
