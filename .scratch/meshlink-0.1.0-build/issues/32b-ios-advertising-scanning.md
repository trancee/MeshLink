# iOS advertising, background and scanning rules

Status: ready-for-agent
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

- [ ] iOS tests with fakes show stop/start rotation, the 10 s background scan restart, overflow-bit sightings reported with a null payload, `readRssi` success and failure, and no 2M support.
- [ ] `./gradlew check` passes with Kover at 100%, and `./gradlew iosSimulatorArm64Test` passes.

## Proof commands

```sh
./gradlew check
./gradlew iosSimulatorArm64Test
```

## Docs to update

- `CHANGELOG.md` and the iOS adapter KDoc.

## Devices needed

None. Ticket 33 proves rotation and background discovery on phones.
