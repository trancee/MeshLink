# What device evidence does the 0.1.0 release need?

Type: grilling
Status: open

## Question

Decide the evidence record ADR 0019's release workflow checks for: which scenarios (discovery, direct and routed unicast, broadcast, receipts, store-and-forward, large messages, each security profile, density), on which phones and OS versions, which pairings (Android-Android, Android-iOS, iOS-iOS), pass criteria, and who runs it (device lab automation or manual).

## Context

- Constitution T9 requires an evidence record for hardware claims; ADR 0005: every radio test runs on hardware only.
- Lab: `../android-devices/devices.md`; open lab faults: Bluetooth off on 5 remote phones, nova 9 adb timeout, Pixel 4a address.
