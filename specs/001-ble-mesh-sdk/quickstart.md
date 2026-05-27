# Quickstart: MeshLink offline BLE mesh SDK

## Goal

Bring up two devices with MeshLink, keep them offline, and complete a first
encrypted message exchange without servers or user accounts.

## Prerequisites

- Two devices running supported platforms:
  - Android API 29+
  - iOS 15+
- Bluetooth enabled on both devices
- Required BLE and background permissions granted by the host app
- The shared `meshlink` KMP module built locally
- No internet connection required after installation

## 1. Add the shared KMP module to the app workspace

- Include the `meshlink` module in the Gradle build for Android.
- For iOS, use direct KMP framework integration via the Gradle-generated Apple
  framework and Xcode run-script embedding.
- Do not add extra runtime libraries beyond the shared module and the
  constitutionally allowed coroutines dependency.

## 2. Create a MeshLink configuration

Use the shared cross-platform DSL. The proof apps keep the default
`deliveryRetryDeadline` of 15 seconds; override it only when you want to
exercise the no-route retry window explicitly.

```kotlin
import kotlin.time.Duration.Companion.seconds

val config = meshLinkConfig {
    appId = "demo.meshlink"
    regulatoryRegion = RegulatoryRegion.DEFAULT
    powerMode = PowerMode.Automatic
    deliveryRetryDeadline = 15.seconds // optional; omit to use the default
}
```

## 3. Construct the platform instance

- Android calls `meshLink(config, bootstrap = androidMeshLinkBootstrap(context))`.
- iOS calls `meshLink(config)`.
- Both factories return the same `MeshLink` surface.
- If local signing, device trust, or reference hardware blocks this step, treat
  that as an environmental blocker for the validation run rather than claiming
  quickstart success.

## 4. Start the SDK and observe peers and diagnostics

Collect:

- `state`
- `peerEvents`
- `diagnosticEvents`
- `messages`

Verify that both devices reach `Running` and begin peer discovery.

If the host app forwards battery state with `updateBattery(BatterySnapshot(...))`,
confirm that `diagnosticEvents` emits `POWER_MODE_CHANGED` entries whose
metadata includes `tier`, `advertisementIntervalMillis`,
`connectionIntervalMillis`, `scanDutyCyclePercent`, `maxConnections`,
`chunkBudgetBytes`, and `region`.

For BLE discovery validation, confirm that each proof peer advertises the fixed
MeshLink discovery UUID `4d455348` plus one second 128-bit UUID carrying the
16-byte MeshLink discovery payload in a single advertisement with no scan
response dependency.

## 5. Keep both devices offline and within BLE range

- Disable Wi-Fi and cellular if you want to prove the offline path explicitly.
- Keep the devices within direct BLE range for the first validation run.
- On first contact, MeshLink uses TOFU and persists the peer identity locally.
- For multi-hop validation, use the shared harness or a three-device topology
  where the sender reaches the destination only through a relay.

## 6. Send the first message

Use a payload below the 64 KiB release limit.

```kotlin
val result = meshLink.send(peerId, "hello mesh".encodeToByteArray())
```

Expected outcome:

- `SendResult.Sent` on the sender
- an inbound message on `messages` for the recipient
- no backend or account interaction

## 7. Validate restart and trust behavior

- Restart one device and start MeshLink again.
- Confirm that the trusted peer can resume communication without re-enrollment.
- Confirm that a simulated identity mismatch results in a trust-failure
  outcome.

## 8. Validate bounded failure behavior

- Attempt a send while no route exists and confirm MeshLink keeps delivery
  state in memory until the configured `deliveryRetryDeadline` expires,
  schedules bounded exponential-backoff retries while the route is unavailable,
  and returns `SendResult.NotSent(UNREACHABLE)` if no route appears before
  expiry.
- Restore a valid route before the deadline expires and confirm MeshLink retries
  immediately without requiring the host app to resubmit the message.
- Attempt a routed 64 KiB send while the transport enforces a 512-byte per-hop
  delivery ceiling and confirm the payload still arrives intact.
- Attempt a send larger than 64 KiB and confirm immediate size-limit rejection.

## 9. Run local quality gates

Recommended local gates:

```bash
./gradlew :meshlink:check
./gradlew :meshlink:apiCheck
./gradlew :benchmarks:jvmBenchmark
```

## Reviewer evidence expectations

For a reviewer to claim this quickstart passed, retain evidence for the exact
run that was performed:

- device pair and proof `appId`
- sender success evidence such as `SendResult.Sent` or proof-app log lines
- recipient evidence such as `MSG from ... bytes=`
- any blocker evidence if the run could not complete
- blocked-run evidence for missing or revoked BLE permissions

If a run is blocked by the environment, record that blocker explicitly and stop
short of claiming quickstart success.

## Expected first proof point

A reviewer should be able to complete a first offline message exchange between
both devices in 30 minutes or less using this flow and the runnable proof apps.
