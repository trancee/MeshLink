# Scan, advertise and carry links over GATT on iOS

Status: ready-for-agent
Type: AFK
Blocked by: 12

## Behavior

On iOS, a node gets a CoreBluetooth `RadioPort`. It advertises the MESH UUID and the payload UUID while in the foreground, scans for the MESH UUID, publishes the MeshLink GATT service as peripheral, and connects, reads the capability, subscribes and streams as central. A powered-off radio, denied authorization or the simulator show as `Blocked(requirements)`. The adapter logic sits behind a port so `iosTest` can drive it with fakes; the CoreBluetooth calls themselves stay thin and are listed in a CI-named file.

## Spec and ADR references

- [ADR 0009](../../../docs/adr/0009-ble-bearers-and-phy-policy.md) decisions 1, 6 (an iPhone accepts at most 3 incoming links; enforcement is ticket 29) and 8 (platform rules, including the peripheral's control-read reuse).
- [ADR 0010](../../../docs/adr/0010-discovery-and-advertising.md) decisions 1 and 5 (a backgrounded iPhone advertises only the overflow bit; centrals then read the `ADVERTISEMENT` TLV).
- [ADR 0007](../../../docs/adr/0007-public-api-shape.md) decision 3 (`BluetoothOff`, `PermissionDenied(kind)`, `Unsupported` for the simulator) and decision 13 (own dispatcher).
- [ADR 0017](../../../docs/adr/0017-testing-architecture.md) decision 6 (port with fakes in `iosTest`; thin CoreBluetooth calls in a CI-named file, exercised by device runs).
- [ADR 0005](../../../docs/adr/0005-toolchain-and-dependency-baseline.md) "Coverage" (`iosMain` thin adapters only).
- Spec: [advertisement](../../../docs/spec/discovery.md#advertisement), [GATT service](../../../docs/spec/discovery.md#gatt-service), [capability record](../../../docs/spec/discovery.md#capability-record), [roles and bearers](../../../docs/spec/link.md#roles-and-bearers).
- Evidence: [bearer bake-off library rules](../../../docs/evidence/2026-11-bearer-bakeoff/library-rules.md).

## Acceptance criteria

- [ ] The iOS radio is split in two. A Kotlin adapter in `iosMain` holds sequencing and state (operation queue, MTU from `maximumWriteValueLengthForType`, `isReadyToSendWriteWithoutResponse` and `peripheralManagerIsReadyToUpdateSubscribers` pacing) behind an internal CoreBluetooth port. A thin layer calls `CBCentralManager`, `CBPeripheralManager` and `CBPeripheral` and does nothing else.
- [ ] Every thin-layer file is listed in the CI-named file from ADR 0005 "Coverage", and a check task in `check` fails if an `iosMain` file is neither listed nor covered by `iosTest`.
- [ ] Advertising uses `CBAdvertisementDataServiceUUIDsKey` with the MESH UUID and the payload UUID, built from the 16 bytes in canonical order (D-12). The GATT service matches D-13: capability readable without encryption, write without response, notify.
- [ ] Central order: read capability, enable notifications, then stream. The peripheral serves the capability record with the `ADVERTISEMENT` TLV. A reconnecting central that sends no new subscribe reuses the link through the control read (ADR 0009 decision 8).
- [ ] `CBManagerState` and `CBManager.authorization` map to `BluetoothOff`, `PermissionDenied(kind)` and `Unsupported`; the state callback clears `Blocked` by itself. On the simulator the node reports `Blocked(Unsupported)`, asserted by an `iosTest` and the SwiftPM consumer test.
- [ ] `iosTest` fakes of the port cover every branch of the Kotlin adapter: discovery, connect, capability read, subscribe, chunked writes under back-pressure, notifications, disconnect, and each requirement state.
- [ ] Out of scope, named in KDoc or the PR: background advertising and scanning rules and state restoration (ticket 32), the 3-link cap (ticket 29), L2CAP CoC (ticket 31).
- [ ] `./gradlew iosSimulatorArm64Test` and `./gradlew check` pass; the consumer test passes.

## Proof commands

```sh
./gradlew iosSimulatorArm64Test
./gradlew check
tools/swiftpm-consumer/test.sh
```

## Docs to update

- `PROJECT.md`: proof-matrix row for the iOS adapter (simulator fakes; real radio proof deferred to ticket 17) and the CI-named thin-adapter file.
- `CHANGELOG.md`: iOS radio entry.
- Host app guidance on `NSBluetoothAlwaysUsageDescription` belongs to the how-to guides of ticket 57; mention it in KDoc only.

## Devices needed

None. Ticket 17 proves this adapter on phones.
