# Power Management Design

## The problem

BLE radios consume significant battery. A mesh networking library that keeps the radio active at maximum duty will drain a phone in hours. But reducing radio activity too aggressively makes the mesh unresponsive — peers aren't discovered, messages are delayed.

MeshLink must balance responsiveness against battery life, adapting automatically to the device's power state.

## The three-tier model

| Tier | Scan Duty | Max Connections | Chunk Size | Ad Interval |
|------|-----------|-----------------|------------|-------------|
| PERFORMANCE | Continuous | 7 | Large (4KB L2CAP) | 250ms |
| BALANCED | Medium | 5 | Medium | 500ms |
| POWER_SAVER | Burst-scan | 3 | Small | 1000ms |

## Automatic tier selection

`PowerManager` observes battery state reported by the app:

```kotlin
meshLink.updateBattery(level = 0.45f, isCharging = false)
```

The decision table:

| Condition | Tier |
|-----------|------|
| Charging (any level) | PERFORMANCE |
| Battery > `performanceThreshold` (default 80%) | PERFORMANCE |
| Battery < `powerSaverThreshold` (default 30%) | POWER_SAVER |
| Otherwise | BALANCED |

## Hysteresis

Without hysteresis, a device oscillating around 30% battery would rapidly flip between BALANCED and POWER_SAVER. Each transition changes radio behavior, causes connection renegotiation, and confuses peers.

The fix: offset the threshold by ±2% depending on direction:

```
BALANCED → POWER_SAVER:  battery drops below 28% (30% - 2%)
POWER_SAVER → BALANCED:  battery rises above 32% (30% + 2%)
```

This creates a "dead zone" where the tier doesn't change, preventing flapping.

## Transition delay

Even with hysteresis, we add a 30-second delay before downgrade transitions:

- **Downgrade (PERFORMANCE → BALANCED, BALANCED → POWER_SAVER):** 30s delay. The battery must remain below threshold for 30 continuous seconds.
- **Upgrade (any → PERFORMANCE when charging):** Immediate. Plugging in should instantly improve responsiveness.

## Bootstrap period

On cold start (first `meshLink.start()`), the engine enters PERFORMANCE tier regardless of battery level for `bootstrapDurationMillis` (default 30s). This ensures:

- Initial peer discovery happens at full scan rate
- First handshakes complete quickly
- The mesh establishes connectivity before settling into the appropriate tier

After bootstrap expires, normal tier selection takes over.

## What each tier controls

### Scan behavior
- PERFORMANCE: Continuous scan (100% duty)
- BALANCED: Periodic scan windows (scan for 5s, pause for 5s)
- POWER_SAVER: Burst scan (scan for 2s, pause for 30s)

### Connection limits (TieredShedder)
When the tier downgrades and the current connection count exceeds the new limit:
1. Peers are ranked by priority (last-message-time, route importance)
2. Lowest-priority peers receive a graceful disconnect after `evictionGracePeriodMillis`
3. If they reconnect before grace period, and we're still at limit, they're rejected

### Advertisement interval
Longer intervals = less radio time transmitting. POWER_SAVER advertises 4× less frequently than PERFORMANCE.

### Chunk size
Smaller chunks per radio burst = less time with radio on per transmission = better battery. The tradeoff is more round-trips for large transfers.

## Custom power mode

Apps can override automatic selection:

```kotlin
meshLink.setCustomPowerMode(PowerTier.PERFORMANCE)  // force high performance
meshLink.setCustomPowerMode(null)                    // return to automatic
```

Use case: a navigation app that needs maximum mesh responsiveness during active use, regardless of battery.

## Observability

Tier transitions emit `DiagnosticCode.TRANSPORT_MODE_CHANGED`:

```kotlin
meshLink.diagnosticEvents
    .filter { it.code == DiagnosticCode.TRANSPORT_MODE_CHANGED }
    .collect { /* TransportModeChanged(mode = "POWER_SAVER") */ }
```

`MeshHealthSnapshot` includes the current power tier for dashboard display.

## Why not let the OS handle it?

Android and iOS have their own battery optimization (Doze, App Standby, Background App Refresh). MeshLink's power management is complementary:

- **OS-level:** Suspends the entire app, kills background services, restricts network
- **MeshLink-level:** Reduces radio activity while the app is still running

MeshLink's tiers operate within whatever runtime the OS grants. If the OS suspends BLE entirely (deep Doze), MeshLink can't override that — it simply resumes at the appropriate tier when the OS wakes it.

## The polling architecture

`PowerManager` polls battery state on a configurable interval (`batteryPollIntervalMillis`). The app reports battery via `updateBattery()`, and the next poll reads the latest value.

**Testing gotcha:** This poll loop launches immediately on `MeshEngine.create()` (not `start()`). Any test that creates a MeshEngine must cancel it before `advanceUntilIdle()`, or the virtual time loop runs forever. See KNOWLEDGE.md K003 for the full explanation.
