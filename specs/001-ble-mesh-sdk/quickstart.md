# Quickstart: MeshLink Offline BLE Mesh SDK

## Goal

Bring up two devices with MeshLink, keep them offline, and complete a first
encrypted message exchange without servers or user accounts.

## Prerequisites

- Two devices running supported platforms:
  - Android API 29+
  - iOS 15+
- Bluetooth enabled on both devices
- Required BLE/background permissions granted by the host app
- The shared `meshlink` KMP module built locally
- No internet connection required after installation

## 1. Add the shared KMP module to the app workspace

- Include the `meshlink` module in the Gradle build for Android.
- For iOS, use direct KMP framework integration via the Gradle-generated Apple
  framework and Xcode run-script embedding.
- Do not add extra runtime libraries beyond the shared module and the
  constitutionally allowed coroutines dependency.

## 2. Create a MeshLink configuration

Configure the SDK with a single cross-platform DSL. The proof apps keep the
default `deliveryRetryDeadline` of 15 seconds; override it only when you want
to exercise the no-route retry window explicitly.

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

- Android host apps call `MeshLink.create(config, context)` and pass the Android context.
- iOS host apps call `MeshLink.create(config)`.
- The committed iOS proof sample now includes an Xcode project plus an XcodeGen
  spec. Local iPhone builds still require a development team selection in Xcode
  or a `DEVELOPMENT_TEAM=<your-team-id>` CLI override.
- Both factories return the same `MeshLinkApi` surface.
- If local signing, device trust, or reference-hardware availability blocks this
  step, treat that as an environmental blocker for the validation run and record
  the failed command plus device state instead of claiming quickstart success.

## 4. Start the SDK and observe peers and diagnostics

Collect:
- `state`
- `peerEvents`
- `diagnosticEvents`
- `messages`

Verify that both devices reach `Running` and begin peer discovery.
If the host app forwards battery state with `updateBattery(level, isCharging)`,
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
- For the multi-hop validation path, use the shared harness or a three-device
  topology where the sender can reach the destination only through a relay.

## 6. Send the first message

Use a payload below the 64 KiB release limit.

```kotlin
val result = meshLink.send(peerId, "hello mesh".encodeToByteArray())
```

Expected outcome:
- `SendResult.Sent` on the sender
- an inbound message on `messages` for the recipient
- no backend or account interaction

Proof-app note:
- Treat the runnable proof integrations as reference implementations, quickstart
  aids, benchmark harnesses, and physical-validation vehicles.
- Android proof validation has already completed on attached hardware.
- The iOS proof app now builds, installs, and launches on the attached iPhone 15
  when a local development team is supplied at build time.
- Direct L2CAP proof runs should not require OS pairing on the current proof-app
  builds.
- A fresh two-device quickstart validation now exists for iPhone 15 + OPPO
  Android 16: peer discovery succeeded, the first `hello mesh from iPhone`
  payload reached Android, and restarting the iPhone app on the same `appId`
  delivered the same payload again without re-enrollment.
- Use isolated transient `appId` values for physical validation work whenever
  nearby devices might also be advertising on the default proof mesh.
- A deeper mixed Android/iOS initiator-policy redesign now makes Android the
  deterministic L2CAP initiator for mixed-platform peers, so the iPhone can host
  the inbound channel and request low connection latency on every final retained
  proof rerun.
- The released baseline still carries the explicitly selected waiver /
  known-limitation path approved on `2026-05-14`.
- That historical released-baseline evidence on the deterministic mixed-
  platform path reached `52.03 KB/s` to the OPPO reference peer and
  `39.48 KB/s` to the Samsung reference peer.
- The latest current-head future branch now has retained product-path evidence
  above the normative iOS target on both reference peers: Samsung reached
  `61.13-68.67 KB/s` and OPPO reached `77.02-78.24 KB/s`, both with
  recipient-confirmed proof receipts retained on attempt 1.
- Do not use quickstart success from the released baseline to claim that iOS
  satisfies `SC-004`. If current iOS large-transfer behavior is described,
  distinguish the waived released baseline from the newer future branch and
  cite the matching retained evidence.

## 7. Validate restart and trust behavior

- Restart one device and start MeshLink again.
- Confirm that the trusted peer can resume communication without re-enrollment.
- Confirm that a simulated identity mismatch results in a trust-failure outcome.

## 8. Validate bounded failure behavior

- Attempt a send while no route exists and confirm MeshLink keeps delivery state
  in memory until the configured `deliveryRetryDeadline` expires, schedules
  bounded, jittered exponential-backoff retries while the route is unavailable,
  emits retry-related diagnostics during that window, and returns
  `SendResult.NotSent(UNREACHABLE)` if no route appears before expiry.
- Restore a valid route before the deadline expires and confirm MeshLink retries
  immediately without requiring the host application to resubmit the message.
- Attempt a routed 64 KiB send while the transport enforces a 512-byte per-hop
  delivery ceiling and confirm the payload still arrives intact.
- Attempt a send larger than 64 KiB and confirm immediate size-limit rejection.

## 9. Run local quality gates

Recommended local gates once the implementation exists:

```bash
./gradlew :meshlink:check
./gradlew :meshlink:apiCheck
./gradlew :benchmarks:jvmBenchmark
```

## Reviewer evidence expectations

For timed `SC-001` reader tests, measure the run from the moment a fresh reader
begins from the documented quickstart prerequisites until sender and recipient
proof logs retain the first successful encrypted-message evidence. Retain the
start timestamp, end timestamp, elapsed duration, and a short observer note for
that exact run.

For a reviewer to claim this quickstart passed, retain evidence for the exact
run that was performed:

- device pair and proof `appId`
- sender success evidence such as `SendResult.Sent` or proof-app `mesh.send(...)`
  / `BENCHMARK ...` lines
- recipient evidence such as `MSG from ... bytes=`
- any blocker evidence if the run could not complete (for example signing,
  permission, device-availability, or nearby-mesh interference logs)
- for blocked runs caused by missing or revoked BLE permissions, the surfaced
  blocked / `MeshLinkException.PermissionDenied` outcome plus the observed
  permission state (for example missing Android nearby-device / location grants
  or denied / restricted iOS CoreBluetooth authorization)

If a run is blocked by the environment, record that blocker explicitly and stop
short of claiming `SC-001` or any related benchmark criterion passed.

## Expected first proof point

A reviewer should be able to complete a first offline message exchange between
both devices in 30 minutes or less using this flow and the runnable proof
integrations in `meshlink-sample/android` and `meshlink-sample/ios`.

Current physical evidence on attached hardware now shows that this direct
quickstart flow works on iPhone 15 + OPPO Android 16, including a restart of the
iPhone proof app followed by another successful direct `hello mesh from iPhone`
delivery on the same `appId`.
