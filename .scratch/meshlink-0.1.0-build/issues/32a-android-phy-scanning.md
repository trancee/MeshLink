# Android PHY, advertising and scanning rules

Status: ready-for-agent
Type: AFK
Blocked by: 32

## Behavior

Split from ticket 32 (Q1). The Android radio carries out the ticket 32 policy.

- **PHY (ADR 0009 decision 3, R4–R6):** connect with the 1M|2M initiating mask, then request 2M with `setPreferredPhy`. Send PHY commands only when `isLe2MPhySupported()` is true, and never on Android 8 and 9, which run GATT only and 1M only (ADR 0009 decision 9). The radio reports 2M support from the same check, so the `PHY_2M` flag follows it.
- **Rotation (ADR 0010 decision 3):** every `startAdvertising` starts a new `AdvertisingSet`, never a data update. No stop/start fallback: `AdvertisingSet` is API 26, the minSdk (Q7).
- **Scanning (ADR 0010 decision 6, R10, R11):** each core window is one `startScan` and one `stopScan`, and every `startScan` has a matching `stopScan` on every path, including errors, timeouts and cancellation. The radio never starts more than 5 scans in any 30 s. Every scan uses two hardware filters, the MESH UUID and Apple manufacturer data (company `0x004C`, type `0x01`) masked to the MESH overflow bit, so no scan is unfiltered and screen state needs no signal (Q8).
- **Overflow sightings (ADR 0010 decision 5):** a scan result that matches only the Apple overflow MESH bit becomes a `Sighting` with a null payload.
- **RSSI:** `CentralLink.readRssi()` maps to `BluetoothGatt.readRemoteRssi`.

## Spec and ADR references

- Ticket 32 (split, Q1), its owner decisions and the `RadioPort` contract it adds.
- [ADR 0009](../../../docs/adr/0009-ble-bearers-and-phy-policy.md) decisions 3, 8 and 9; [ADR 0010](../../../docs/adr/0010-discovery-and-advertising.md) decisions 1, 3, 5–7 and amendments.
- [Library rules](../../../docs/evidence/2026-11-bearer-bakeoff/library-rules.md) R4–R6, R10, R11 and R19; [discovery evidence](../../../docs/evidence/2026-11-discovery/README.md) (background overflow bit).
- [ADR 0017](../../../docs/adr/0017-testing-architecture.md) decisions 3 and 13.

## Acceptance criteria

- [ ] Robolectric tests show the 1M|2M connect mask, a 2M request after connect, and no PHY command on a device that reports no 2M support or runs Android 8 or 9.
- [ ] Robolectric tests show a new `AdvertisingSet` per `startAdvertising`.
- [ ] Robolectric tests show one `startScan` and one `stopScan` per window, never more than 5 starts in any 30 s, both hardware filters on every scan, and a `stopScan` on every error, timeout and cancellation path.
- [ ] Robolectric tests show an overflow-bit scan result reported with a null payload, `readRssi` success and failure, and the 2M report.
- [ ] `./gradlew check` passes with Kover at 100%.

## Proof commands

```sh
./gradlew check
```

## Docs to update

- `CHANGELOG.md` and the Android adapter KDoc.

## Devices needed

None. Ticket 33 proves PHY, rotation and scanning on phones.
