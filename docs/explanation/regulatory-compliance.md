# Regulatory compliance and region clamping

## The problem

BLE operates in the 2.4 GHz ISM band, but applications still influence how hard
that radio is driven. MeshLink cannot control the underlying controller or OS
stack, but it can control application-level behavior that changes radio usage.

## What MeshLink actually regulates

MeshLink does not set raw PHY parameters. It adjusts higher-level policy values
that still affect radio pressure:

| Parameter | What it affects |
|---|---|
| Advertisement interval | How often the device advertises |
| Scan duty cycle | How much time the device spends listening |

Those values are enough to make regional policy matter at the application
layer.

## `RegulatoryRegion`

```kotlin
public enum class RegulatoryRegion {
    DEFAULT,
    EU,
}
```

- `DEFAULT` means "rely on the platform's normal behavior"
- `EU` adds MeshLink-side clamping for the current compliance-oriented policy

## EU clamping

For the EU region, MeshLink currently clamps two values when the selected policy
would be too aggressive:

- advertisement interval cannot go below 300 ms
- scan duty cycle cannot exceed 70%

This happens inside the shared power-policy code, not in a platform-specific
wrapper.

## Why `DEFAULT` does not clamp

`DEFAULT` does not mean "ignore compliance." It means MeshLink does not add an
extra shared clamp on top of what the operating system and controller already
do.

That default is reasonable because:

- Android and iOS already enforce important BLE limits below the app layer
- the exact low-level radio rules are owned by the platform stack, not by
  MeshLink
- redundant clamping in shared code would make behavior more conservative
  without necessarily improving real compliance

## How clamping interacts with power tiers

Power tier selection happens first, then regional clamping adjusts the effective
policy if needed.

That means an EU `PERFORMANCE` posture is still performance-oriented, but it may
produce a more conservative effective advertisement interval or scan duty cycle
than the unrestricted default-region version.

## How the host app sees it

MeshLink surfaces the effective power-policy state through
`POWER_MODE_CHANGED` diagnostics. When a regional clamp is active, the emitted
metadata includes `clampWarnings` alongside the effective values.

That keeps the app-facing story simple:

- the app chooses a region and a power mode
- MeshLink computes the effective policy
- diagnostics tell operators what actually happened

## Why the model stays simple

MeshLink does not currently model dozens of country-specific variants. The goal
is a small set of meaningful, shared choices rather than a large compliance
matrix that the host app has to reason about.

If a future jurisdiction needs additional application-layer constraints beyond
what the platform already enforces, that should become a new
`RegulatoryRegion` with explicit shared clamping rules.

## Related docs

- [MeshLink SDK API reference](../reference/meshlink-sdk-api.md)
- [MeshLink runtime behavior reference](../reference/meshlink-runtime-behavior.md)
- [Power management](power-management.md)
- [How to structure a robust MeshLink integration](../how-to/structure-a-robust-meshlink-integration.md)
