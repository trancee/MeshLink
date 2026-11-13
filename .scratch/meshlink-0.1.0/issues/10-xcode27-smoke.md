# Does Kotlin 2.4.21 build with Xcode 27.2, and do its builds run on iOS 15 and 16 phones?

Type: task
Status: resolved

## Question

Build a throwaway Kotlin/Native framework (Kotlin 2.4.21, Gradle 9.7.1, SKIE 0.10.16, iOS 15.0 deployment target) with Xcode 27.2. Link it into a minimal host app, then install and launch that app on the lab's iOS 15 and iOS 16 iPhones (`ideviceinstaller`, since Xcode 27.2 debugs on devices only from iOS 17) and on an iOS 17 or later phone. Record the warnings, whether it runs, and every command used.

## Context

- Input to [ticket 03](03-toolchain-decision.md); findings in [ticket 01](01-toolchain-refresh.md).
- Prototype work on a `prototype/xcode27-smoke` branch, never merged. Read `docs/agents/lessons.md` ("Device lab") first.

## Answer

Resolved 2026-11-13. **Yes:** Kotlin 2.4.21 builds with Xcode 27.2, and the result installs and runs on iOS 15.8.8, 16.7.16, 17.6.1, 18.7.8 and 27.2.

- **Setup:** the SE 2016 bench prototype (Kotlin 2.4.21, Gradle 9.7.1, SKIE 0.10.16, libsodium 1.0.22 cinterop, iOS 15.0 target) on branch `prototype/xcode27-smoke`, built with `DEVELOPER_DIR=/Applications/Xcode-27.2.0.app/Contents/Developer` (Xcode 27.2, build 27B52, iOS SDK 27.2) and JDK toolchain 21.
- **Framework:** `libsodium/build.sh`, then `./gradlew clean assembleMeshLinkKitReleaseXCFramework`, succeeded in 51 s. Kotlin printed no Xcode-compatibility warning, although ticket 01 expected one. The only warnings were JDK 25 restricted-method notes (`java.lang.System::load` from the Kotlin/Native compiler). The framework's build version records `minos 15.0` and `sdk 26.4`; Kotlin/Native stamps `sdk 26.4` from its own `konan.properties`, as ADR 0007 records.
- **App:** `xcodebuild -configuration Release -destination generic/platform=iOS -allowProvisioningUpdates build` succeeded (`minos 15.0`, `sdk 27.2`). The 8 warnings are all prototype Swift code (Swift 6 lock use in async contexts, an unmutated `var`) or `Info.plist` notes; none comes from Kotlin or the framework.
- **Devices:** the app ran `mode=all sections=i1 warmup=1 iterations=5`. Every X-Wing draft 11 and HPKE-PQ draft 05 vector matched, and every run ended `DONE`:

  | Phone | iOS | Install and launch |
  |---|---|---|
  | iPhone SE 2016 | 15.8.8 | `ideviceinstaller`, `idevicedebug --detach run`, `afcclient` |
  | iPhone X | 16.7.16 | same as above |
  | iPhone 15 | 17.6.1 | `xcrun devicectl device install app`, `process launch`, `copy from` |
  | iPhone SE 2020 | 18.7.8 | same as above |
  | iPhone 12 mini | 27.2 | same as above |

- **Lab facts found on the way:**
  - A free-team profile lists at most 3 devices, so the iOS 15/16 phones run the second team's `ch.trancee.lab.host.b` build.
  - When a profile is regenerated, every phone must trust the developer again (Settings › General › VPN & Device Management). Until then a launch fails with "profile has not been explicitly trusted" or CoreDevice error 10002 / FBSOpenApplicationServiceErrorDomain error 1.
  - Read a team ID from the signing certificate's OU field; never write it into the repository.
- **For ticket 03:** Xcode 27.2 can replace Xcode 16.4 as the build Xcode, because device debugging on iOS 15/16 is not needed: libimobiledevice installs, launches and pulls results there. The missing Kotlin warning means the Gradle build output cannot prove Xcode compatibility; the toolchain decision should rely on this smoke test plus CI on the `xcode-27` runner image.
