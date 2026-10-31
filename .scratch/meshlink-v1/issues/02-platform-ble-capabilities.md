# What does each platform's BLE stack support in 2026?

Type: research
Status: resolved

## Question

Capability matrix for Android API 26–37 and iOS 15–27 with primary-source citations: GATT client/server, L2CAP CoC (public APIs, bonding/encryption requirements, cross-platform interop), 1M/2M/Coded PHY control, extended advertising, ATT MTU and DLE, max concurrent connections (central/peripheral), advertising payload limits (iOS overflow area, service data), background execution (iOS state restoration, Android FGS types/CompanionDeviceService), permissions, address randomisation. Mark anything unverifiable.

## Answer

GATT client and server work on every target: Android 26+ and iOS 15+. LE L2CAP CoC exists on Android 29+ and iOS 11+. Its dynamic PSM must be exchanged over GATT on every connection (Core 6.1 Table 4.15). Android↔iOS CoC is not yet proven, so treat it as an optional insecure upgrade; secure CoC needs bonding.

iOS has no public API for PHY, MTU, DLE or connection interval. iOS adverts carry only the local name and service UUIDs, never service data. In the background, iOS UUIDs move to an overflow area that only iOS scanners can see. iOS sees Android extended adverts only on a 2M secondary PHY, and some older iPhones see none (cairn). A legacy 31 B advert must therefore always be present.

The attribute value is capped at 512 B. Android 14+ requests MTU 517. Observed iOS↔Android MTUs are 185 to 517, so size fragments from the measured length.

Android's GATT table is 7 links on API ≤ 34 and 8–16 on API 35+ (AOSP sysprop). cairn's practical budget is 5–6 links. Apple does not publish its connection limits.

Android background operation needs a live process: a typed `connectedDevice` FGS, which carries Play-policy risk, plus a PendingIntent scan. Android's scan quota is 5 starts per 30 s, and scans are downgraded after 10 min (AOSP defaults). iOS background is best-effort via state restoration. iOS 26 Live Activities give background scanning foreground privileges.

RPAs rotate on both platforms, so the BLE address is never a peer identity.

Findings: branch `research/platform-ble-capabilities`, file `docs/research/2026-10-31-platform-ble-capabilities.md`.
