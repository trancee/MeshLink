# Android PHY, advertising and scanning rules

Status: ready-for-agent
Type: AFK
Blocked by: 32

## Behavior

Split from ticket 32 (Q1). The Android radio carries out the ticket 32 policy.

- **PHY (ADR 0009 decision 3, R4–R6):** connect with the 1M|2M initiating mask, then request 2M with `setPreferredPhy`. Send PHY commands only when `isLe2MPhySupported()` is true, and never on Android 8 and 9, which run GATT only and 1M only (ADR 0009 decision 9). The radio reports 2M support from the same check, so the `PHY_2M` flag follows it.
- **Rotation (ADR 0010 decision 3):** every `startAdvertising` starts a new `AdvertisingSet`, never a data update. No stop/start fallback: `AdvertisingSet` is API 26, the minSdk (Q7).
- **Scanning (ADR 0010 decision 6, R10, R11):** each core window is one `startScan` and one `stopScan`, and every `startScan` has a matching `stopScan` on every path, including errors, timeouts and cancellation. The radio never starts more than 5 scans in any 30 s. Every scan uses two hardware filters, the MESH UUID and Apple manufacturer data (company `0x004C`; amended, see Outcome), so no scan is unfiltered and screen state needs no signal (Q8).
- **Overflow sightings (ADR 0010 decision 5):** a scan result that matches only the Apple overflow MESH bit becomes a `Sighting` with a null payload.
- **RSSI:** `CentralLink.readRssi()` maps to `BluetoothGatt.readRemoteRssi`.

## Spec and ADR references

- Ticket 32 (split, Q1), its owner decisions and the `RadioPort` contract it adds.
- [ADR 0009](../../../docs/adr/0009-ble-bearers-and-phy-policy.md) decisions 3, 8 and 9; [ADR 0010](../../../docs/adr/0010-discovery-and-advertising.md) decisions 1, 3, 5–7 and amendments.
- [Library rules](../../../docs/evidence/2026-11-bearer-bakeoff/library-rules.md) R4–R6, R10, R11 and R19; [discovery evidence](../../../docs/evidence/2026-11-discovery/README.md) (background overflow bit).
- [ADR 0017](../../../docs/adr/0017-testing-architecture.md) decisions 3 and 13.

## Acceptance criteria

- [x] Robolectric tests show the 1M|2M connect mask, a 2M request after connect, and no PHY command on a device that reports no 2M support or runs Android 8 or 9.
- [x] Robolectric tests show a new `AdvertisingSet` per `startAdvertising`.
- [x] Robolectric tests show one `startScan` and one `stopScan` per window, never more than 5 starts in any 30 s, both hardware filters on every scan, and a `stopScan` on every error, timeout and cancellation path.
- [x] Robolectric tests show an overflow-bit scan result reported with a null payload, `readRssi` success and failure, and the 2M report.
- [x] `./gradlew check` passes with Kover at 100%.

## Proof commands

```sh
./gradlew check
```

## Docs to update

- `CHANGELOG.md` and the Android adapter KDoc.

## Devices needed

None. Ticket 33 proves PHY, rotation and scanning on phones.

## Outcome

- **Owner decisions (2026-11-18):** a scan start beyond the budget is deferred, not refused, because a refused `startScanning()` ends the core's scan loop. The Apple filter matches company `0x004C` with empty data, and the scanner looks for the overflow area in each raw AD structure (option A): since `android-15.0.0_r1`, `ScanRecord` concatenates a company's manufacturer data and every filter is re-checked in software, so the planned `0x01`-at-byte-0 mask would drop a background iPhone 15 whose Nearby Info comes first. Recorded in the ADR 0010 amendment "Android manufacturer data" (version 11) and in `docs/agents/lessons.md`.
- **PHY:** the 1M|2M connect mask stays in `LegacyGatt.connect` (`connectsDirectlyOverLeAndAsksForTheLargestMtu`). On Android 10+ with `isLe2MPhySupported`, `GattCentralLink` asks for 2M after connecting, and `supportsLe2MPhy` reports the same check (`aPhoneWith2MRequestsItAfterConnectingFromAndroid10`, `aPhoneWithout2MSendsNoPhyCommand`). API 37's `BluetoothGattConnectionSettings` has no PHY choice.
- **Rotation:** `advertisingAgainStopsTheEarlierSetFirst` already shows a new `AdvertisingSet` per start.
- **Scanning:** `BleScanner` starts one scan per window with both filters and `SCAN_MODE_BALANCED`, keeps the last 5 start times and defers a sixth start in 30 s until the oldest is 30 s old; `stop()` cancels a deferred start and stops after a scan failure too. Overflow sightings carry a null payload. Reading malformed records from raw bytes also avoids an API 26 `getManufacturerSpecificData` NPE that a truncated record triggered.
- **RSSI:** `readRssi()` maps to `readRemoteRssi`; a refusal or failed status is `RADIO_ERROR`, a closed link `LINK_CLOSED`.
- **Tests:** `AndroidRadioCentralTest` 66 and `AndroidRadioDiscoveryTest` 40 cases on SDKs 26 and 37, all green; the full gate passes with Kover at 100 % line and branch coverage.
- **Unverified:** API 28 and Android 15–16 are not pinned on the host (only 26 and 37). Real PHY negotiation, throttling, filter offload and the extra screen-off wakeups from the Apple-only filter are left to ticket 33 on phones.
