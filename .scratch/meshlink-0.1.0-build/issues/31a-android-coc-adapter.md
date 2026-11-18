# Android L2CAP CoC adapter and GATT failure mapping

Status: ready-for-agent
Type: AFK
Blocked by: 31

## Behavior

Split from ticket 31 (Q1). The Android radio implements the CoC half of the `RadioPort` contract, so real Android nodes take part in the ticket 31 bearer switch, and maps the ticket 29 GATT failure contract.

- **Listener:** on Android 10+ `listenForChannels()` opens an insecure LE CoC server socket and returns its PSM; each accepted socket becomes a `RadioEvent.ChannelOpened` stream whose `peer` is the `PeerHandle` of the GATT link from the same device. `stopListening()` closes the server socket. Android 8 and 9 return `UNSUPPORTED` and never set `COC_AVAILABLE` or send `L2CAP_PSM`.
- **Open:** `CentralLink.openChannel(psm)` connects an insecure LE CoC socket to the link's device. Failures map to `RadioFailure`: a closed link to `LINK_CLOSED`, a refused or failed connect to `RADIO_ERROR`.
- **Stream I/O:** a reader coroutine turns socket reads into `incoming` chunks; `write` writes whole and reports `LINK_CLOSED` once the socket is closed; `disconnect()` closes the socket once. EOF or an I/O error ends `incoming`.
- **Ticket 29 mapping:** GATT status 133 and 147 and the silent close map to transient disconnects (`RadioEvent.Disconnected(transient = true)`); the GATT notification path honors the `CREDIT` window it is given.

## Spec and ADR references

- [link.md, Bearer switch](../../../docs/spec/link.md#bearer-switch), [D-43](../../../docs/spec/decisions.md#d-43-bearer-switch), [D-49](../../../docs/spec/decisions.md#d-49-bearer-switch-timing-and-credits).
- [ADR 0009](../../../docs/adr/0009-ble-bearers-and-phy-policy.md) decisions 1, 4 and 6; [library rules](../../../docs/evidence/2026-11-bearer-bakeoff/library-rules.md) R1–R3, R7, R8, R12 and R16.
- [ADR 0017](../../../docs/adr/0017-testing-architecture.md) decision 3 (Robolectric).

## Acceptance criteria

- [ ] Robolectric tests cover the listener (PSM, accept, stop), channel open success and each failure, stream reads, writes, EOF and close, and the 133/147/silent-close mapping.
- [ ] Robolectric tests on API 28 show no listener, no `COC_AVAILABLE` and no `L2CAP_PSM`.
- [ ] `./gradlew check` passes with Kover at 100%.

## Proof commands

```sh
./gradlew check
```

## Docs to update

- `CHANGELOG.md` and the Android adapter KDoc.

## Devices needed

None. Ticket 33 proves CoC on phone pairs.
