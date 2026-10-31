# Is the device lab ready for cross-platform radio evidence?

Type: task
Status: resolved

## Question

Re-trust the two ECID-only iPhones (iPhone10,6, iPhone8,4), bring the iPhone 12 mini online, enable Developer Mode, and record every iPhone's model and iOS version alongside the Android inventory. Done when `xcrun devicectl list devices` shows all five iPhones connected and the inventory is written.

## Scope additions (from research, 2026-10-31)

- Xcode: this Mac has only Xcode 16.4 (macOS 27.0.1); Kotlin 2.4 is tested with Xcode 26.4, and Xcode 27 debugs only iOS 17+ devices. Install Xcode 26.x side by side (or a matching Xcode 27) and verify it runs on macOS 27, so iOS 15/16 devices remain usable ([toolchain findings](05-kmp-toolchain-2026.md)).

## Answer

Done. iPhone inventory is recorded in the iOS section of `../android-devices/devices.md`:
- SE (2016) iOS 15.8.8 and iPhone X iOS 16.7.16: USB, paired; reachable only via libimobiledevice (CoreDevice/`devicectl` lists iOS < 17 by ECID only). iPhone X has Developer Mode on; iOS 15 has no such setting.
- iPhone 15 iOS 17.6.1 (USB) and SE (2020) iOS 18.7.8 (Wi-Fi): connected, Developer Mode on.
- iPhone 12 mini iOS 27.0.1: USB, paired, Developer Mode on (brought online by the user, 2026-10-31). Xcode 16.4 lists it as a device (`xcrun xctrace list devices`); DDI-backed `devicectl` calls (app list, process list, app launch) work. Building and debugging from Xcode 16.4 on iOS 27 is still untested; the toolchain-baseline smoke build covers it.

Tools: installed `ideviceinstaller` 1.2.0 (Homebrew, dev machine only) next to libimobiledevice 1.4.0 to install builds and read logs on iOS 15/16.

Xcode (verified on developer.apple.com/xcode/system-requirements, 2026-10-31): Xcode 27.x runs on macOS 27 but installs and debugs only on iOS 17+; Xcode 26.x supports only macOS 26.x; the installed Xcode 16.4 is officially supported up to macOS 26.1.x, yet works here with the iOS 17/18 phones.

**User decision:** keep **Xcode 16.4** as a hard constraint. The toolchain-baseline ticket's smoke build tests whether the newest Kotlin (2.4.21, officially tested with Xcode 26.4) builds and links with Xcode 16.4. If not, fall back to Kotlin 2.2.20, the newest tested with Xcode 16.4, which caps AGP at ≤ 8.11.1 and Gradle at ≤ 8.14 (kotlinlang.org multiplatform compatibility guide).

Consequence: the iOS 18.5 SDK has no CryptoKit ML-KEM/X-Wing (iOS 26 SDK), so iOS PQ crypto must be bundled.
