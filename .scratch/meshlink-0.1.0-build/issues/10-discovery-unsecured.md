# Discover an `Unsecured` peer on `VirtualAir`

Status: ready-for-agent
Type: AFK
Blocked by: 09

## Behavior

A running `Unsecured` node advertises the 16 B layout `001` payload with the MESH service UUID and scans for others. It keeps sightings whose mesh tag matches its own mesh for epoch e − 1, e or e + 1 and ignores every other advertisement. Each kept sighting becomes a link candidate for ticket 11. Two nodes on `VirtualAir` with the same mesh ID find each other; a node with another mesh ID or profile is ignored.

## Spec and ADR references

- [ADR 0010](../../../docs/adr/0010-discovery-and-advertising.md) decision 1 (layout and mesh tag) and its amendment (layout E); decision 4 (resolvable tag; random in `unsecured`).
- [ADR 0012](../../../docs/adr/0012-wire-format-and-codec.md) decision 10 (16 B advertisement).
- [ADR 0017](../../../docs/adr/0017-testing-architecture.md) decision 9 (mutation test for every decoder).
- Spec: [mesh identifier](../../../docs/spec/discovery.md#mesh-identifier), [advertisement](../../../docs/spec/discovery.md#advertisement), [payload layout](../../../docs/spec/discovery.md#payload-layout), [mesh tag](../../../docs/spec/discovery.md#mesh-tag), [resolvable tag](../../../docs/spec/discovery.md#resolvable-tag), [epochs](../../../docs/spec/README.md#epochs), [security profiles](../../../docs/spec/README.md#security-profiles); decisions D-03, D-05, D-07, D-12.
- Vectors: `docs/spec/vectors/advertisement.json`; `derivations.json` `mesh-tag`.

## Acceptance criteria

- [ ] An advertisement codec encodes and decodes the 16 B payload: byte 0 holds layout `001` in bits 7–5 and flags in bits 4–0; then a 4 B mesh tag, 8 B resolvable tag and 3 B random. The bytes map to the 128-bit UUID in canonical string order (D-12).
- [ ] In `Unsecured`, `tag_key` is the MeshIdDigest with profile code 0x00. The resolvable tag and the 3 random bytes come from the injected random source, fresh for the epoch in which the advertisement is built. Flags `COC_AVAILABLE` and `PHY_2M` are sent as zero; the reserved bits are zero.
- [ ] The decoder rejects a layout version other than `001` and a UUID that is not 16 B; it ignores reserved flag bits. All 10 `advertisement.json` cases pass on the Android host and `iosSimulatorArm64` through the shared vector runner.
- [ ] A deterministic mutation test of the decoder covers every prefix length, over-long inputs and a fixed number of seeded mutations; the decoder never throws.
- [ ] The scanner computes the mesh tags for epochs e − 1, e and e + 1 and drops every sighting matching none. Tests: a matching tag at e − 1 and e + 1 is kept; e − 2 is dropped; another mesh ID is dropped; the same mesh ID with another profile byte is dropped.
- [ ] A node never treats its own advertisement as a candidate.
- [ ] Multi-node test in `:meshlink-testing` `commonTest`: two started `Unsecured` nodes with the same mesh ID each record the other as a candidate within the first scan window in virtual time; a third node on another mesh ID is in no candidate list.
- [ ] Rotation inside a running node (new payload each epoch, `NEIGHBOR_LIST` notice) is out of scope; ticket 27 owns it. `peers` stays empty until ticket 11, because a `Peer` is keyed by a NodeId learned in the hello.
- [ ] `./gradlew check` and `./gradlew iosSimulatorArm64Test` pass; Kover 100%.

## Proof commands

```sh
./gradlew :meshlink:testAndroidHostTest --tests '*Advertisement*'
./gradlew :meshlink-testing:testAndroidHostTest --tests '*Discovery*'
./gradlew check
./gradlew iosSimulatorArm64Test
```

## Docs to update

- `GLOSSARY.md`: "mesh tag" and "advertisement identifier", if absent.
- `PROJECT.md`: proof-matrix row for the advertisement vectors.

## Devices needed

None.
