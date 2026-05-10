# Regulatory Compliance and Region Clamping

## The problem

BLE operates in the 2.4 GHz ISM band. Different jurisdictions impose different rules on how devices use this spectrum. A library that works globally must enforce these limits automatically.

## What MeshLink regulates

MeshLink doesn't control raw radio parameters — that's the OS BLE stack's job. But it controls **application-layer behavior** that directly affects radio usage:

| Parameter | What it affects |
|-----------|-----------------|
| Advertisement interval | How often the radio transmits ads (duty cycle) |
| Scan duty cycle | What percentage of time the radio is actively receiving |

These two parameters determine whether the device complies with duty cycle limits imposed by radio regulations.

## RegulatoryRegion enum

```kotlin
public enum class RegulatoryRegion {
    DEFAULT,  // No additional clamping (rely on OS enforcement)
    EU,       // ETSI EN 300 328 compliance
}
```

## EU region: ETSI EN 300 328

The European Telecommunications Standards Institute sets rules for 2.4 GHz devices:

- **Advertisement interval ≥ 300ms:** Prevents excessive channel occupation from rapid advertising
- **Scan duty cycle ≤ 70%:** Ensures the device doesn't monopolize the receive path

### How clamping works

During `MeshLinkConfigBuilder.build()`:

```kotlin
if (region == RegulatoryRegion.EU) {
    if (advertisementIntervalMillis < 300L) {
        // Clamp to 300ms, record warning
        advertisementIntervalMillis = 300L
        clampWarnings.add("advertisementIntervalMillis: clamped ... → 300 (EU minimum 300 ms)")
    }
    if (scanDutyCyclePercent > 70) {
        // Clamp to 70%, record warning
        scanDutyCyclePercent = 70
        clampWarnings.add("scanDutyCyclePercent: clamped ... → 70 (EU maximum 70%)")
    }
}
```

### User visibility

- Clamped values are recorded in `MeshLinkConfig.clampWarnings`
- On `meshLink.start()`, each warning emits a `CONFIG_CLAMPED` diagnostic event
- The app can log or display these: "Your scan duty was reduced to 70% for EU compliance"

## Why DEFAULT doesn't clamp

In the DEFAULT region:
- The OS BLE stack already enforces its own radio limits
- Android enforces minimum advertisement intervals at the HAL level
- iOS enforces its own duty cycle limits (more aggressive than ETSI)
- Adding redundant clamping on top of OS enforcement provides no benefit

DEFAULT means: "trust the platform to handle radio compliance."

## Power tier interaction

Power tier settings are applied **after** regulatory clamping. This means:

1. Config sets `advertisementIntervalMillis = 200ms`
2. EU clamping raises it to `300ms`
3. POWER_SAVER tier may further increase it to `500ms`

Regulatory limits are a floor — power management can only be more conservative, never less.

## Why not per-country granularity?

Three reasons:

1. **BLE is globally harmonized on 2.4 GHz ISM.** Most countries follow either FCC (US) or ETSI (EU) rules, and the differences that matter for application-layer behavior are minimal.

2. **The OS handles the hard parts.** Channel hopping sequences, maximum transmit power, and frequency masking are managed by the Bluetooth controller firmware and OS BLE stack — not the application.

3. **Complexity vs. value.** Supporting 50+ country codes with marginally different rules adds maintenance burden without meaningful compliance improvement. Two tiers (DEFAULT + EU) covers the practical space.

## Future regions

If a jurisdiction imposes application-layer BLE restrictions beyond what the OS enforces, add a new `RegulatoryRegion` variant with its own clamping rules in `MeshLinkConfigBuilder.build()`.

Candidate: Japan (MIC Article 4-2 power density limits could affect advertisement rates for certain use cases). Not currently needed.

## Export control note

MeshLink uses standard cryptographic algorithms (ChaCha20-Poly1305, Ed25519, X25519) that are publicly available and widely deployed. See `EXPORT_CONTROL.md` in the repository root for the full analysis. The library qualifies for EAR License Exception TSU (publicly available encryption source code).
