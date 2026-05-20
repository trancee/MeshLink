package ch.trancee.meshlink.config

import ch.trancee.meshlink.api.MeshLinkException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Marks the MeshLink configuration DSL and prevents accidental receiver mixing. */
@DslMarker public annotation class MeshLinkDsl

/** Regulatory policy region applied to MeshLink power-policy clamping and compliance defaults. */
public enum class RegulatoryRegion {
    DEFAULT,
    EU,
}

/** Requests the effective MeshLink power policy tier. */
public sealed class PowerMode {
    /** Lets MeshLink adapt the effective tier from runtime battery and charging state. */
    public data object Automatic : PowerMode()

    /** Prefers throughput and responsiveness over energy savings. */
    public data object Performance : PowerMode()

    /** Balances delivery performance against energy use. */
    public data object Balanced : PowerMode()

    /** Prefers lower power use over aggressive transport behavior. */
    public data object PowerSaver : PowerMode()
}

/**
 * Immutable MeshLink runtime configuration shared by all peers in the same mesh domain.
 *
 * Peers must use the same [appId] to discover and interoperate with each other.
 */
public class MeshLinkConfig
public constructor(
    public val appId: String,
    public val regulatoryRegion: RegulatoryRegion,
    public val powerMode: PowerMode,
    public val deliveryRetryDeadline: Duration,
) {
    init {
        if (appId.isBlank()) {
            throw MeshLinkException.InvalidConfiguration("appId must not be blank")
        }
        if (deliveryRetryDeadline <= Duration.ZERO) {
            throw MeshLinkException.InvalidConfiguration(
                "deliveryRetryDeadline must be greater than zero"
            )
        }
    }
}

/** Mutable builder used by [meshLinkConfig] to create a validated [MeshLinkConfig]. */
@MeshLinkDsl
public class MeshLinkConfigBuilder {
    /** Non-blank application or mesh identifier shared by interoperating peers. */
    public var appId: String = ""

    /** Regulatory region used when MeshLink applies compliance-oriented clamps. */
    public var regulatoryRegion: RegulatoryRegion = RegulatoryRegion.DEFAULT

    /** Requested power-policy tier for the runtime. */
    public var powerMode: PowerMode = PowerMode.Automatic

    /** Maximum in-memory retry window while no usable route exists. */
    public var deliveryRetryDeadline: Duration = 15.seconds

    /** Builds and validates an immutable [MeshLinkConfig] from the current builder state. */
    public fun build(): MeshLinkConfig {
        return MeshLinkConfig(
            appId = appId,
            regulatoryRegion = regulatoryRegion,
            powerMode = powerMode,
            deliveryRetryDeadline = deliveryRetryDeadline,
        )
    }
}

/** Builds a validated [MeshLinkConfig] using the MeshLink configuration DSL. */
public fun meshLinkConfig(block: MeshLinkConfigBuilder.() -> Unit): MeshLinkConfig {
    val builder = MeshLinkConfigBuilder()
    builder.block()
    return builder.build()
}
