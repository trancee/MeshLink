# Android BLE — Permissions Reference

<target_12_plus>
## Targeting Android 12+ (API 31+)

Declare the runtime permissions your app actually uses:

```xml
<manifest>
  <!-- Legacy permissions, only granted on Android 11 and below -->
  <uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
  <uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />

  <!-- Needed only if your app looks for BLE devices -->
  <uses-permission android:name="android.permission.BLUETOOTH_SCAN"
      android:usesPermissionFlags="neverForLocation" />

  <!-- Needed only if your app makes the device discoverable -->
  <uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />

  <!-- Needed only if your app connects to already-paired/bonded devices -->
  <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />

  <!-- Only if you actually derive physical location from scan results -->
  <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
</manifest>
```

- `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT` are **runtime permissions** — request them explicitly (they all surface as one "Nearby devices" system prompt).
- Set `android:maxSdkVersion="30"` on the legacy `BLUETOOTH`/`BLUETOOTH_ADMIN` permissions so the system only grants what's needed per OS version.

### `neverForLocation` — strongly assert no location use

If your app never derives physical location from scan results, add `android:usesPermissionFlags="neverForLocation"` to `BLUETOOTH_SCAN` and drop (or cap) `ACCESS_FINE_LOCATION`:

```xml
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"
    android:maxSdkVersion="30" />
```

**Trade-off:** `neverForLocation` filters some BLE beacons out of scan results. Only use it if you can make the assertion truthfully.
</target_12_plus>

<target_11_minus>
## Targeting Android 11 or Lower (API ≤ 30)

```xml
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
```

- `BLUETOOTH` is required for any Bluetooth classic or BLE communication (connect, accept, transfer data).
- `ACCESS_FINE_LOCATION` is required because scan results could reveal user location on these versions — and it's a **runtime permission**, request it in addition to declaring it.
- `BLUETOOTH_ADMIN` is needed only to initiate device discovery or change Bluetooth settings (most apps need it just to discover devices — don't use its other capabilities unless you're a "power manager" app).
- On a foreground service running on Android 10 (API 29) or 11 (API 30) that discovers Bluetooth devices, also declare `ACCESS_BACKGROUND_LOCATION`.

**Skip location permissions entirely on API 26+:** `CompanionDeviceManager` can scan for companion devices on the app's behalf without any location permission — see `service-pattern-and-background.md`.
</target_11_minus>

<feature_declaration>
## Declaring and Checking BLE Support

```xml
<!-- Required: Google Play hides the app from devices lacking the feature -->
<uses-feature android:name="android.hardware.bluetooth_le" android:required="true" />

<!-- Optional: app still installs on devices without BLE; check at runtime -->
<uses-feature android:name="android.hardware.bluetooth_le" android:required="false" />
```

Only set `required="true"` if the app is unusable without BLE — it removes the app from Play Store search/install on devices that lack the feature.

Runtime check when `required="false"`:
```kotlin
val bleAvailable = packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
```
</feature_declaration>
