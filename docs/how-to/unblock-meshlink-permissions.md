# How to unblock MeshLink permissions on Android and iOS

This guide shows you how to fix MeshLink startup or discovery when platform
permissions or first-run Bluetooth prompts are blocking progress.

Use it when:

- your app starts MeshLink but no peers ever appear
- the reference app shows startup blockers on Android
- the first physical iPhone launch stops at a Bluetooth permission prompt
- a proof-app or live-proof run stalls before peer discovery

For a first guided lesson, start with [Your first MeshLink exchange](../tutorials/your-first-meshlink-exchange.md).
For end-to-end host-app bootstrap, use [How to integrate MeshLink into a host app](integrate-meshlink-into-a-host-app.md).

## 1. Check which platform is blocked

Treat permissions as the first thing to verify when either of these happens:

- Android can start the app, but peer discovery stays empty
- iPhone launches the app for the first time and shows a Bluetooth prompt
- a real-device reference-app or proof-app run never gets past the first peer wait

Do not debug routing or delivery until both platforms have cleared their local
permission gates.

## 2. Unblock Android permissions before starting MeshLink

For Android 12 and newer, make sure the app has Nearby devices access before
calling `meshLink.start()`:

- `BLUETOOTH_SCAN`
- `BLUETOOTH_CONNECT`
- `BLUETOOTH_ADVERTISE`

For Android 11 and older, make sure the app has:

- `ACCESS_FINE_LOCATION`

If you are integrating MeshLink into your own Android app, declare the needed
permissions in the manifest and request the runtime permissions before starting
MeshLink.

If discovery still stays empty on some Android 12+ OEM builds, also grant
Location permission for the app. In Android settings, the two labels you
usually need to verify are **Nearby devices** and **Location**.

After changing Android permissions:

1. force-stop or relaunch the app
2. start MeshLink again
3. wait for peer discovery before moving on

## 3. Unblock the iPhone Bluetooth prompt

For any real iPhone run, make sure the host app includes an
`NSBluetoothAlwaysUsageDescription` entry and then launch the app once on the
physical device.

If iOS shows the Bluetooth prompt, tap **Allow** before continuing.

If Bluetooth access was denied earlier, re-enable it in iPhone Settings and then
launch the app again. The recovery path is usually under **Settings > Privacy &
Security > Bluetooth** or the app's own settings page.

After changing iPhone permission state:

1. relaunch the app
2. start MeshLink again if needed
3. wait for peer discovery before retrying the flow

## 4. Re-run the workflow you were attempting

Once permissions are fixed, go back to the task you were trying to complete:

- tutorial flow — rerun the start step and wait for `Peer found`
- host-app integration — restart the runtime and verify peer discovery or send
  flow
- reference app quickstart — restart the guided first exchange
- live-proof harness — rerun the command after the permission prompt is cleared
- proof apps — relaunch the apps and retry the exchange or benchmark

## 5. Verify that permissions are no longer the blocker

You are past the permission issue when:

- Android no longer shows startup blockers and can discover the other peer
- iPhone no longer interrupts the run with the Bluetooth prompt
- the next expected workflow state is about discovery, trust, or delivery rather
  than local platform readiness

If both platforms have the right permissions and discovery still fails, continue
with the task-specific guide you started from rather than staying in permission
troubleshooting.
