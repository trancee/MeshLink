# iOS L2CAP CoC adapter and peripheral link cap

Status: ready-for-agent
Type: AFK
Blocked by: 31

## Behavior

Split from ticket 31 (Q1). The Core Bluetooth radio implements the CoC half of the `RadioPort` contract, so real iOS nodes take part in the ticket 31 bearer switch, and reports the ticket 29 peripheral link cap.

- **Listener:** `listenForChannels()` publishes an unencrypted L2CAP channel and returns its PSM; each opened `CBL2CAPChannel` becomes a `RadioEvent.ChannelOpened` stream whose `peer` is the `PeerHandle` of the GATT link from the same central. `stopListening()` unpublishes it.
- **Open:** `CentralLink.openChannel(psm)` opens an L2CAP channel on the link's peripheral; failures map to `LINK_CLOSED` or `RADIO_ERROR`.
- **Stream I/O:** the channel's input and output streams back `incoming`, `write` and `disconnect()`, with the same close rules as the GATT link.
- **Ticket 29 mapping:** `peripheralLinkCap` reports 3.

## Spec and ADR references

- [link.md, Bearer switch](../../../docs/spec/link.md#bearer-switch), [D-43](../../../docs/spec/decisions.md#d-43-bearer-switch), [D-49](../../../docs/spec/decisions.md#d-49-bearer-switch-timing-and-credits).
- [ADR 0009](../../../docs/adr/0009-ble-bearers-and-phy-policy.md) decisions 1, 4 and 6; [library rules](../../../docs/evidence/2026-11-bearer-bakeoff/library-rules.md) R1–R3, R7, R8, R12 and R16.

## Acceptance criteria

- [ ] Tests with Core Bluetooth fakes cover the listener, channel open success and failures, stream reads, writes, end and close, and the cap of 3; the adapters are listed in the CI-named `iosMain` file.
- [ ] `./gradlew check` passes with Kover at 100%, and `./gradlew iosSimulatorArm64Test` passes.

## Proof commands

```sh
./gradlew check
./gradlew iosSimulatorArm64Test
```

## Docs to update

- `CHANGELOG.md` and the iOS adapter KDoc.

## Devices needed

None. Ticket 33 proves CoC on phone pairs.
