# meshlink-sample

Two-device integration harness for MeshLink S04 hardware verification.
Targets Android (`:meshlink-sample:androidApp`) and iOS (`iosApp/`).

---

## Android sample app

### Prerequisites
- Android Studio Meerkat or later
- Android 12+ device (API 31) with Bluetooth hardware
- `adb` in PATH

### Build & install
```bash
# From repository root
./gradlew :meshlink-sample:androidApp:assembleDebug
adb install -r meshlink-sample/androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

### Runtime log filter
```bash
adb logcat -s MeshLink:D
```

### GATT-only mode (force GATT, disable L2CAP)
Add `-PFORCE_GATT=true` to the Gradle command and rebuild.

---

## iOS sample app

### Prerequisites
- macOS 14+ with Xcode 16+
- iOS 16+ device (two iPhones, or one iPhone + the Android device)
- Kotlin Multiplatform plugin for Xcode (optional, for source navigation)

### Step 1 — Build the MeshLink XCFramework

Run this once per code change (from repository root on macOS):

```bash
./gradlew :meshlink:assembleMeshLinkReleaseXCFramework
```

The framework is assembled to:
```
meshlink/build/XCFrameworks/release/MeshLink.xcframework
```

For a debug build (larger binary, assertions enabled):
```bash
./gradlew :meshlink:assembleMeshLinkDebugXCFramework
```

### Step 2 — Open the Xcode project

```bash
open meshlink-sample/iosApp/MeshLinkSample.xcodeproj
```

> **Note:** `MeshLinkSample.xcodeproj` is a minimal hand-authored project file sufficient for building and
> running the sample. A full xcworkspace / SwiftPM integration can be added when the library is
> distributed via SPM (planned for M004/S02).

### Step 3 — Add the XCFramework to the Xcode project

1. In the Xcode project navigator select the **MeshLinkSample** target.
2. Go to **General → Frameworks, Libraries, and Embedded Content**.
3. Click **+** → **Add Other… → Add Files…**.
4. Navigate to `meshlink/build/XCFrameworks/release/MeshLink.xcframework` and click **Add**.
5. Make sure "Embed & Sign" is selected for the framework.

### Step 4 — Configure signing

Set your Team in **Signing & Capabilities** or add `DEVELOPMENT_TEAM = <team-id>` to
`iosApp/MeshLinkSample.xcodeproj/project.pbxproj`.

### Step 5 — Run on device

Select your connected iPhone as the run destination and press **⌘R**.
Grant Bluetooth permissions when prompted.

### Runtime log filter

Logs are prefixed with `[MeshLink]`.  Filter in **Console.app** (device selected, search bar):

```
[MeshLink]
```

Or attach Xcode's debugger console directly.

---

## S04 UAT — Two-device hardware test

See [S04-UAT.md](../.gsd/milestones/M003/slices/S04/S04-UAT.md) for the full runbook.

### Quick reference: L2CAP path (primary)

| Step | Android | iOS |
|------|---------|-----|
| 1 | Install + launch sample APK | Install + launch MeshLinkSample |
| 2 | Grant all Bluetooth permissions | Grant Bluetooth permission |
| 3 | Tap **Start** (or let auto-start) | Tap **Start** |
| 4 | Watch `adb logcat -s MeshLink:D` | Watch Console.app `[MeshLink]` |
| 5 | Expect `🔵 Peer connected` within ~10 s | Expect `🔵 Peer connected` within ~10 s |
| 6 | Auto-send fires: `📤 send →` | Auto-send fires: `📤 send →` |
| 7 | Expect `✅ Delivery ACK` on sender | Expect `✅ Delivery ACK` on sender |
| 8 | Expect `📨 Message from` on receiver | Expect `📨 Message from` on receiver |

Total Noise XX handshake time should be **≤ 28 s** from scan to first ACK.

### Quick reference: GATT fallback path

1. On the Android device, set `FORCE_GATT=true` via `-PFORCE_GATT=true` and reinstall.
2. Repeat the L2CAP test above — the log should show `GATT` connection path instead of `L2CAP`.
3. Delivery ACK must still be observed on both ends.

---

## Module structure

```
meshlink-sample/
├── build.gradle.kts                  # Container project (no build logic)
├── shared/
│   ├── build.gradle.kts              # KMP module (commonMain + iosMain + androidMain)
│   └── src/
│       ├── commonMain/               # Compose Multiplatform UI (App, screens, MeshController)
│       ├── androidMain/              # PlatformMeshLink.android.kt (expect/actual)
│       └── iosMain/                  # PlatformMeshLink.ios.kt + MainViewController.kt
├── androidApp/
│   ├── build.gradle.kts              # Pure Android app (com.android.application)
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── kotlin/.../MainActivity.kt
└── iosApp/
    └── MeshLinkSample/
        ├── SampleApp.swift           # @main SwiftUI entry
        ├── MeshEngineBridge.swift     # ObservableObject wrapping MeshLink.createIos()
        ├── ContentView.swift          # Start/Stop buttons + scrolling log
        └── Info.plist                 # BLE usage + background modes
```

The shared Kotlin library lives in the `:meshlink` module at the repository root.
