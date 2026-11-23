# Settle the open overflow-area questions on phones

Status: ready-for-human
Type: HITL
Blocked by: 68

## Behavior

The research note's open device questions (§8, tests 2–5) have answers recorded in the note, and the owner has decided whether Android adds the narrowing AD-type filter (§7, recommendation 7). No library behavior changes unless that decision adds the filter; if it does, that becomes its own AFK ticket.

## Spec and ADR references

- `docs/research/2026-11-23-ios-overflow-area-android.md` §7 recommendation 7, §8 and §9.
- ADR 0010 decisions 5–7 and the "Android 17 scan limits" risk.

## Acceptance criteria

- [ ] **AD-type filter (§8 test 2):** the screen-off hit rate and HCI capture of `setAdvertisingDataTypeWithData(0xFF, …)` next to the empty-data Apple filter, on Android 16 and on Android 13 or 14, against a background iOS 15 and iOS 17 advertiser; the owner's adopt-or-drop decision is recorded.
- [ ] **Packed overflow on air (§8 test 3):** a capture with AirPods, Handoff and a Watch active shows whether iOS ever puts type `0x01` after another Apple sub-field in one AD structure.
- [ ] **Collision audit (§8 test 4):** per lab iPhone, the bits left 30 min after MeshLink stops and a scrub, and the app or service behind bit 52.
- [ ] **Android 15 merge flag (§8 test 5):** the `scan_record_manufacturer_data_merge` state on an Android 15 Pixel, and whether an Android 13/14 phone with a recent Bluetooth mainline update concatenates.
- [ ] §8 and §9 of the note updated with each result and its evidence label.

## Proof commands

Device runs; the note cites each run ID. The energy lab app in `MeshLink-energy-proto` (branch `prototype/energy`, commit `fc4158d`, not yet pushed) has the switches these tests need:

- Android `Discovery`: `filter=apple` (empty data, as on `main`) or `filter=apple_ovf` (legacy `[0x01]`).
- Android `Energy`: `apple=any` (default) or `apple=ovf`.
- iOS advertiser: `svc=<uuid>+<uuid>…` advertises arbitrary service UUIDs, used to hash-test bits.

Read the overflow bitmap from the SCAN_RSP with the decoder in the note §4.2.4.

## Docs to update

The research note §8 and §9; ADR 0010 only if the AD-type filter is adopted.

## Devices needed

- Pixel on Android 16 and a Pixel on Android 15.
- An Android 13 or 14 phone.
- iPhones on iOS 15 and iOS 17 or later, one with AirPods and a paired Apple Watch.
