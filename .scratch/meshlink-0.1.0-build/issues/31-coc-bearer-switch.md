# Upgrade links to L2CAP CoC with GATT fallback

Status: ready-for-agent
Type: AFK
Blocked by: 17, 29

## Behavior

Every link still starts on GATT. When both phones support it, the link moves to an unbonded L2CAP CoC channel with `BEARER_SWITCH`. Otherwise it stays on GATT.

- **Who supports CoC:** Android 10+ and iOS. Android 8 and 9 never open or offer CoC. The node sets the `COC_AVAILABLE` advertisement flag and the `L2CAP_PSM` capability TLV only while its CoC listener is up. The capability record is authoritative, and the flag is a hint.
- **Security:** unbonded (insecure) CoC only. MeshLink's own encryption protects the data in the secured profiles, and `Unsecured` gets no bearer protection.
- **Switch (D-43):** the central opens the channel to the peer's PSM, then sends `BEARER_SWITCH` (`0x0F`) as its last GATT record. The peripheral, after receiving it, sends `BEARER_SWITCH` as its last GATT record. Each side continues its record stream on CoC with the next record, and cipher states and nonces carry over. CoC bytes received before the peer's switch are buffered. GATT bytes after the peer's switch are discarded.
- **Fallback:** if the channel fails to open, the link stays on GATT with a typed diagnostic event. If the channel closes later, the link ends, and a GATT reconnect is a new link with a fresh handshake. Routing state stays keyed by NodeId (C-07).
- **Config:** an internal config value turns CoC off; it is on by default. The public toggle comes in ticket 53. Per the ADR 0011 toggle rule, it only stops this node from offering or opening CoC. It never stops parsing `BEARER_SWITCH` or the PSM TLV.
- **Adapters:** the Android and iOS adapters gain the CoC listener, the channel open, and the stream I/O. They also map the ticket 29 `RadioPort` contract: GATT status 133 and 147 and the silent close to transient causes, the iOS peripheral cap of 3, and the GATT notification send path that the ticket 29 `CREDIT` window gates. CoC traffic is not credited; CoC has its own credits.

## Spec and ADR references

- [link.md, Bearer switch](../../../docs/spec/link.md#bearer-switch) and [Rekey](../../../docs/spec/link.md#rekey); [discovery.md, Payload layout](../../../docs/spec/discovery.md#payload-layout) and [Capability record](../../../docs/spec/discovery.md#capability-record).
- [D-43](../../../docs/spec/decisions.md#d-43-bearer-switch), [D-14](../../../docs/spec/decisions.md#d-14-capability-record), [C-07](../../../docs/spec/decisions.md#c-07).
- [ADR 0009](../../../docs/adr/0009-ble-bearers-and-phy-policy.md) decisions 1, 2, 4, 6, 7, 9 and the 2026-11-08 amendment; [ADR 0011](../../../docs/adr/0011-modules-toggles-and-presets.md) decisions 8 and 10.
- [Library rules](../../../docs/evidence/2026-11-bearer-bakeoff/library-rules.md) R1–R3, R7, R8, R12 and R16.
- [ADR 0017](../../../docs/adr/0017-testing-architecture.md) decisions 3, 9 and 10; [ADR 0020](../../../docs/adr/0020-diagnostics-catalog.md) (`ML3005`; the catalog lands in ticket 54).

## Acceptance criteria

- [ ] The `BEARER_SWITCH` cases in `link-frames.json` and the `L2CAP_PSM` and flag cases in `capability.json` and `advertisement.json` pass in the one runner. The new decoder has a deterministic mutation test.
- [ ] `VirtualAir` models a CoC channel. A seeded test in each profile switches a link, keeps sending direct messages without loss or reordering, and rekeys after the switch.
- [ ] A tier 2 vector set from the ticket 20 reference shows nonce continuity across `BEARER_SWITCH`, and Kotlin passes it.
- [ ] Seeded tests show: a channel that fails to open leaves the link on GATT; a channel lost later ends the link and a new GATT link runs a fresh handshake; GATT bytes after the switch are discarded; early CoC bytes are buffered.
- [ ] T10: a `BEARER_SWITCH` from the peripheral first, a second `BEARER_SWITCH`, a switch with no CoC channel open, and a switch before handshake completion each close the bearer or fail the handshake as the spec requires, with no cipher state reused on a new link.
- [ ] Android 8 and 9 and CoC-off nodes never set `COC_AVAILABLE` or send `L2CAP_PSM`, but still link over GATT with CoC nodes.
- [ ] Robolectric tests cover the Android CoC listener, channel open, stream I/O and the 133/147 mapping. iOS tests with fakes cover the same paths and the cap of 3, and the adapters are listed in the CI-named `iosMain` file.
- [ ] `./gradlew check` passes with Kover at 100%, and `./gradlew iosSimulatorArm64Test` passes.

## Proof commands

```sh
./gradlew check
./gradlew iosSimulatorArm64Test
cargo test --manifest-path tools/reference/Cargo.toml
```

## Docs to update

- [Vectors README](../../../docs/spec/vectors/README.md): mark the `BEARER_SWITCH` continuity cases as published.

## Devices needed

None for this ticket. Ticket 33 proves CoC on phone pairs.
