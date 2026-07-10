# MeshLink does not wake a killed process or mesh headlessly

**Status:** accepted

## Context

Android can fully kill a backgrounded app process (OOM, long idle, user swipe-away).
`BleTransportAdapter`'s BLE discovery — including the supplementary `PendingIntent`-based scan
channel added for Doze/screen-off resilience (see
[docs/explanation/android-ble-skill-alignment-review.md](../explanation/android-ble-skill-alignment-review.md),
item D) — only runs while that process is alive. A manifest-declared `BroadcastReceiver` could, in
principle, let the OS wake a killed process on a scan match and let MeshLink resume meshing without
an existing `MeshEngine` instance or host-app UI.

## Decision

MeshLink does **not** support this. "Alive process = meshing, dead process = not meshing" is a
permanent product boundary, not a gap to close. MeshLink is a library embedded in a host app
(`meshLink(config, context)`), not a standalone background daemon; the constitution's Technical
Constraints say nothing about background-service guarantees, and the reference app has no notion
of "the app isn't running but the mesh is." Building headless dead-process-wake meshing would
introduce a new product capability — its own lifecycle, security posture, and battery
implications — not a BLE bug fix.

## Consequences

- No manifest-declared `BroadcastReceiver` should be added to `:meshlink` for scan/connection
  wake-up. The existing dynamically-registered receivers (`BackgroundScanSupport.kt`,
  `BluetoothStateChangeSupport.kt`) are the correct, final shape — they intentionally only survive
  while the process is alive.
- A host app that needs longer background presence than the OS naturally grants should use its own
  foreground service (`connectedDevice` type) around a live `MeshLink` instance, not expect the SDK
  to resurrect itself after being killed.
- If this boundary is ever revisited, it is a new product decision (and likely a new ADR), not a
  resumption of the work scoped out of decision D1 in
  [android-ble-skill-alignment-review.md](../explanation/android-ble-skill-alignment-review.md).
