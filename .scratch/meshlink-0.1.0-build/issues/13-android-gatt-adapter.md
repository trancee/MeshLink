# Scan, advertise and carry links over GATT on Android

Status: ready-for-agent
Type: AFK
Blocked by: 12

## Behavior

On Android, `MeshLink.create(config, platform)` with a `Context`-wrapping platform gets a real BLE `RadioPort`. It advertises the 16 B payload as specified, scans for the MESH UUID, serves the MeshLink GATT service as peripheral, and connects, reads the capability, subscribes and streams as central. Missing Bluetooth or Nearby Devices permissions show as `Blocked(requirements)` and clear by themselves. The adapter holds no protocol logic; the M1 core runs unchanged on top.

## Spec and ADR references

- [ADR 0009](../../../docs/adr/0009-ble-bearers-and-phy-policy.md) decisions 1 (every link starts on GATT), 8 (platform rules: paired `startScan`/`stopScan`, busy retries, `openGattServer` retries) and 9 (support tiers).
- [ADR 0010](../../../docs/adr/0010-discovery-and-advertising.md) decision 1 (legacy connectable advertising on 1M, two service UUIDs, no name or scan response).
- [ADR 0007](../../../docs/adr/0007-public-api-shape.md) decisions 1 (`platform` wraps a `Context`) and 3 (MeshLink never prompts; typed `Blocked` requirements).
- [ADR 0017](../../../docs/adr/0017-testing-architecture.md) decisions 1 (adapters hold no protocol logic) and 5 (Robolectric on SDK 26 and 37, own shadows).
- Spec: [advertisement](../../../docs/spec/discovery.md#advertisement), [GATT service](../../../docs/spec/discovery.md#gatt-service) (D-13 UUIDs and properties), [roles and bearers](../../../docs/spec/link.md#roles-and-bearers).
- Evidence: [bearer bake-off library rules](../../../docs/evidence/2026-11-bearer-bakeoff/library-rules.md); [lessons, device lab](../../../docs/agents/lessons.md#device-lab).

## Acceptance criteria

- [ ] An `androidMain` `RadioPort` implementation covers every operation ticket 08 defined, using `BluetoothLeAdvertiser`, `BluetoothLeScanner`, `BluetoothGattServer` and `BluetoothGatt`. It contains no frame parsing, tag logic or state machine beyond operation sequencing.
- [ ] Advertising: legacy, connectable, 1M, exactly the 32-bit MESH UUID `0x4D455348` and the 128-bit payload UUID in canonical byte order (D-12); no local name, no scan response.
- [ ] GATT server: one primary service with the 128-bit MESH UUID and the three D-13 characteristics (capability read without encryption, central-to-peripheral write without response, peripheral-to-central notify with a CCCD). Central order: read capability, enable notifications, then stream.
- [ ] The ATT payload size follows the negotiated MTU; notifications wait for `onNotificationSent` before the next. Every GATT operation is serialized and retries when the stack reports busy; `openGattServer` retries; every `startScan` has a matching `stopScan` on every path, including errors and cancellation.
- [ ] Requirements: missing `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE` or `BLUETOOTH_CONNECT` on API 31+, or the location permission the scan needs on API 26–30, maps to `PermissionDenied(kind)`; adapter off maps to `BluetoothOff`; no BLE peripheral support maps to `Unsupported`. A broadcast receiver clears `Blocked` when Bluetooth turns on. The manifest declares the permissions; MeshLink never requests them.
- [ ] Robolectric tests on SDK 26 and 37 cover every `androidMain` branch, with own shadows where Robolectric's fall short (GATT write status, failing registrations, busy). Kover stays at 100% for `androidMain`.
- [ ] Out of scope, with the owning ticket named in KDoc or the PR: connect retries on status 133 and 147 (ticket 29), PHY requests (ticket 32), scan restarts and screen-off filters (ticket 32), L2CAP CoC (ticket 31).
- [ ] `./gradlew check` passes; `./gradlew iosSimulatorArm64Test` stays green.

## Proof commands

```sh
./gradlew :meshlink:testAndroidHostTest --tests '*AndroidRadio*'
./gradlew check
./gradlew iosSimulatorArm64Test
```

## Docs to update

- `PROJECT.md`: proof-matrix row for the Android adapter (Robolectric, SDK 26 and 37; real radio proof deferred to ticket 17).
- `CHANGELOG.md`: Android radio entry.

## Devices needed

None. Ticket 17 proves this adapter on phones.
