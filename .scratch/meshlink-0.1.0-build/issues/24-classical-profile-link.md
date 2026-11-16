# Open `Classical` links with a certified handshake

Status: done
Type: AFK
Blocked by: 12, 21, 22

## Behavior

Two nodes configured with `SecurityProfile.Classical` discover each other on `VirtualAir` and open a secured link. Each sees the other as a peer with `secured = true`, and every link record after the handshake is encrypted.

Owner decision (2026-11-16): the spec forbids the unsecured message container in a secured mesh, and the `Classical` containers come with ticket 45. Until then `send()` in `Classical` is accepted but stays `Queued`, and received routed frames are dropped. The node takes an optional 32-byte `meshKey` in its config (`null` is an open mesh), which `describe()` redacts and `Unsecured` rejects.

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

- [x] A public-API `VirtualAir` test brings up two `Classical` nodes in an open mesh and in a gated mesh, and both list each other as secured peers. A transcript check proves the link records after message 3 are encrypted. A `send()` stays `Queued` until ticket 45 (owner decision above).
- [x] The `HANDSHAKE` codec passes its `link-frames.json` tests, `handshake-payload.json` passes, and the `link-prologue`, `mesh-psk`, `mesh-tag-key` and `mesh-tag` derivations pass in the one runner. Each new decoder has a deterministic mutation test.
- [x] Tier 2 `XX` and `XXpsk3` link transcripts with real certificates are added through the ticket 20 reference and pass in Kotlin.
- [x] T10: a different profile, mesh ID or tampered capability versions (prologue), a wrong mesh key, a certificate whose `x25519_key` differs from the Noise static key, an invalid certificate, a missing `CERTIFICATE` TLV, an unknown critical TLV, a non-`HANDSHAKE` frame before completion, a wrong step and a version change mid-handshake each close the link with the spec's `CLOSE` reason and no peer appears.
- [x] T10 (D-20): an over-long Noise message (moved to ticket 28, see Outcome), a second handshake on one bearer, a ninth concurrent handshake, a 10 s stall, and a repeat within 2 s from one address are each dropped as specified, with a typed diagnostic event.
- [x] An `Unsecured` node and a `Classical` node never link.
- [x] `./gradlew check` passes with Kover at 100% and the ABI dump updated, and `./gradlew iosSimulatorArm64Test` passes.

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

## Outcome

- **API:** `SecurityProfile.Classical` and the builder's `meshKey: ByteArray?` (exactly 32 B, copied, `<redacted>` in `describe()`, `InvalidConfig("meshKey", …)` with `Unsecured`). A gated mesh tags its advertisements with the mesh tag key, so nodes with different mesh keys never connect. `PostQuantum` follows in ticket 25.
- **Diagnostics:** `LinkDiagnostic.HandshakeFailed(reason)` (`ML4001`; `TIMEOUT`, `DECRYPT`, `CERTIFICATE`, `PROTOCOL`) and `HandshakeLimitDropped(limit)` (`ML4002`; `PER_BEARER`, `GLOBAL`, `NEIGHBOR_INTERVAL`) on `MeshLink.linkDiagnostics`, `@InternalMeshLinkApi` until ticket 54 makes them public with the same codes.
- **Close reasons:** every handshake failure closes with `HANDSHAKE_FAILED`, in clear before completion and encrypted after it, so a certificate failure's `CLOSE` is encrypted. A D-20 drop and a record that fails to decrypt close without a reply. A `HANDSHAKE` or `PLAIN_HELLO` on an open link closes with `PROTOCOL_ERROR`, as does a `HANDSHAKE` sent to an `Unsecured` node.
- **Prologue, profile, mesh ID and mesh key:** all surface as `DECRYPT`. In `XXpsk3`, message 1's payload is already encrypted with the prologue hash as associated data, so a responder rejects a prologue mismatch at message 1; a wrong psk fails at message 3. The responder's message 2 also fails on an all-zero initiator ephemeral (`ee`).
- **Over-long Noise message:** one record holds at most 1,024 B, so a `HANDSHAKE` above 4,096 B can only arrive through `FRAGMENT` reassembly. The `HANDSHAKE` decoder already rejects it, which fails the handshake as spec "Handshake limits" says; ticket 28 adds the T10 case.
- **Timeout:** the 10 s timer starts when the bearer opens, which is stricter than starting at message 1.
- **Tests:** `ClassicalLinkTest` (16 tests) drives a scripted Noise peer as initiator and as responder. With the handshake unwired, 14 of them fail.
- **Security profile guarantees:** the `classical` column describes the finished profile. This ticket delivers peer authentication, gated admission and link forward secrecy; message confidentiality comes with ticket 45. The table is unchanged.
