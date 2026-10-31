# Is the device lab ready for cross-platform radio evidence?

Type: task
Status: open

## Question

Re-trust the two ECID-only iPhones (iPhone10,6, iPhone8,4), bring the iPhone 12 mini online, enable Developer Mode, and record every iPhone's model and iOS version alongside the Android inventory. Done when `xcrun devicectl list devices` shows all five iPhones connected and the inventory is written.

## Scope additions (from research, 2026-10-31)

- Xcode: this Mac has only Xcode 16.4 (macOS 27.0.1); Kotlin 2.4 is tested with Xcode 26.4, and Xcode 27 debugs only iOS 17+ devices. Install Xcode 26.x side by side (or a matching Xcode 27) and verify it runs on macOS 27, so iOS 15/16 devices remain usable ([toolchain findings](05-kmp-toolchain-2026.md)).
