# How to Configure Power Tiers

## When to use this

You want to tune MeshLink's BLE behavior based on battery state — reduce scan duty when battery is low, increase throughput when charging.

## Steps

### 1. Report battery level

MeshLink doesn't read battery directly — your app reports it:

```kotlin
// Call periodically or on battery change broadcast
meshLink.updateBattery(level = 0.45f, isCharging = false)
```

### 2. Understand the three tiers

| Tier | Scan Duty | Max Connections | Chunk Size | Trigger |
|------|-----------|-----------------|------------|---------|
| Performance | High | 7 | Large | Charging OR battery > 80% |
| Balanced | Medium | 5 | Medium | Default (20%–80%) |
| PowerSaver | Low | 3 | Small | Battery < 20% |

### 3. Hysteresis rules

Transitions have ±2% hysteresis and a 30-second delay to prevent flapping:

- Balanced → PowerSaver: battery drops below 18% (20% - 2%) for 30s
- PowerSaver → Balanced: battery rises above 22% (20% + 2%)
- Charging: immediate jump to Performance (no delay)
- Unplug: 30s delay before downgrade

### 4. Override with custom tier

```kotlin
meshLink.setCustomPowerMode(PowerTier.PERFORMANCE)
// Forces Performance regardless of battery — use for time-critical operations
// Call meshLink.setCustomPowerMode(null) to return to automatic
```

### 5. Configure thresholds (optional)

```kotlin
val config = MeshLinkConfig {
    power {
        lowBatteryThreshold = 0.15f      // default: 0.20
        highBatteryThreshold = 0.85f     // default: 0.80
        hysteresisPercent = 0.03f        // default: 0.02
        downgradeDelayMillis = 45_000L   // default: 30_000
    }
}
```

### 6. Observe tier changes via diagnostics

```kotlin
meshLink.diagnosticEvents
    .filter { it.code == DiagnosticCode.POWER_TIER_CHANGED }
    .collect { event ->
        val payload = event.payload as DiagnosticPayload.PowerTierChanged
        log("Power: ${payload.previousTier} → ${payload.newTier}")
    }
```

## What each tier affects

- **Scan interval:** How often BLE scanning runs (Performance: continuous, PowerSaver: periodic bursts)
- **Connection slots:** `ConnectionLimiter` enforces max peers per tier
- **Chunk size:** Smaller chunks = less radio time per burst = better battery
- **Advertisement interval:** Longer interval in PowerSaver
- **TieredShedder:** When at connection limit, lowest-priority connections are evicted

## EU regulatory interaction

If `regulatoryRegion = RegulatoryRegion.EU`, additional clamping applies regardless of power tier:
- Advertisement interval ≥ 300ms
- Scan duty ≤ 70%

These override tier settings when they would violate regulatory limits.
