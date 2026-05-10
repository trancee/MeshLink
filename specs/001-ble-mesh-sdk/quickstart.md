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

Configure the SDK with a single cross-platform DSL.

```kotlin
val config = meshLinkConfig {
    appId = "demo.meshlink"
    regulatoryRegion = RegulatoryRegion.DEFAULT
    powerMode = PowerMode.Automatic
}
```

## 3. Construct the platform instance

- Android host apps call the Android factory and pass the Android context.
- iOS host apps construct the exported framework object through the iOS factory.
- Both factories return the same `MeshLinkApi` surface.

## 4. Start the SDK and observe peers and diagnostics

Collect:
- `state`
- `peerEvents`
- `diagnosticEvents`
- `messages`

Verify that both devices reach `Running` and begin peer discovery.

## 5. Keep both devices offline and within BLE range

- Disable Wi-Fi and cellular if you want to prove the offline path explicitly.
- Keep the devices within direct BLE range for the first validation run.
- On first contact, MeshLink uses TOFU and persists the peer identity locally.

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
- Confirm that a simulated identity mismatch results in a trust-failure outcome.

## 8. Validate bounded failure behavior

- Attempt a send while no route exists and confirm bounded retry followed by an
  explicit unreachable or expired outcome.
- Attempt a send larger than 64 KiB and confirm immediate size-limit rejection.

## 9. Run local quality gates

Recommended local gates once the implementation exists:

```bash
./gradlew :meshlink:check
./gradlew :meshlink:apiCheck
./gradlew :benchmarks:jvmBenchmark
```

## Expected first proof point

A reviewer should be able to complete a first offline message exchange between
both devices in 30 minutes or less using this flow and the platform sample apps.
