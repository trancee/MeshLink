# macOS Verification Checklist

These steps close the environment-gated gaps left open by the M004 validation.
They require macOS, Xcode 15+, and physical iOS + Android devices.
Run them in the order listed — each step is a prerequisite for the next.

---

## Prerequisites

```bash
# Xcode 15+ (includes command-line tools)
xcodebuild -version

# Java 21 — required by Gradle
java -version   # should print 21.x; if not: brew install --cask zulu21

# Verify Gradle wrapper
./gradlew --version
```

**Physical devices** for Gap 5:
- iOS device running iOS 15+ (Simulator cannot run BLE peripheral role)
- Android device running API 26+

---

## Gap 1 — Build libsodium for iOS

Downloads libsodium 1.0.20 source and cross-compiles static libraries for `iosArm64` and
`iosSimulatorArm64`. Required by the cinterop binding in `:meshlink`. Needs internet access
and Xcode command-line tools. Only needs to run once per machine.

```bash
bash scripts/build-ios-libsodium.sh
```

**Pass criteria** — both files exist after the script completes:

```
meshlink/src/iosMain/interop/lib/iosArm64/libsodium.a
meshlink/src/iosMain/interop/lib/iosSimulatorArm64/libsodium.a
```

---

## Gap 2 — Verify SKIE Swift compilation

SKIE 0.10.11 generates Swift `AsyncStream` wrappers for all public `Flow` properties on
`MeshLink`. The Kotlin side compiled on Linux CI, but the Swift-side wrapper compilation
was never confirmed.

```bash
# Step 1 — Kotlin compile for both iOS targets (catches cinterop + SKIE plugin issues)
./gradlew :meshlink:compileKotlinIosArm64 :meshlink:compileKotlinIosSimulatorArm64
```

**Pass criteria:** both tasks exit 0 with no errors.

Swift-side confirmation is done as part of Gap 3 (the Xcode build includes the generated
Swift shims). If you want to inspect the generated wrappers before building:

```bash
find meshlink/build -name '*.swift' -path '*skie*' | head -10
```

---

## Gap 3 — Assemble XCFramework and build the iOS reference app

The Xcode project references `MeshLink.xcframework` at the Gradle output path
`meshlink/build/XCFrameworks/release/MeshLink.xcframework`. The framework must be assembled
before Xcode can build the sample app.

### 3a — Assemble XCFramework

```bash
./gradlew :meshlink:assembleMeshLinkReleaseXCFramework
```

Verify the output contains both slices:

```bash
ls meshlink/build/XCFrameworks/release/MeshLink.xcframework/
# Expected: ios-arm64/  ios-arm64-simulator/  Info.plist
```

### 3b — Build the iOS sample app in Xcode

```bash
open meshlink-sample/iosApp/MeshLinkSample.xcodeproj
```

In Xcode:
1. Select your physical iOS device (or an arm64 simulator) in the device picker.
2. **Product → Build** (⌘B).

**Pass criteria:** build succeeds with 0 errors. Confirm that `MeshEngineBridge.swift`
compiles cleanly — it imports `MeshLink` and uses the SKIE-generated Swift API directly.

---

## Gap 4 — Maven Central publish

Publication is triggered automatically by pushing a version tag. Before doing so, add the
four required secrets to the GitHub repository
(**Settings → Secrets and variables → Actions → New repository secret**):

| Secret name | Value |
|---|---|
| `MAVEN_CENTRAL_USERNAME` | OSSRH / Maven Central portal username |
| `MAVEN_CENTRAL_PASSWORD` | OSSRH token (generate one at [central.sonatype.com](https://central.sonatype.com) — not your login password) |
| `SIGNING_KEY` | ASCII-armored GPG private key: `gpg --export-secret-keys --armor <KEY_ID>` |
| `SIGNING_PASSWORD` | Passphrase for the GPG key |

Once the secrets are in place, push the tag:

```bash
git tag v0.1.0
git push origin v0.1.0
```

The `release.yml` workflow runs three parallel jobs:

- **publish-android** (Linux) — uploads Android AAR, JVM JAR, and KMP metadata to OSSRH.
- **publish-ios** (macOS) — runs `build-ios-libsodium.sh` then uploads iOS KLib publications.
- **publish-xcframework** (macOS) — assembles the XCFramework, zips it, computes the SHA-256
  checksum, uploads `MeshLink.xcframework.zip` to the GitHub Release, then commits the updated
  `Package.swift` (with real URL and checksum) back to `main`.

After the workflow completes (allow ~15–30 min for Maven Central propagation), verify:

```bash
# In a fresh Gradle project that resolves from mavenCentral()
./gradlew dependencies --configuration runtimeClasspath | grep ch.trancee
# Expected: ch.trancee:meshlink:0.1.0
```

For SPM, add the repo URL in Xcode (**File → Add Package Dependencies**) and confirm
`Package.swift` at the repo root resolves to the real checksum written by the workflow.

---

## Gap 5 — Two-device BLE UAT

Full procedure is in `S06-UAT.md` at the repo root. Steps below are the critical path.

### Install

**Android:**

```bash
./gradlew :meshlink-sample:assembleDebug
adb -s <device-serial> install -r \
  meshlink-sample/build/outputs/apk/debug/meshlink-sample-debug.apk
```

**iOS:**

```bash
open meshlink-sample/iosApp/MeshLinkSample.xcodeproj
# Select the physical iOS device → Product → Run (⌘R)
```

On both devices, grant all Bluetooth permission dialogs when they appear.

### Verification sequence

Place both devices within 5 m of each other with Bluetooth enabled.

| # | Step | Pass criteria |
|---|------|--------------|
| 1 | Open the app on both devices | **Settings** tab shows state `RUNNING` within 2 s |
| 2 | Navigate to **Mesh Visualizer** (🕸 tab) on both devices | Both devices appear as nodes in the graph within 30 s |
| 3 | Navigate to **Chat** on Device A, send a message | Message appears on Device B within 5 s |
| 4 | Navigate to **Diagnostics** on either device | `PEER_DISCOVERED` event visible in the event log |

Seven full scenarios including negative cases (background/foreground transitions, restart
recovery) are documented in `S06-UAT.md`.

---

## Summary

| Gap | Command / action | Requires |
|-----|-----------------|----------|
| 1 — libsodium build | `bash scripts/build-ios-libsodium.sh` | macOS + Xcode CLT |
| 2 — SKIE compile | `./gradlew :meshlink:compileKotlinIosArm64 ...SimulatorArm64` | macOS |
| 3 — XCFramework + Xcode build | `./gradlew :meshlink:assembleMeshLinkReleaseXCFramework` + Xcode ⌘B | macOS + Xcode 15+ |
| 4 — Maven Central publish | Add 4 repo secrets → `git tag v0.1.0 && git push origin v0.1.0` | GitHub Actions + OSSRH account |
| 5 — Two-device BLE UAT | Install APK + Xcode run → follow `S06-UAT.md` | 1 Android + 1 iOS device |
