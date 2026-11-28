# Request permissions

A node can't use Bluetooth until the user allows it. MeshLink never asks the user itself: your app asks, at a moment the user understands, and the node waits as `Blocked` until it may use the radio. This guide asks for the permissions on Android and iOS and tells the user what a blocked node is missing.

## Declare what the system needs

On Android, MeshLink's manifest already declares the Bluetooth permissions, and they merge into your app's manifest. On Android 12 and later they are `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE` and `BLUETOOTH_CONNECT`; on Android 8 to 11, `ACCESS_FINE_LOCATION`.

On iOS, add `NSBluetoothAlwaysUsageDescription` to your app's `Info.plist`, with a sentence that tells the user why your app uses Bluetooth.

## Ask for them

On Android, ask from an activity, for example when the user opens the screen that uses the mesh. Use the Activity Result API of AndroidX Activity:

<!-- region: meshlink/src/androidHostTest/kotlin/ch/trancee/meshlink/docs/RequestPermissionsAndroidTest.kt#request-android -->
```kotlin
private val bluetoothPermissions =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(BLUETOOTH_SCAN, BLUETOOTH_ADVERTISE, BLUETOOTH_CONNECT)
    } else {
        arrayOf(ACCESS_FINE_LOCATION)
    }

// MeshLink checks the permissions again when an activity resumes, so the callback has nothing to do.
private val requestBluetooth =
    registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

fun askForBluetooth() = requestBluetooth.launch(bluetoothPermissions)
```

Call `askForBluetooth()` before or after `start()`. When the user allows the permissions and your activity resumes, a blocked node starts running by itself; don't start it again.

On iOS, there is nothing to call. iOS asks the user with your `Info.plist` sentence the first time a node starts.

## Tell the user what is missing

While the node can't run, its state is `Blocked`, and its requirements say why. Turn each one into a request the user can act on:

<!-- region: meshlink-testing/src/commonTest/kotlin/ch/trancee/meshlink/samples/Samples.kt#blocked -->
```kotlin
val state = node.state.value
val prompts =
    if (state is MeshState.Blocked) {
        state.requirements.map { requirement ->
            when (requirement) {
                is Requirement.PermissionDenied -> "Allow ${requirement.kind} for this app in Settings"
                Requirement.BluetoothOff -> "Turn on Bluetooth"
                Requirement.Unsupported -> "This device can't join the mesh"
                Requirement.RadioInUse -> "Another part of this app uses the mesh"
                Requirement.IdentityUnavailable -> "Unlock the device and try again"
            }
        }
    } else {
        emptyList()
    }
```

<!-- region: tools/swiftpm-consumer/Tests/MeshLinkKitConsumerTests/HowToTests.swift#blocked -->
```swift
var prompts: [String] = []
if case .blocked(let requirements) = node.currentState {
    prompts = requirements.map { requirement in
        switch requirement {
        case .permissionDenied(let kind): "Allow \(kind) for this app in Settings"
        case .bluetoothOff: "Turn on Bluetooth"
        case .unsupported: "This device can't join the mesh"
        case .radioInUse: "Another part of this app uses the mesh"
        case .identityUnavailable: "Unlock the device and try again"
        }
    }
}
```

A user who denied a permission can only allow it again in the system settings. On iOS, a denied permission is `PermissionDenied(Bluetooth)`. `Unsupported` means the device has no Bluetooth LE, or is the iOS simulator.

## Wait until the node runs

The node leaves `Blocked` as soon as the last requirement is met. To act on that, for example to hide your prompt, wait for `Running`:

<!-- region: meshlink-testing/src/commonTest/kotlin/ch/trancee/meshlink/samples/Samples.kt#leave-blocked -->
```kotlin
node.state.first { it is MeshState.Running }
```

<!-- region: tools/swiftpm-consumer/Tests/MeshLinkKitConsumerTests/HowToTests.swift#leave-blocked -->
```swift
for await state in node.stateUpdates {
    if case .running = state { break }
}
```

If the node stays blocked after the user allowed everything, see [Troubleshoot peers that aren't found](troubleshoot-peers-not-found.md#check-the-node-can-use-the-radio).
