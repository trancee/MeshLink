# Open an `Unsecured` link with `PLAIN_HELLO`

Status: ready-for-agent
Type: AFK
Blocked by: 10

## Behavior

For each link candidate, the node acting as GATT central reads the peer's capability record, selects the highest common protocol version and sends `PLAIN_HELLO`; the peripheral checks the version and mesh and answers with its own. Records stay in clear. Both nodes then list each other in `peers` as `Direct`, not secured. A peer on another mesh gets `CLOSE` with `WRONG_MESH`; two links to one neighbor collapse to one; `stop()` closes links with `SHUTTING_DOWN`.

## Spec and ADR references

- [ADR 0009](../../../docs/adr/0009-ble-bearers-and-phy-policy.md) decisions 1 (GATT first) and 4 (one link per neighbor).
- [ADR 0012](../../../docs/adr/0012-wire-format-and-codec.md) decisions 3 (link records), 6 (version selection) and 12 (unsecured).
- [ADR 0007](../../../docs/adr/0007-public-api-shape.md) decision 10 (`peers`).
- [ADR 0017](../../../docs/adr/0017-testing-architecture.md) decision 9 (mutation tests).
- Spec: [capability record](../../../docs/spec/discovery.md#capability-record), [roles and bearers](../../../docs/spec/link.md#roles-and-bearers), [link records](../../../docs/spec/link.md#link-records), [version selection](../../../docs/spec/link.md#version-selection), [unsecured link](../../../docs/spec/link.md#unsecured-link), [keep one link per neighbor](../../../docs/spec/link.md#keep-one-link-per-neighbor), [close](../../../docs/spec/link.md#close), [link-local frame summary](../../../docs/spec/link.md#link-local-frame-summary), [TLVs](../../../docs/spec/README.md#tlvs); decisions D-14, D-15, D-17, D-19, D-22.
- Vectors: `capability.json` (all 14), `records.json` (all 8), `link-frames.json` tcIds 5–7, 21–26, 54–57 and 64.

## Acceptance criteria

- [ ] The peripheral serves a capability record listing version 1 and the `ADVERTISEMENT` TLV with its current payload; it omits `L2CAP_PSM` (CoC is ticket 31). The codec enforces 1–8 strictly descending versions, the 512 B cap and the TLV rules; all `capability.json` cases pass.
- [ ] A link-record codec reads and writes `u16` length plus body over the ordered byte stream, independent of write boundaries. A length of 0 or above 1,024 is fatal: the node closes the bearer. All `records.json` cases pass.
- [ ] `PLAIN_HELLO` (67 B) and `CLOSE` (2 B) codecs pass the listed `link-frames.json` cases. An unknown close reason is treated as `NORMAL`. An unknown link-local frame type is dropped; types 0x00 and 0xC0–0xFF are rejected, as the frame-type registry says.
- [ ] Each decoder (capability record, link record, `PLAIN_HELLO`, `CLOSE`) has a deterministic mutation test (prefix lengths, over-long inputs, seeded mutations) and never throws.
- [ ] Version selection: no common version → the central closes the bearer; a hello carrying a version the peripheral does not speak → `CLOSE` `UNSUPPORTED_VERSION`.
- [ ] A hello with another `mesh_id_digest` → `CLOSE` `WRONG_MESH`, and the sender never appears in `peers`.
- [ ] Simultaneous links in both directions: both nodes keep the link whose central has the lower NodeId and send `DUPLICATE_LINK` on the other; frames queued on the closed link move to the kept one. Tested with a seeded race on `VirtualAir`.
- [ ] `peers` emits a `Peer` with the claimed NodeId, `Direct` reachability, `secured = false` and a last-seen time from the injected clock. `proximity` is left out here; [ticket 32](32-phy-advertising-scanning.md) owns it (EWMA smoothing and buckets).
- [ ] `stop()` sends `CLOSE` `SHUTTING_DOWN` on every link; the peer drops it from `peers` (`Unreachable` or removed, as the ticket decides and documents in KDoc).
- [ ] Multi-node tests in `:meshlink-testing` `commonTest` cover each case above; `./gradlew check` and `./gradlew iosSimulatorArm64Test` pass; Kover 100%.

## Proof commands

```sh
./gradlew :meshlink:testAndroidHostTest --tests '*Link*'
./gradlew :meshlink-testing:testAndroidHostTest --tests '*UnsecuredLink*'
./gradlew check
./gradlew iosSimulatorArm64Test
```

## Docs to update

- `CHANGELOG.md`: `peers` entry.
- `GLOSSARY.md`: "link record" and "capability record", if absent.

## Devices needed

None.
