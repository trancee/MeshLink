# Power management

MeshLink runs on battery-powered phones, so it cannot stay at maximum radio
activity all the time. It also cannot become so conservative that discovery,
trust establishment, and delivery feel unreliable.

The power model balances those goals with three ideas:

- a small number of transport tiers
- automatic tier selection from battery and charging state
- regional clamps that keep the effective policy within compliance-oriented
  bounds

## The three transport tiers

| Tier | Advertisement interval | Connection interval | Scan duty cycle | Max connections | Chunk budget |
|---|---:|---:|---:|---:|---:|
| `PERFORMANCE` | 250 ms | 100 ms | 100% | 7 | 4096 bytes |
| `BALANCED` | 500 ms | 250 ms | 50% | 5 | 2048 bytes |
| `POWER_SAVER` | 1000 ms | 500 ms | 5% | 3 | 512 bytes |

This is intentionally simple. A host app does not need to manage a large matrix
of BLE knobs. It only needs to understand which tier MeshLink chose and why.

## Why automatic mode exists

Most applications do not want to pick a transport posture manually. They want
MeshLink to stay aggressive when the device is charging or just starting up,
and then back off when battery pressure is real.

That is what `PowerMode.Automatic` does.

The host app feeds battery state into MeshLink:

```kotlin
meshLink.updateBattery(
    snapshot = BatterySnapshot(level = 0.45f, isCharging = false),
)
```

MeshLink then recalculates the effective policy.

## The selection model

Automatic selection uses four inputs:

- current battery level
- charging state
- whether the runtime is still inside the startup bootstrap window
- the previously selected tier, so small battery oscillations do not cause
  constant flapping

### Startup bootstrap

Immediately after `start()`, MeshLink enters a bootstrap window. During that
window it stays in `PERFORMANCE`, even on a low battery.

The goal is not permanent maximum throughput. The goal is to give a fresh
runtime the best chance to:

- discover peers quickly
- establish trust quickly
- build initial connectivity before settling into a lower tier

By default, the bootstrap window lasts 30 seconds.

### Charging wins immediately

When the device is charging, MeshLink stays in `PERFORMANCE`.

That is a deliberate bias toward responsiveness: once external power is
available, the runtime stops trying to conserve radio activity aggressively.

### Battery thresholds and hysteresis

Outside bootstrap and charging, the default thresholds are:

- above 80% battery: prefer `PERFORMANCE`
- below 30% battery: prefer `POWER_SAVER`
- between them: prefer `BALANCED`

MeshLink does not switch exactly on those raw boundaries forever. It uses a
small hysteresis band so the effective policy does not flap when the battery
hovers near a threshold.

With the default 2% band:

- `PERFORMANCE` does not step down until battery drops below 78%
- `POWER_SAVER` does not step up until battery rises above 32%
- `BALANCED` stays put until the battery moves clearly past a threshold

That is why later automatic selections behave more gently than the first one:
the runtime has policy history, not just the latest battery sample.

## Regional clamps

The selected tier is not always the final transport posture.

For the EU region, MeshLink currently clamps two values when a chosen tier would
be too aggressive:

- advertisement interval cannot go below 300 ms
- scan duty cycle cannot exceed 70%

This is why the effective policy matters more than the requested tier alone. A
performance-oriented policy in the EU is still performance-oriented, but it is
not identical to the unrestricted default-region version.

## What the host app needs to do

The host app does not manage the full power policy. It only needs to provide
battery snapshots when it wants automatic mode to react.

That keeps ownership clear:

- the app knows battery state
- MeshLink knows how battery state changes transport posture

If the host app never calls `updateBattery()`, automatic mode can react only to
startup posture and the last known battery input.

## Observability

MeshLink emits `POWER_MODE_CHANGED` diagnostics when battery updates change the
effective policy.

Those diagnostics include machine-readable metadata such as:

- `level`
- `isCharging`
- `tier`
- `advertisementIntervalMillis`
- `connectionIntervalMillis`
- `scanDutyCyclePercent`
- `maxConnections`
- `chunkBudgetBytes`
- `region`
- `clampWarnings` when regional clamping was applied

This is the right surface for operator tooling and troubleshooting. A host app
should not try to infer policy changes indirectly from peer behavior.

## What this design optimizes for

The current design does not try to be infinitely configurable. It optimizes for:

- predictable transport behavior
- enough adaptivity for real battery pressure
- clear operator visibility
- shared Android and iOS semantics
