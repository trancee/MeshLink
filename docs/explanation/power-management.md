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

**Max connections is an enforced admission-control budget on both Android and
iOS, not just a reported number.** Before starting a new GATT side-link or
L2CAP connection for a newly discovered peer, the transport adapter checks
the device's current active-connection count against this budget; once the
budget is spent, a not-yet-connected peer is deferred (it stays known and is
admitted once a slot frees up) rather than competing for a connection the
platform's own Bluetooth stack may not reliably support anyway -- see
[BLE connection robustness](ble-connection-robustness.md#platform-limits-on-concurrent-gattl2cap-connections-research-2026-07-11)
for why the historical Android default of 7 concurrent connections exists in
the first place. An already-connected peer is never dropped just because the
budget is at capacity. Only Android has a locally-initiated GATT side-link
connection to gate (it acts as GATT client); iOS's admission gate covers the
only locally-initiated connection its transport adapter opens -- the L2CAP
channel connect -- while still counting active GATT notify side-links
(inherently peer-initiated on iOS, since the local device is the GATT
peripheral there) toward the active-connection total so the budget reflects
the device's actual concurrent BLE connection load either way. The same
admission check also runs on the send path (not just discovery-driven
auto-connect): a `send()` call to a not-yet-connected peer this device would
locally initiate a connection for is deferred the same way once the budget is
spent, rather than bypassing the gate.

**The budget is admission-control only, not an active eviction policy.**
Dropping to a lower tier (for example `PERFORMANCE` -> `POWER_SAVER` while
automatic mode reacts to a battery-level change) never closes
already-connected peers to fit the new, smaller budget, even if the device is
currently over the new tier's limit. There is no eviction step keyed on
connection age, signal stability, RSSI, or any other quality metric --
existing connections are only ever removed by their own normal lifecycle
(peer loss, explicit disconnect, link failure), never by a tier change. A
tier downgrade only affects new connection attempts from that point forward;
the device's active count settles back under the new budget purely through
natural attrition as peers come and go.

## Why automatic mode exists

Most applications do not want to pick a transport posture manually. They want
MeshLink to stay aggressive when the device is charging or just starting up,
and then back off when battery pressure is real.

That is what `PowerMode.Automatic` does.

MeshLink observes battery state internally on each supported platform and
recalculates the effective policy whenever that state changes. Android listens
to system battery broadcasts, and iOS listens to `UIDevice` battery
notifications.

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

The host app does not manage the full power policy. On Android and iOS, MeshLink
subscribes to platform battery updates itself and applies the shared commonMain
policy logic internally.

That keeps ownership clear:

- the platform owns raw battery state delivery
- MeshLink owns how battery state changes transport posture
- the host app only chooses whether to use `PowerMode.Automatic` or a fixed mode

## Observability

MeshLink emits `POWER_MODE_CHANGED` diagnostics when observed battery updates
change the effective policy.

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

## Platform translation of the shared tiers

`PowerPolicy` and its tiers/thresholds/clamps above are entirely `commonMain`
and identical on both platforms. Each platform's `PowerMonitor` translates
that shared `PowerTier`/policy into its own platform's native BLE tuning
constants (`PowerProfile`) -- and Android's `PowerProfile` carries more fields
than iOS's:

| Field | Android | iOS |
|---|---|---|
| `discoveryPowerMode` | yes | yes |
| `maxConnections` | yes | yes |
| `advertiseMode` | yes (`AdvertiseSettings` constant) | not present |
| `txPowerLevel` | yes (`AdvertiseSettings` constant) | not present |
| `scanMode` | yes (`ScanSettings` constant) | not present |
| `connectionPriority` | yes (`BluetoothGatt` constant) | not present |

This is a genuine CoreBluetooth API limitation, not a parity gap MeshLink
chose not to close. `CBCentralManager`/`CBPeripheralManager` do not expose any
app-facing equivalent of Android's advertise mode, TX power level, scan
mode/duty-cycle, or GATT connection priority -- iOS's peripheral advertising
API is limited to two dictionary keys (local name and service UUIDs), and
neither the central nor peripheral manager APIs offer a power/latency tuning
knob at all. There is no additional iOS-side state this workstream could add
to reach field-level parity with Android's `PowerProfile`; the underlying
platform simply does not offer that control surface to applications.

Both platforms still apply the same shared `PowerTier`/threshold/hysteresis
decision logic above, and both platforms' `PowerProfile.maxConnections` and
`discoveryPowerMode` stay in lockstep with it -- the divergence is confined to
the handful of platform-only BLE tuning constants neither platform's
counterpart can express.

## Related docs

- [MeshLink runtime behavior reference](../reference/meshlink-runtime-behavior.md)
- [How to structure a robust MeshLink integration](../how-to/structure-a-robust-meshlink-integration.md)
- [About integrating MeshLink well](about-integrating-meshlink.md)
- [Regulatory compliance and region clamping](regulatory-compliance.md)
- [BLE connection robustness](ble-connection-robustness.md) -- the Android connection-budget admission-control implementation and the platform connection-limit research behind the `maxConnections` default values.
