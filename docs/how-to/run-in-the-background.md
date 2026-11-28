# Run in the background

A node finds peers and moves messages only while the system lets your app use Bluetooth. This guide sets up your app so the mesh keeps working while the user isn't looking at it, where your preset needs that.

> **Unverified on devices in 0.1.0.** MeshLink 0.1.0 makes no claim about background behavior ([ADR 0017](../adr/0017-testing-architecture.md#amendments), 2026-11-13 amendment). The steps below follow the Android and Apple documentation; the device tests that measure them are still to come.

## Decide whether your app needs it

| Preset | Recommendation |
|---|---|
| `OfflineChat`, `FieldTeams` | Keep the mesh running in the background while the user expects messages, started from a user action such as "Stay reachable". |
| `EmergencyBroadcast` | Keep it running for the whole emergency session. |
| `ProximityDiscovery`, `LocalMultiplayer` | Foreground only: stop the node when the user leaves the screen. |

A foreground-only app needs none of the steps below.

## Android: run a connected-device foreground service

Android 14 and later need a typed foreground service to keep Bluetooth work going while your app is in the background ([foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types)).

1. In your manifest, declare the permissions `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_CONNECTED_DEVICE`, and your service with `android:foregroundServiceType="connectedDevice"`.
2. Start the service from a user action while your app is visible, after the user allowed the Bluetooth permissions ([Request permissions](request-permissions.md)). Android 12 and later refuse to start a foreground service from the background, with few exceptions, and a `connectedDevice` service needs a granted Bluetooth permission.
3. Call `startForeground()` with `FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE` and a notification that tells the user the mesh is running.
4. Keep the node in the service, or in an object that outlives your activities, and close it when the service stops.

If your app is on Google Play, declare the foreground service type in Play Console under **App content** ([foreground service requirements](https://support.google.com/googleplay/android-developer/answer/13392821)). Play's `connectedDevice` examples are wearables and other external devices, and the review video must show the user connecting to the device in the app. Whether Play accepts a phone-to-phone mesh under that type is not known yet; plan for the review to ask.

## iOS: declare the Bluetooth background modes

1. In your target's **Signing & Capabilities**, add **Background Modes** and turn on **Uses Bluetooth LE accessories** (`bluetooth-central`) and **Acts as a Bluetooth LE accessory** (`bluetooth-peripheral`) ([Core Bluetooth background processing](https://developer.apple.com/library/archive/documentation/NetworkingInternetWeb/Conceptual/CoreBluetooth_concepts/CoreBluetoothBackgroundProcessingForIOSApps/PerformingTasksWhileYourAppIsInTheBackground.html)).
2. Start the node while your app is in the foreground.

Know what iOS still limits:

- A background iPhone advertises its service only in Apple's overflow area. Android and foreground iPhones still find it; two background iPhones find each other only now and then ([Finding iPhones in the background](../explanation/background-iphone-discovery.md)).
- Background scanning is slower, so peers appear later.
- MeshLink has no CoreBluetooth state restoration: when iOS ends your app, the node stops until the user opens the app again.

## Check it works

Put the app in the background, then send it a message from another phone. If it never arrives, see [Troubleshoot messages that aren't delivered](troubleshoot-messages-not-delivered.md); if peers drop out, see [Troubleshoot peers that aren't found](troubleshoot-peers-not-found.md).
