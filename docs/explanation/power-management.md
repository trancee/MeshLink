# Power management

MeshLink runs on battery-powered phones, so its transport posture cannot stay at
maximum radio activity all the time. But it also cannot become so conservative
that discovery, trust establishment, and delivery feel unreliable.

The current power model tries to solve that tension with three ideas:

- a small number of transport tiers that are easy to reason about
- automatic tier selection based on battery level, charging state, and startup
  posture
- regional clamps that keep the transport within compliance-oriented bounds

## The three transport tiers

MeshLink reduces transport behavior to three tiers.

| Tier | Advertisement interval | Connection interval | Scan duty cycle | Max connections | Chunk budget |
|---|---:|---:|---:|---:|---:|
| `PERFORMANCE` | 250 ms | 100 ms | 100% | 7 | 4096 bytes |
| `BALANCED` | 500 ms | 250 ms | 50% | 5 | 2048 bytes |
| `POWER_SAVER` | 1000 ms | 500 ms | 5% | 3 | 512 bytes |

This is intentionally simple. A host application does not need to understand a
large matrix of transport knobs. It only needs to understand which tier MeshLink
selected and why.

## Why automatic mode exists

Most applications do not want to pick a transport posture manually. They want
MeshLink to stay responsive when the device is charging or just starting up, and
then back off when the battery is low.

That is what `PowerMode.Automatic` does.

The host app feeds battery state into MeshLink:

```kotlin
meshLink.updateBattery(level = 0.45f, isCharging = false)
```

MeshLink then recalculates the effective tier immediately.

## The selection model

Automatic selection has four inputs:

- the current battery level
- whether the device is charging
- whether the runtime is still inside the startup bootstrap window
- the previously selected tier, so hysteresis can prevent flapping

### Startup bootstrap

Immediately after `start()`, MeshLink enters a bootstrap period. During that
window it stays in `PERFORMANCE`, even on a low battery.

The point is not to maximize throughput forever. The point is to give a fresh
runtime the best chance to:

- discover peers quickly
- establish trust quickly
- build initial connectivity before settling into a lower tier

By default, the bootstrap window lasts 30 seconds.

### Charging wins immediately

When the device is charging, MeshLink stays in `PERFORMANCE`.

This is a deliberate bias toward responsiveness. When power pressure is gone,
MeshLink stops trying to conserve radio activity aggressively.

### Battery thresholds

Outside bootstrap and charging, the default thresholds are:

- above 80% battery: prefer `PERFORMANCE`
- below 30% battery: prefer `POWER_SAVER`
- between them: prefer `BALANCED`

That would be enough if the battery level moved in large stable steps. In real
usage it does not.

## Why hysteresis matters

A battery level that hovers near 30% or 80% would cause constant tier changes if
MeshLink switched exactly at those boundaries.

That would be noisy for operators and expensive for the transport, because each
change alters the runtime's radio posture.

To avoid this, MeshLink uses a small hysteresis band around the thresholds.
With the default 2% band:

- a runtime already in `PERFORMANCE` does not step down until battery drops
  below 78%
- a runtime already in `POWER_SAVER` does not step up until battery rises above
  32%
- a runtime already in `BALANCED` only leaves that band when the battery moves
  clearly past a recovery or downgrade threshold

This means the system behaves like a state machine, not like a single raw lookup
against current battery level.

## Why the current tier changes more gently than the initial tier

The first automatic selection has no history, so MeshLink chooses the tier that
matches the raw battery level.

After that, the previously selected tier matters. This gives the runtime some
memory and prevents small battery oscillations from forcing immediate transport
oscillations.

That difference is important for integrators:

- the initial tier answers "where should we start?"
- later tiers answer "is there enough evidence to change?"

## Regional clamps

The selected tier is not always the final transport posture.

For the EU region, MeshLink currently clamps two values when the chosen tier
would be too aggressive:

- advertisement interval cannot go below 300 ms
- scan duty cycle cannot exceed 70%

This is why the effective policy should be treated as the source of truth, not
just the requested tier.

A `PERFORMANCE` request in the EU region is still a performance-oriented policy,
but it is not identical to the unrestricted default-region version.

## What the host app needs to do

The host app does not manage the full policy. It only needs to provide timely,
normalized battery snapshots when it wants automatic mode to react.

That makes the ownership boundary clear:

- the app knows battery state
- MeshLink knows how battery state changes transport posture

If the host app never calls `updateBattery()`, automatic mode can only react to
startup posture and its last known battery input.

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

This is the right surface for operator tooling and troubleshooting. A host app
should not try to infer policy changes indirectly from peer behavior alone.

## What this design optimizes for

The current design does not try to be infinitely configurable. It optimizes for:

- predictable transport behavior
- enough adaptivity for real battery pressure
- clear operator visibility
- shared Android and iOS semantics

That trade-off matters for library users. The value is not just lower battery
usage; it is having a power model that can be explained, tested, and trusted.
