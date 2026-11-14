# Open an `Unsecured` link with `PLAIN_HELLO`

Status: done
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

- [x] The peripheral serves a capability record listing version 1 and the `ADVERTISEMENT` TLV with its current payload; it omits `L2CAP_PSM` (CoC is ticket 31). The codec enforces 1–8 strictly descending versions, the 512 B cap and the TLV rules; all `capability.json` cases pass.
- [x] A link-record codec reads and writes `u16` length plus body over the ordered byte stream, independent of write boundaries. A length of 0 or above 1,024 is fatal: the node closes the bearer. All `records.json` cases pass.
- [x] `PLAIN_HELLO` (67 B) and `CLOSE` (2 B) codecs pass the listed `link-frames.json` cases. An unknown close reason is treated as `NORMAL`. An unknown link-local frame type is dropped; types 0x00 and 0xC0–0xFF are rejected, as the frame-type registry says.
- [x] Each decoder (capability record, link record, `PLAIN_HELLO`, `CLOSE`) has a deterministic mutation test (prefix lengths, over-long inputs, seeded mutations) and never throws.
- [x] Version selection: no common version → the central closes the bearer; a hello carrying a version the peripheral does not speak → `CLOSE` `UNSUPPORTED_VERSION`.
- [x] A hello with another `mesh_id_digest` → `CLOSE` `WRONG_MESH`, and the sender never appears in `peers`.
- [x] Simultaneous links in both directions: both nodes keep the link whose central has the lower NodeId and send `DUPLICATE_LINK` on the other; frames queued on the closed link moving to the kept one moved to [ticket 12](12-direct-message-tracer.md), which adds the first outgoing queue. Tested with a seeded race on `VirtualAir`.
- [x] `peers` emits a `Peer` with the claimed NodeId, `Direct` reachability, `secured = false` and a last-seen time from the injected clock. `proximity` is left out here; [ticket 32](32-phy-advertising-scanning.md) owns it (EWMA smoothing and buckets).
- [x] `stop()` sends `CLOSE` `SHUTTING_DOWN` on every link; the peer drops it from `peers` (removed, documented in the `peers` KDoc and the ADR 0007 amendment).
- [x] Multi-node tests in `:meshlink-testing` `commonTest` cover each case above; `./gradlew check` and `./gradlew iosSimulatorArm64Test` pass; Kover 100%.

## Proof commands

```sh
./gradlew :meshlink-core:testAndroidHostTest --tests '*Capability*' --tests '*LinkRecord*' --tests '*LinkFrame*' --tests '*LinkPolicy*'
./gradlew :meshlink:testAndroidHostTest --tests '*NodeId*'
./gradlew :meshlink-testing:testAndroidHostTest --tests '*UnsecuredLink*' --tests '*Discovery*'
./gradlew check iosSimulatorArm64Test dokkaGenerate --rerun-tasks
tools/swiftpm-consumer/test.sh
```

## Docs to update

- `CHANGELOG.md`: `peers` entry.
- `GLOSSARY.md`: "link record" and "capability record", if absent.

## Devices needed

None.

## Notes

- Owner decisions (2026-11-14): a peer leaves `peers` when its last link closes, and `Reachability` holds only `Direct`; `lastSeen` is refreshed when the link opens and on every received frame; the 10 s handshake timeout also bounds the hello exchange (spec D-44); `NodeId` is a regular class with hex `toString()` and `toByteArray()`. Recorded in the ADR 0007 amendment of 2026-11-14 ("peers").
- The peripheral echoes the selected version and the central closes with `PROTOCOL_ERROR` on a mismatch (spec D-44).
- A link that closes on cancellation still disconnects its bearer: the `CLOSE` write and the disconnect sit in `try`/`finally`.
- Kotlin copies a `finally` block into both the normal and the exceptional exit, so a branch inside it needs both exits covered. Bookkeeping in a `finally` calls one function instead.
- Owner decision: `:meshlink-testing` test configurations may depend on `:meshlink-crypto-sodium`, so fake peers derive real digests, NodeIds and advertisements; main configurations keep the allow-list (ADR 0011 amendment "test-only edge").
- `CloseReason.code` is the entry's ordinal: the registry's codes run from 0x00 without gaps, and detekt's `MagicNumber` counts enum arguments.
- One forced full run failed once in `:meshlink-crypto-sodium:compileIosMainKotlinMetadata` with unresolved `cinterop` references; the task passed alone and in the next two forced full runs.
