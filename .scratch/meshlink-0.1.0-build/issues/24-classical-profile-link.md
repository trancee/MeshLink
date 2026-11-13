# Open `Classical` links with a certified handshake

Status: ready-for-agent
Type: AFK
Blocked by: 12, 21, 22

## Behavior

Two nodes configured with `SecurityProfile.Classical` discover each other on `VirtualAir` and open a secured link. Each sees the other as a peer with `secured = true`, and a direct message from ticket 12 travels encrypted.

- **Roles:** the central reads the capability record, then initiates. The pattern is `XX` in an open mesh and `XXpsk3` in a gated mesh.
- **psk:** `KDF(mesh_key, MeshIdDigest, "mesh-psk", "", 32)` (D-06).
- **Prologue:** `DS("link-prologue") ‖ u8(n) ‖ versions ‖ u8(selected_version) ‖ u8(profile) ‖ MeshIdDigest`, with `versions` from the peripheral's capability record (D-15).
- **`HANDSHAKE` frame (`0x02`):** version, step 1–3, and a Noise message of 1–4,096 B, in cleartext records. Before completion only `HANDSHAKE`, `FRAGMENT` carrying `HANDSHAKE`, and `CLOSE` are accepted.
- **Payload (D-16):** message 1 is empty. Messages 2 and 3 carry the `CERTIFICATE` TLV (`0x81`, critical). The `RESOLVING_KEY` TLV (`0x02`) is parsed and ignored here; ticket 27 sends and uses it. An open-mesh node ignores it.
- **After message 3:** each side validates the peer certificate (ticket 21) and checks that the Noise remote static key equals `x25519_key`. The peer is then known by the certificate's NodeId. Failure sends `CLOSE` `HANDSHAKE_FAILED`.
- **Records:** the initiator encrypts every record after message 3, and the responder every record after processing message 3.
- **Limits (D-20):** 4,096 B per Noise message, 1 handshake per bearer, 8 global, a 10 s timeout, and one new handshake per remote address per 2 s. Excess connections close without a reply.
- **Mesh tag:** gated meshes use the tag key `KDF(mesh_key, MeshIdDigest, "mesh-tag-key", "", 32)` (D-05).

Classical handshake messages fit in one cleartext record, so this ticket needs no `FRAGMENT` sending.

## Spec and ADR references

- [link.md: Patterns](../../../docs/spec/link.md#patterns), [Pre-shared key](../../../docs/spec/link.md#pre-shared-key), [Prologue](../../../docs/spec/link.md#prologue), [HANDSHAKE frame](../../../docs/spec/link.md#handshake-frame), [Handshake payload](../../../docs/spec/link.md#handshake-payload), [Handshake limits](../../../docs/spec/link.md#handshake-limits), [Close](../../../docs/spec/link.md#close).
- [discovery.md, Mesh tag](../../../docs/spec/discovery.md#mesh-tag) and [Capability record](../../../docs/spec/discovery.md#capability-record).
- [D-05](../../../docs/spec/decisions.md#d-05-mesh-tag-derivation), [D-06](../../../docs/spec/decisions.md#d-06-mesh-psk), [D-15](../../../docs/spec/decisions.md#d-15-handshake-roles-frame-and-prologue), [D-16](../../../docs/spec/decisions.md#d-16-link-handshake-payload), [D-20](../../../docs/spec/decisions.md#d-20-handshake-denial-of-service-bounds).
- [ADR 0001, Decision](../../../docs/adr/0001-threat-model-and-profile-guarantees.md#decision), [ADR 0002, Decision](../../../docs/adr/0002-noise-suites-and-handshake-patterns.md#decision), [ADR 0007](../../../docs/adr/0007-public-api-shape.md) (config, `SecurityProfile`, `Peer`), [ADR 0020](../../../docs/adr/0020-diagnostics-catalog.md) (`ML4001`, `ML4002`; the catalog lands in ticket 54).

## Acceptance criteria

- [ ] A public-API `VirtualAir` test brings up two `Classical` nodes in an open mesh and in a gated mesh, and exchanges a direct message both ways. A transcript check proves the message bytes on air are encrypted.
- [ ] The `HANDSHAKE` codec passes its `link-frames.json` tests, `handshake-payload.json` passes, and the `link-prologue`, `mesh-psk`, `mesh-tag-key` and `mesh-tag` derivations pass in the one runner. Each new decoder has a deterministic mutation test.
- [ ] Tier 2 `XX` and `XXpsk3` link transcripts with real certificates are added through the ticket 20 reference and pass in Kotlin.
- [ ] T10: a different profile, mesh ID or tampered capability versions (prologue), a wrong mesh key, a certificate whose `x25519_key` differs from the Noise static key, an invalid certificate, a missing `CERTIFICATE` TLV, an unknown critical TLV, a non-`HANDSHAKE` frame before completion, a wrong step and a version change mid-handshake each close the link with the spec's `CLOSE` reason and no peer appears.
- [ ] T10 (D-20): an over-long Noise message, a second handshake on one bearer, a ninth concurrent handshake, a 10 s stall, and a repeat within 2 s from one address are each dropped as specified, with a typed diagnostic event.
- [ ] An `Unsecured` node and a `Classical` node never link.
- [ ] `./gradlew check` passes with Kover at 100% and the ABI dump updated, and `./gradlew iosSimulatorArm64Test` passes.

## Proof commands

```sh
./gradlew check
./gradlew iosSimulatorArm64Test
python3 tools/spec/check_spec.py
```

## Docs to update

- [Security profile guarantees](../../../docs/reference/security-profile-guarantees.md): confirm the `Classical` row matches the behavior, or fix it in this PR.
- [Vectors README](../../../docs/spec/vectors/README.md): mark the link handshake and mesh psk tier 2 sets as covered.

## Devices needed

None. `VirtualAir` only. Ticket 26 proves the profiles on phones.
