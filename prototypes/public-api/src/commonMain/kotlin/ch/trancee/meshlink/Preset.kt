package ch.trancee.meshlink

/**
 * A named bundle of enabled features and settings. Presets are local to a node: nodes with
 * different presets share a mesh as long as mesh ID, security profile and mesh key match.
 *
 * The values of the built-ins are placeholders for this prototype; print them with
 * [MeshLinkConfig.describe].
 *
 * @sample ch.trancee.meshlink.samples.customPreset
 */
public sealed class Preset {
    /** Display name used by [MeshLinkConfig.describe] and diagnostics. */
    public abstract val name: String

    internal abstract val settings: PresetSettings

    /** One-to-one and small-group chat with delivery and read receipts. */
    public data object OfflineChat : Preset() {
        override val name: String = "OfflineChat"
        override val settings: PresetSettings = PresetSettings(4096, 64, 6, 3600, false, true, true, DutyCycle.Balanced)
    }

    /** Wide-reach mesh broadcasts with store-and-forward. */
    public data object EmergencyBroadcast : Preset() {
        override val name: String = "EmergencyBroadcast"
        override val settings: PresetSettings = PresetSettings(512, 128, 10, 1800, true, true, false, DutyCycle.Balanced)
    }

    /** Peer discovery with proximity; small messages only. */
    public data object ProximityDiscovery : Preset() {
        override val name: String = "ProximityDiscovery"
        override val settings: PresetSettings = PresetSettings(256, 16, 1, 60, false, false, false, DutyCycle.LowPower)
    }

    /** Low-latency messages between a handful of nearby players. */
    public data object LocalMultiplayer : Preset() {
        override val name: String = "LocalMultiplayer"
        override val settings: PresetSettings = PresetSettings(512, 256, 2, 5, false, true, false, DutyCycle.LowLatency)
    }

    /** Teams that drift in and out of range; store-and-forward with long message lifetimes. */
    public data object FieldTeams : Preset() {
        override val name: String = "FieldTeams"
        override val settings: PresetSettings = PresetSettings(4096, 64, 8, 86_400, true, true, true, DutyCycle.LowPower)
    }

    /**
     * An application-defined preset: [base] with [overrides] applied. Validated by
     * [MeshLinkConfig.Builder.build].
     */
    public class Custom(
        override val name: String,
        public val base: Preset,
        public val overrides: PresetOverrides,
    ) : Preset() {
        override val settings: PresetSettings get() = overrides.applyTo(base.settings)

        override fun toString(): String = "Custom($name, base=${base.name})"
    }
}

/** Radio scheduling trade-off between latency and battery. */
public enum class DutyCycle {
    /** Lowest latency, highest battery use. */
    LowLatency,

    /** Default trade-off. */
    Balanced,

    /** Lowest battery use, highest latency. */
    LowPower,
}

/**
 * Settings that a [Preset.Custom] changes relative to its base; `null` keeps the base value.
 *
 * @sample ch.trancee.meshlink.samples.customPreset
 */
