package ch.trancee.meshlink

/**
 * Immutable configuration of a node. Create it with [meshLinkConfig] (Kotlin) or
 * [MeshLinkConfig.Builder] (Swift); both validate in [Builder.build].
 *
 * @sample ch.trancee.meshlink.samples.configureWithDsl
 */
public class MeshLinkConfig private constructor(
    /** Local preset; does not need to match other nodes. */
    public val preset: Preset,
    /** Protection level; must match every other node of the mesh. */
    public val securityProfile: SecurityProfile,
    /** Mesh identifier; must match every other node of the mesh. */
    public val meshId: String,
    private val meshKey: ByteArray?,
    /** Whether diagnostics include debug-level detail. Off by default. */
    public val debugDiagnostics: Boolean,
    /** Capacity of the diagnostics buffer; overflow produces a summary event. */
    public val diagnosticsBufferCapacity: Int,
) {
    internal val settings: PresetSettings = preset.settings

    /** `true` for a gated mesh, which only nodes holding the same mesh key may join. */
    public val isGated: Boolean get() = meshKey != null

    internal fun sameMeshAs(other: MeshLinkConfig): Boolean =
        meshId == other.meshId && securityProfile == other.securityProfile && meshKey.contentEquals(other.meshKey)

    /** Returns the effective settings, one `name: value` per line, with the mesh key redacted. */
    public fun describe(): String = buildString {
        val overridden = (preset as? Preset.Custom)?.overrides?.overridden().orEmpty()
        fun line(name: String, value: Any) {
            appendLine("$name: $value" + if (name in overridden) " (override)" else "")
        }
        line("preset", if (preset is Preset.Custom) "${preset.name} (custom, base ${preset.base.name})" else preset.name)
        line("securityProfile", securityProfile)
        line("meshId", meshId)
        line("meshKey", if (meshKey == null) "none (open mesh)" else "set (redacted)")
        line("maxPayloadBytes", settings.maxPayloadBytes)
        line("inboxCapacity", settings.inboxCapacity)
        line("maxHopLimit", settings.maxHopLimit)
        line("messageLifetimeSeconds", settings.messageLifetimeSeconds)
        line("storeAndForward", settings.storeAndForward)
        line("deliveryReceipts", settings.deliveryReceipts)
        line("readReceipts", settings.readReceipts)
        line("dutyCycle", settings.dutyCycle)
        line("debugDiagnostics", debugDiagnostics)
        line("diagnosticsBufferCapacity", diagnosticsBufferCapacity)
    }

    /**
     * Collects settings; usable as a Kotlin DSL scope ([meshLinkConfig]) or as a fluent builder
     * from Swift. Security profile and preset are required and have no default.
     *
     * @sample ch.trancee.meshlink.samples.configureWithBuilder
     */
    @MeshLinkDsl
    public class Builder(
        /** Local preset. */
        public val preset: Preset,
        /** Security profile shared by the mesh. */
        public val securityProfile: SecurityProfile,
    ) {
        /** Required; 1 to [MAX_MESH_ID_LENGTH] characters. */
        public var meshId: String = ""

        /** Optional [MESH_KEY_BYTES]-byte mesh key; makes the mesh gated. Needs a secured profile. */
        public var meshKey: ByteArray? = null

        /** See [MeshLinkConfig.debugDiagnostics]. */
        public var debugDiagnostics: Boolean = false

        /** See [MeshLinkConfig.diagnosticsBufferCapacity]. */
        public var diagnosticsBufferCapacity: Int = DEFAULT_DIAGNOSTICS_CAPACITY

        /** Fluent setter for [meshId]. */
        public fun meshId(id: String): Builder = apply { meshId = id }

        /** Fluent setter for [meshKey]. */
        public fun meshKey(key: ByteArray): Builder = apply { meshKey = key.copyOf() }

        /** Fluent setter for [debugDiagnostics]. */
        public fun debugDiagnostics(enabled: Boolean): Builder = apply { debugDiagnostics = enabled }

        /** Fluent setter for [diagnosticsBufferCapacity]. */
        public fun diagnosticsBufferCapacity(events: Int): Builder = apply { diagnosticsBufferCapacity = events }

        /**
         * Validates and returns the configuration.
         *
         * @throws MeshLinkException.InvalidConfig naming the first invalid setting.
         */
        @Throws(MeshLinkException::class)
        public fun build(): MeshLinkConfig {
            check("meshId", meshId.length in 1..MAX_MESH_ID_LENGTH) { "must be 1 to $MAX_MESH_ID_LENGTH characters" }
            val key = meshKey
            if (key != null) {
                check("meshKey", securityProfile != SecurityProfile.None) { "a gated mesh needs a secured security profile" }
                check("meshKey", key.size == MESH_KEY_BYTES) { "must be $MESH_KEY_BYTES bytes" }
            }
            check("diagnosticsBufferCapacity", diagnosticsBufferCapacity in 16..4096) { "must be 16 to 4096" }
            validatePreset(preset)
            return MeshLinkConfig(preset, securityProfile, meshId, key?.copyOf(), debugDiagnostics, diagnosticsBufferCapacity)
        }

        private fun validatePreset(preset: Preset) {
            if (preset is Preset.Custom) check("preset", preset.name.isNotBlank()) { "custom preset name must not be blank" }
            val settings = preset.settings
            check("maxPayloadBytes", settings.maxPayloadBytes in 1..65_536) { "must be 1 to 65536" }
            check("inboxCapacity", settings.inboxCapacity in 4..4096) { "must be 4 to 4096" }
            check("maxHopLimit", settings.maxHopLimit in 1..16) { "must be 1 to 16" }
            check("messageLifetimeSeconds", settings.messageLifetimeSeconds in 1..604_800) { "must be 1 to 604800" }
        }

        private inline fun check(setting: String, valid: Boolean, problem: () -> String) {
            if (!valid) throw MeshLinkException.InvalidConfig(setting, problem())
        }

        private companion object {
            const val DEFAULT_DIAGNOSTICS_CAPACITY = 256
        }
    }

    /** Limits shared by every configuration. */
    public companion object {
        /** Longest mesh identifier. */
        public const val MAX_MESH_ID_LENGTH: Int = 64

        /** Mesh key length. */
        public const val MESH_KEY_BYTES: Int = 32
    }
}

/**
 * Kotlin DSL for [MeshLinkConfig]; [preset] and [securityProfile] are required.
 *
 * @throws MeshLinkException.InvalidConfig naming the first invalid setting.
 * @sample ch.trancee.meshlink.samples.configureWithDsl
 */
@Throws(MeshLinkException::class)
public fun meshLinkConfig(
    preset: Preset,
    securityProfile: SecurityProfile,
    configure: MeshLinkConfig.Builder.() -> Unit,
): MeshLinkConfig = MeshLinkConfig.Builder(preset, securityProfile).apply(configure).build()
