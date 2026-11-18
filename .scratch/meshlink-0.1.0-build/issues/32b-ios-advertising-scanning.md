# iOS advertising, background and scanning rules

Status: done
Type: AFK
Blocked by: 32

## Behavior

Split from ticket 32 (Q1). The Core Bluetooth radio carries out the ticket 32 policy.

- **Rotation (ADR 0010 decision 3):** each epoch's `startAdvertising` stops and starts the advertisement; iOS keeps the address (decision 7).
- **Background (ADR 0010 decisions 5 and 6):** a backgrounded iPhone advertises only the MESH bit in Apple's overflow area, which iOS does on its own. Background scans restart every 10 s. A MESH service sighting without the rotating UUID becomes a `Sighting` with a null payload, which the core turns into a capability read.
- **PHY:** CoreBluetooth reports no 2M support, so the radio reports none and `PHY_2M` stays 0 (Q9).
- **RSSI:** `CentralLink.readRssi()` maps to `CBPeripheral.readRSSI`.

## Spec and ADR references

- Ticket 32 (split, Q1), its owner decisions and the `RadioPort` contract it adds.
- [ADR 0009](../../../docs/adr/0009-ble-bearers-and-phy-policy.md) decisions 3, 8 and 9; [ADR 0010](../../../docs/adr/0010-discovery-and-advertising.md) decisions 1, 3, 5–7 and amendments.
- [Library rules](../../../docs/evidence/2026-11-bearer-bakeoff/library-rules.md) R4–R6, R10, R11 and R19; [discovery evidence](../../../docs/evidence/2026-11-discovery/README.md) (background overflow bit).
- [ADR 0017](../../../docs/adr/0017-testing-architecture.md) decisions 3 and 13.

## Acceptance criteria

- [x] iOS tests with fakes show stop/start rotation, the 10 s background scan restart, overflow-bit sightings reported with a null payload, `readRssi` success and failure, and no 2M support.
- [x] `./gradlew check` passes with Kover at 100%, and `./gradlew iosSimulatorArm64Test` passes.

## Proof commands

```sh
./gradlew check
./gradlew iosSimulatorArm64Test
```

## Docs to update

- `CHANGELOG.md` and the iOS adapter KDoc.

## Devices needed

None. Ticket 33 proves rotation and background discovery on phones.

## Outcome

- **Rotation:** `advertisingStopsAnyEarlierAdvertisementAndAdvertisesTheMeshServiceAndThePayload` already shows `stopAdvertising` before each `advertise`; the `AppleRadio.startAdvertising` KDoc now names decisions 3, 5 and 7.
- **10 s restart:** no adapter timer. Each core scan window starts a fresh `scanForPeripherals` and lasts 4 s in every schedule (ticket 36 changes only the gap). `aCentralScannerRestartsTheScanWithinTenSeconds` runs the core `Scanner` over `AppleRadio` and the fake and sees `scan`, `stopScan`, `scan` within 10 s. Recorded in the ADR 0010 amendment "iOS scanning" (version 12). This test passed before any change: it pins existing behavior, not new code.
- **Null-payload sightings:** every discovery while scanning is a sighting; one without the rotating UUID (MESH only, or no `CBAdvertisementDataServiceUUIDsKey` UUID at all, as for an overflow-only background iPhone) has a null payload (`aMeshSightingWithoutAPayloadIsASightingWithoutPayload`).
- **RSSI:** `PeripheralCalls.readRssi` calls `CBPeripheral.readRSSI()`, and `peripheral(_:didReadRSSI:error:)` answers through `PeripheralEvents.rssiRead`. A failed read is `RADIO_ERROR`, a closed link `LINK_CLOSED` (`theLinkReadsTheRssiOfItsPeripheral`, `aFailedRssiReadIsARadioError`, `readingTheRssiOfAClosedLinkIsAClosedLink`).
- **No 2M:** `theRadioClaimsNoLe2MPhy`.
- **Tests:** the full gate passes with Kover at 100 % line and branch coverage; `:meshlink:compileKotlinIosArm64` compiles the thin layer.
- **Unverified:** the thin layer's `readRSSI` and overflow discovery run only on phones; ticket 33 proves rotation and background discovery there.