public class PresetOverrides private constructor(
    /** Largest accepted message payload. */
    public val maxPayloadBytes: Int?,
    /** Inbox capacity in messages; each sender may occupy at most a quarter of it. */
    public val inboxCapacity: Int?,
    /** Largest hop limit a message or mesh broadcast may use. */
    public val maxHopLimit: Int?,
    /** Default and maximum message lifetime. */
    public val messageLifetimeSeconds: Int?,
    /** Whether messages for unreachable peers are held until a route appears. */
    public val storeAndForward: Boolean?,
    /** Whether senders may request delivery receipts. */
    public val deliveryReceipts: Boolean?,
    /** Whether senders may request read receipts. */
    public val readReceipts: Boolean?,
    /** Radio duty cycle. */
    public val dutyCycle: DutyCycle?,
) {
    internal fun applyTo(base: PresetSettings): PresetSettings = PresetSettings(
        maxPayloadBytes ?: base.maxPayloadBytes,
        inboxCapacity ?: base.inboxCapacity,
        maxHopLimit ?: base.maxHopLimit,
        messageLifetimeSeconds ?: base.messageLifetimeSeconds,
        storeAndForward ?: base.storeAndForward,
        deliveryReceipts ?: base.deliveryReceipts,
        readReceipts ?: base.readReceipts,
        dutyCycle ?: base.dutyCycle,
    )

    internal fun overridden(): Set<String> = buildSet {
        if (maxPayloadBytes != null) add("maxPayloadBytes")
        if (inboxCapacity != null) add("inboxCapacity")
        if (maxHopLimit != null) add("maxHopLimit")
        if (messageLifetimeSeconds != null) add("messageLifetimeSeconds")
        if (storeAndForward != null) add("storeAndForward")
        if (deliveryReceipts != null) add("deliveryReceipts")
        if (readReceipts != null) add("readReceipts")
        if (dutyCycle != null) add("dutyCycle")
    }

    /** Collects overrides; usable as a Kotlin DSL scope or as a fluent Swift builder. */
    @MeshLinkDsl
    public class Builder {
        /** See [PresetOverrides.maxPayloadBytes]. */
        public var maxPayloadBytes: Int? = null

        /** See [PresetOverrides.inboxCapacity]. */
        public var inboxCapacity: Int? = null

        /** See [PresetOverrides.maxHopLimit]. */
        public var maxHopLimit: Int? = null

        /** See [PresetOverrides.messageLifetimeSeconds]. */
        public var messageLifetimeSeconds: Int? = null

        /** See [PresetOverrides.storeAndForward]. */
        public var storeAndForward: Boolean? = null

        /** See [PresetOverrides.deliveryReceipts]. */
        public var deliveryReceipts: Boolean? = null

        /** See [PresetOverrides.readReceipts]. */
        public var readReceipts: Boolean? = null

        /** See [PresetOverrides.dutyCycle]. */
        public var dutyCycle: DutyCycle? = null

        /** Fluent setter for [maxPayloadBytes]. */
        public fun maxPayloadBytes(bytes: Int): Builder = apply { maxPayloadBytes = bytes }

        /** Fluent setter for [inboxCapacity]. */
        public fun inboxCapacity(messages: Int): Builder = apply { inboxCapacity = messages }

        /** Fluent setter for [maxHopLimit]. */
        public fun maxHopLimit(hops: Int): Builder = apply { maxHopLimit = hops }

        /** Fluent setter for [messageLifetimeSeconds]. */
        public fun messageLifetimeSeconds(seconds: Int): Builder = apply { messageLifetimeSeconds = seconds }

        /** Fluent setter for [storeAndForward]. */
        public fun storeAndForward(enabled: Boolean): Builder = apply { storeAndForward = enabled }

        /** Fluent setter for [deliveryReceipts]. */
        public fun deliveryReceipts(allowed: Boolean): Builder = apply { deliveryReceipts = allowed }

        /** Fluent setter for [readReceipts]. */
        public fun readReceipts(allowed: Boolean): Builder = apply { readReceipts = allowed }

        /** Fluent setter for [dutyCycle]. */
        public fun dutyCycle(cycle: DutyCycle): Builder = apply { dutyCycle = cycle }

        /** Returns the collected overrides; validation happens in [MeshLinkConfig.Builder.build]. */
        public fun build(): PresetOverrides = PresetOverrides(
            maxPayloadBytes, inboxCapacity, maxHopLimit, messageLifetimeSeconds,
            storeAndForward, deliveryReceipts, readReceipts, dutyCycle,
        )
    }
}

/** Kotlin DSL for [PresetOverrides]. */
public fun presetOverrides(configure: PresetOverrides.Builder.() -> Unit): PresetOverrides =
    PresetOverrides.Builder().apply(configure).build()

/** Marks MeshLink DSL scopes so nested builders cannot leak receivers. */
@DslMarker
public annotation class MeshLinkDsl

internal class PresetSettings(
    val maxPayloadBytes: Int,
    val inboxCapacity: Int,
    val maxHopLimit: Int,
    val messageLifetimeSeconds: Int,
    val storeAndForward: Boolean,
    val deliveryReceipts: Boolean,
    val readReceipts: Boolean,
    val dutyCycle: DutyCycle,
)
