package ch.trancee.meshlink

import ch.trancee.meshlink.internal.RouteDiscovery
import ch.trancee.meshlink.internal.UnsecuredMessaging
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * The values a [Preset] sets (ADR 0011 decisions 10 to 12). Each starts as the preset sets it; setting
 * it overrides the preset. [Preset.Custom]'s block and [meshLinkConfig]'s block set them, and
 * [MeshLinkConfigBuilder.build] validates the result.
 *
 * A value off governs only what this node originates, holds or does with its radio (ADR 0011 decision
 * 8). It never changes what the node relays or parses: a node with a feature off still relays other
 * nodes' frames of that feature.
 */
public open class PresetSettings internal constructor(
    base: PresetValues,
) {
    internal val knobs = Knobs(base)

    /** How the node picks its density class (ADR 0008 decision 8); [Density.Automatic] unless pinned. */
    public var density: Density by knobs.density

    /** How long the node rests between scan windows in each density class. */
    public var power: Power by knobs.power

    /**
     * Whether [MeshLink.send] may carry a message over other nodes (ADR 0008). Off, every message is sent
     * as [SendOptions.directOnly]: only a link to its destination carries it.
     */
    public var routedUnicast: Boolean by knobs.routedUnicast

    /**
     * Whether this node may originate broadcasts. Off, [MeshLink.broadcast] returns [SendResult.Rejected]
     * with [RejectionReason.BroadcastDisabled]; the node still relays other nodes' broadcasts.
     */
    public var broadcastOrigination: Boolean by knobs.broadcastOrigination

    /**
     * Whether this node writes broadcasts, its own and relayed, before the messages to single peers
     * waiting on each link (ADR 0011 amendment "presets and toggles built"). A waiting message lets at most 3
     * broadcasts go ahead of it, so messages keep at least one write in four on a busy link. Local scheduling only:
     * nothing is dropped and nothing changes on the wire.
     */
    public var broadcastPriority: Boolean by knobs.broadcastPriority

    /**
     * Whether this node holds small frames for other nodes while their destination is out of reach
     * (spec "Holding", ADR 0014). Off, it still relays them; it never holds its own, which wait in its
     * send queue.
     */
    public var holding: Boolean by knobs.holding

    /**
     * How long a message this node sends stays deliverable: 120 to 3,600 seconds (ADR 0014 decision 2).
     * Its expiry is the send time plus this. Until then this node retries it and relays may hold copies
     * of it for an absent destination; after it, plus 30 s of clock skew, it is
     * [DeliveryStatus.Expired] and every copy is dropped.
     */
    public var messageLifetimeSeconds: Int by knobs.messageLifetimeSeconds

    /** The read receipt settings; see [ReadReceiptsBuilder]. */
    public val readReceipts: ReadReceiptsBuilder = ReadReceiptsBuilder(knobs)

    /** The payload caps; see [LimitsBuilder]. */
    public val limits: LimitsBuilder = LimitsBuilder(knobs)

    /** Throws [MeshLinkException.InvalidConfig] for the first value outside its bounds. */
    internal fun checkPresetValues() {
        val lifetime = messageLifetimeSeconds
        if (lifetime !in LIFETIME_MIN_SECONDS..UnsecuredMessaging.LIFETIME_MAX_SECONDS) {
            throw MeshLinkException.InvalidConfig(
                "messageLifetimeSeconds",
                "messageLifetimeSeconds must be $LIFETIME_MIN_SECONDS to " +
                    "${UnsecuredMessaging.LIFETIME_MAX_SECONDS}, not $lifetime",
            )
        }
        limits.check()
    }
}

private const val LIFETIME_MIN_SECONDS = RouteDiscovery.LIFETIME_MIN_SECONDS.toInt()

/** The read receipt settings of [PresetSettings.readReceipts] (ADR 0013 decision 10). */
public class ReadReceiptsBuilder internal constructor(
    knobs: Knobs,
) {
    /** Whether a message sent with [ReadReceiptRequest.Default] asks its recipient for a read receipt. */
    public var requestByDefault: Boolean by knobs.readReceiptsRequestByDefault

    /**
     * Whether this node sends a read receipt when the app marks a message read that asked for one. Off,
     * the sender sees the message as unread; the node still relays other nodes' read receipts.
     */
    public var send: Boolean by knobs.readReceiptsSend
}

/**
 * One setting of [PresetSettings]: its [key] as [MeshLinkConfig.describe] prints it, and its value,
 * which is [overridden] once set.
 */
internal class Knob<T : Any>(
    val key: String,
    base: T,
) : ReadWriteProperty<Any?, T> {
    var value: T = base
        private set

    var overridden: Boolean = false
        private set

    override fun getValue(
        thisRef: Any?,
        property: KProperty<*>,
    ): T = value

    override fun setValue(
        thisRef: Any?,
        property: KProperty<*>,
        value: T,
    ) {
        this.value = value
        overridden = true
    }
}

/** The [Knob]s of [PresetSettings], starting at [base]. */
internal class Knobs(
    base: PresetValues,
) {
    val density = Knob("density", base.density)
    val power = Knob("power", base.power)
    val routedUnicast = Knob("routedUnicast", base.routedUnicast)
    val broadcastOrigination = Knob("broadcastOrigination", base.broadcastOrigination)
    val broadcastPriority = Knob("broadcastPriority", base.broadcastPriority)
    val holding = Knob("holding", base.holding)
    val messageLifetimeSeconds = Knob("messageLifetimeSeconds", base.messageLifetimeSeconds)
    val readReceiptsRequestByDefault = Knob("readReceipts.requestByDefault", base.readReceiptsRequestByDefault)
    val readReceiptsSend = Knob("readReceipts.send", base.readReceiptsSend)
    val maxUnicastPayload = Knob("limits.maxUnicastPayload", base.maxUnicastPayload)
    val maxBroadcastPayload = Knob("limits.maxBroadcastPayload", base.maxBroadcastPayload)

    /** Every knob, in the order [MeshLinkConfig.describe] prints them. */
    val all: List<Knob<*>> =
        listOf(
            density,
            power,
            routedUnicast,
            broadcastOrigination,
            broadcastPriority,
            holding,
            messageLifetimeSeconds,
            readReceiptsRequestByDefault,
            readReceiptsSend,
            maxUnicastPayload,
            maxBroadcastPayload,
        )

    /** The current values. */
    fun values(): PresetValues =
        PresetValues(
            density.value,
            power.value,
            routedUnicast.value,
            broadcastOrigination.value,
            broadcastPriority.value,
            holding.value,
            messageLifetimeSeconds.value,
            readReceiptsRequestByDefault.value,
            readReceiptsSend.value,
            maxUnicastPayload.value,
            maxBroadcastPayload.value,
        )
}
