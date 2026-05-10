package ch.trancee.meshlink.config

import ch.trancee.meshlink.api.MeshLinkException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@DslMarker
public annotation class MeshLinkDsl

public enum class RegulatoryRegion {
    DEFAULT,
    EU,
}

public sealed class PowerMode {
    public data object Automatic : PowerMode()

    public data object Performance : PowerMode()

    public data object Balanced : PowerMode()

    public data object PowerSaver : PowerMode()
}

public class MeshLinkConfig public constructor(
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
                "deliveryRetryDeadline must be greater than zero",
            )
        }
    }
}

@MeshLinkDsl
public class MeshLinkConfigBuilder public constructor() {
    public var appId: String = ""
    public var regulatoryRegion: RegulatoryRegion = RegulatoryRegion.DEFAULT
    public var powerMode: PowerMode = PowerMode.Automatic
    public var deliveryRetryDeadline: Duration = 15.seconds

    public fun build(): MeshLinkConfig {
        return MeshLinkConfig(
            appId = appId,
            regulatoryRegion = regulatoryRegion,
            powerMode = powerMode,
            deliveryRetryDeadline = deliveryRetryDeadline,
        )
    }
}

public fun meshLinkConfig(block: MeshLinkConfigBuilder.() -> Unit): MeshLinkConfig {
    val builder = MeshLinkConfigBuilder()
    builder.block()
    return builder.build()
}
