# What device evidence does the 0.1.0 release need?

Type: grilling
Status: resolved

## Question

Decide the evidence record ADR 0019's release workflow checks for: which scenarios (discovery, direct and routed unicast, broadcast, receipts, store-and-forward, large messages, each security profile, density), on which phones and OS versions, which pairings (Android-Android, Android-iOS, iOS-iOS), pass criteria, and who runs it (device lab automation or manual).

## Context

- Constitution T9 requires an evidence record for hardware claims; ADR 0005: every radio test runs on hardware only.
- Lab: `../android-devices/devices.md`; open lab faults: Bluetooth off on 5 remote phones, nova 9 adb timeout, Pixel 4a address.

## Answer

2026-11-13, grilling; recorded as the 2026-11-13 amendment to [ADR 0017](../../../docs/adr/0017-testing-architecture.md#amendments).

- **Phones:** `p10lite`, `xcover4`, `nothing2`, `pixel6`, `se2016`, `ipx`, `ip15`, `ip12mini` (Android 8–17, iOS 15.8–27). The iOS 15/16 installs depend on the Xcode 27 smoke ticket.
- **Scenarios:** pairwise (discovery, handshake, direct unicast with receipts, 128 KiB over GATT and L2CAP CoC; unicast under all three profiles); a forced mixed-platform line of at least 3 hops (routed unicast, broadcast, store-and-forward, churn); a 10-minute fleet smoke run on at least 15 phones. Density stays simulator evidence; background is not claimed.
- **Pass:** exactly-once, intact, in-lifetime delivery with matching receipts and no crash; misses block unless root-caused to an external fault. Performance is recorded as the baseline for later releases (P4).
- **Lab app and runs:** unpublished `labapp/` (`ch.trancee.lab.host`) driven by launch arguments; an agent runs `tools/lab/`, the owner fixes physical faults.
- **New backlog need:** a HITL ticket to fix the lab faults (Bluetooth off on 5 remote phones, nova 9 adb timeout, Pixel 4a address) before the release run. The write-backlog ticket includes it.
