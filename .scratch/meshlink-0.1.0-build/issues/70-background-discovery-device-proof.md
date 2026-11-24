# Prove background-iPhone discovery on phones

Status: ready-for-human
Type: HITL
Blocked by: 33, 57, 64, 69

## Behavior

The library itself finds and links a backgrounded iPhone on real phones, not just the prototype apps used in the research. An Android node sights a suspended iPhone's overflow MESH bit, connects without an identifier, vets the peer over GATT and exchanges a message. iPhone-to-iPhone discovery is measured with the scanner in the foreground and in the background. The results decide what a later release may claim about background behavior; 0.1.0 claims none.

Today the path has passed unit tests only (tickets 32b and 68):

- Android `BleScanner` sights the bit.
- `Scanner` turns the sighting into an anonymous candidate.
- `Links.connectTo` dials with vetting.
- `AppleCentral.discovered` emits payload-less sightings.

Ticket 33 has no background scenario, and ticket 32b's claim that it did was corrected.

## Spec and ADR references

- [ADR 0010](../../../docs/adr/0010-discovery-and-advertising.md) decision 5 and the 2026-11-18 and 2026-11-23 amendments.
- [ADR 0011](../../../docs/adr/0011-modules-toggles-and-presets.md#decision) decision 13: the host app owns background modes.
- [ADR 0017](../../../docs/adr/0017-testing-architecture.md#amendments), 2026-11-13 amendment: 0.1.0 claims no background behavior.
- [ADR 0009](../../../docs/adr/0009-ble-bearers-and-phy-policy.md#amendments): CoreBluetooth state restoration was deferred to ticket 32, which did not take it up; no code has it.
- `docs/explanation/background-iphone-discovery.md` and `docs/research/2026-11-23-ios-overflow-area-android.md`.

## Acceptance criteria

- [ ] The lab app (`labapp/ios/project.yml`) declares `UIBackgroundModes` `bluetooth-peripheral` and `bluetooth-central`, done as a small AFK change before the runs.
- [ ] **`bg-android-finds-iphone`:** with the iPhone app in the background and the screen locked, the Android node sights it, links after GATT vetting, and delivers a direct message both ways. The run records the time to first link and the number of stale addresses.
- [ ] **`bg-iphone-finds-iphone`:** the trial counts of the research (21 trials each) are repeated with the library: foreground scanner to background advertiser, and background to background.
- [ ] **`bg-suspended-long`:** the iPhone stays in the background for at least 30 min. The run records whether it keeps advertising, serving GATT and holding links, and what happens after iOS terminates the app.
- [ ] The owner decides, with the long-run evidence, whether MeshLink needs CoreBluetooth state restoration. If so, it becomes its own AFK ticket.
- [ ] `docs/explanation/background-iphone-discovery.md`, `docs/how-to/run-in-the-background.md` (ticket 57) and ADR 0010 are updated with the results and evidence labels. The "unverified" notice is narrowed to whatever is still unproven.

## Proof commands

Device runs through `tools/lab/lab.py`; the docs cite each run ID.

## Devices needed

- `pixel6` (Android 16) and an Android 13 or 14 phone.
- `ip15` (iOS 17 or later) and `ip12mini`, plus an iOS 15 iPhone if one is reachable.
