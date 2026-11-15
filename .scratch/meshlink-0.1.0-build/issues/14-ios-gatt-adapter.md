# Scan, advertise and carry links over GATT on iOS

Status: claimed
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

- [x] The iOS radio is split in two. A Kotlin adapter in `iosMain` holds sequencing and state (operation queue, MTU from `maximumWriteValueLengthForType`, `isReadyToSendWriteWithoutResponse` and `peripheralManagerIsReadyToUpdateSubscribers` pacing) behind an internal CoreBluetooth port. A thin layer calls `CBCentralManager`, `CBPeripheralManager` and `CBPeripheral` and does nothing else.
- [x] Every thin-layer file is listed in the CI-named file from ADR 0005 "Coverage", and a check task in `check` fails if an `iosMain` file is neither listed nor covered by `iosTest`.
- [x] Advertising uses `CBAdvertisementDataServiceUUIDsKey` with the MESH UUID and the payload UUID, built from the 16 bytes in canonical order (D-12). The GATT service matches D-13: capability readable without encryption, write without response, notify.
- [x] Central order: read capability, enable notifications, then stream. The peripheral serves the capability record with the `ADVERTISEMENT` TLV. A reconnecting central that sends no new subscribe reuses the link through the control read (ADR 0009 decision 8).
- [x] `CBManagerState` and `CBManager.authorization` map to `BluetoothOff`, `PermissionDenied(kind)` and `Unsupported`; the state callback clears `Blocked` by itself. On the simulator the node reports `Blocked(Unsupported)`, asserted by an `iosTest` and the SwiftPM consumer test.
- [x] `iosTest` fakes of the port cover every branch of the Kotlin adapter: discovery, connect, capability read, subscribe, chunked writes under back-pressure, notifications, disconnect, and each requirement state.
- [x] Out of scope, named in KDoc or the PR: background advertising and scanning rules and state restoration (ticket 32), the 3-link cap (ticket 29), L2CAP CoC (ticket 31).
- [x] `./gradlew iosSimulatorArm64Test` and `./gradlew check` pass; the consumer test passes.

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

## Notes

- **Logic in `commonMain` (owner decision, deviates from criterion 1):** the adapter logic lives in `:meshlink` `commonMain` behind the internal `CoreBluetooth` port, not in `iosMain`, and its fakes (`FakeCoreBluetooth`) are in `commonTest`, not `iosTest`. Kover measures only the Android host tests, so this puts the logic under the 100% gate; the same tests still run in `iosSimulatorArm64Test`. ADR 0017 amendment of 2026-11-15.
- **Port split:** the port mirrors Core Bluetooth to stay under detekt's `TooManyFunctions` without suppressions: `CentralManagerCalls`, `PeripheralCalls` and `PeripheralManagerCalls` for calls, and one event sink per delegate (`CentralManagerEvents`, `PeripheralEvents`, `PeripheralManagerEvents`), implemented by `AppleCentral`, `AppleLinkEvents` and `ApplePeripheralHost`. The thin layer in `iosArm64Main` (`IosCoreBluetooth.kt`, `QueueDispatcher.kt`, the platform factories) forwards each call and callback and holds the weakly referenced delegates.
- **Bluetooth prompt (owner decision):** iOS may show its prompt at the first `start()`, when collecting the requirements creates both managers, never at `create()`. The node stays `Starting` until both managers report a known state; an unknown state before the user answers is `PermissionDenied(Bluetooth)`. ADR 0007 amendment of 2026-11-15.
- **Simulator (owner decision):** chosen at compile time; `iosSimulatorArm64Main` returns `UnsupportedRadioPort`.
- **Connect timeout (owner decision):** Core Bluetooth never times out a connection attempt, so the central cancels it after 30 s with `PEER_UNREACHABLE`. ADR 0009 amendment of 2026-11-15, which also records that a peripheral can't disconnect a central and the pacing rules.
- **`PlatformPort.requirements` is a `Flow`:** a radio that can't tell yet emits nothing. The internal interface is in the ABI dumps, so both changed; `CHANGELOG.md` records it.
- **Test order:** most logic came before its tests, so the tests were proven by mutation: 20 mutants across `AppleRadio`, `AppleCentral`, `AppleCentralLink` and `ApplePeripheralHost`, each killed. The 30 s timeout test asserts the attempt is still pending at exactly 30 s of virtual time before awaiting it, so a 60 s mutant fails.
- **Dokka:** `:meshlink`'s `iosArm64Main` Dokka source set is suppressed. Every declaration there is internal or private, but Dokka 2.3.0 still resolved the KDoc `QueueDispatcher` inherits from `CoroutineDispatcher` and failed `failOnWarning` on 45 unresolvable links; excluding only the file (`suppressedFiles`) left all 45.
- **typos:** `_typos.toml` allows Apple's identifier `CBAttributePermissionsWriteable`.
- **Unverified until ticket 17:** whether Core Bluetooth reports the discovered MESH service in its 32-bit or 128-bit form; both are accepted. A late callback for a cancelled connection may reach the next link to the same peripheral.
