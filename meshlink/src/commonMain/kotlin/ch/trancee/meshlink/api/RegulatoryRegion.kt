package ch.trancee.meshlink.api

/**
 * Regulatory region controlling transport parameter clamping.
 *
 * When a region is active, [MeshLinkConfigBuilder.build] applies region-specific constraints and
 * populates [MeshLinkConfig.clampWarnings] with any fields that were adjusted.
 *
 * @property DEFAULT No clamping — all transport parameters are used as configured.
 * @property EU ETSI EN 300 328 constraints: advertisement interval ≥ 300 ms, scan duty cycle ≤ 70
 *   %.
 */
public enum class RegulatoryRegion {
    /** No regulatory clamping applied. */
    DEFAULT,

    /** ETSI EN 300 328 (European Union): ad interval ≥ 300 ms, scan duty ≤ 70 %. */
    EU,
}
