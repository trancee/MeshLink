# Discovery

Status: Draft. Part of [MeshLink protocol 1](README.md).

## Mesh identifier

(ADR 0010 decision 1; [D-03].)

Apps name a mesh with a mesh identifier: a UTF-8 string of 1 to 64 bytes, compared byte for byte (no normalization). The profile is part of the mesh: the same identifier with another profile is another mesh.

```text
MeshIdDigest = H("mesh-id", u16(len(mesh_id)) ‖ mesh_id ‖ u8(profile))      32 B, unkeyed
```

A gated mesh also has a 32 B random mesh key, distributed out of band.

<!-- data: limits.json -->

| Name | Value | Unit | Decision |
|---|---|---|---|
| mesh-id-min-length | 1 | B | D-03 |
| mesh-id-max-length | 64 | B | D-03 |

## Advertisement

(ADR 0010 decision 1 and amendment; ADR 0012 decision 10; [D-12].)

A node advertises legacy, connectable, on 1M, with exactly two service UUIDs and nothing else:

- the fixed MESH UUID, in its 32-bit form `0x4D455348`;
- one rotating 128-bit service UUID whose 16 bytes are the advertisement payload below.

The 16 payload bytes map to the UUID in canonical string order: byte 0 is the first two hex digits of the UUID string. Implementations MUST convert from their platform's UUID type accordingly (for example, Android `ParcelUuid` and iOS `CBUUID` data both use this order) [D-12].

<!-- data: constants.json -->

| Name | Value | Decision |
|---|---|---|
| mesh-service-uuid16 | 0x4D455348 | - |
| mesh-service-uuid | 4D455348-0000-1000-8000-00805F9B34FB | - |

### Payload layout

| Offset | Size | Name | Encoding | Notes |
|---|---|---|---|---|
| 0 | 1 | layout_and_flags | bits | Bits 7–5: layout version, `001`. Bits 4–0: flags |
| 1 | 4 | mesh_tag | `bytes[4]` | See below |
| 5 | 8 | resolvable_tag | `bytes[8]` | Gated: see below. Open and `unsecured`: random per epoch |
| 13 | 3 | random | `bytes[3]` | Fresh random per epoch |

Total: 16 B, as ADR 0012 decision 10 fixes. Bytes 5–12 are also the node's 8 B advertisement identifier, used in neighbor lists and shuffles ([link.md](link.md#neighbor-list)).

<!-- data: limits.json -->

| Name | Value | Unit | Decision |
|---|---|---|---|
| advertisement-length | 16 | B | - |
| advertisement-layout-version | 1 | - | D-12 |

Flag bits (bit 0 is the least significant bit of byte 0):

<!-- data: codes.json registry=advertisement-flag -->

| Code | Name | Decision |
|---|---|---|
| 0x00 | COC_AVAILABLE | D-12 |
| 0x01 | PHY_2M | D-12 |

- `COC_AVAILABLE`: the node can accept an L2CAP CoC upgrade (ADR 0009 decision 1). `PHY_2M`: the node supports the 2M PHY (ADR 0009 decision 3). Both are hints for peer choice; the capability record is authoritative.
- Bits 2–4 are reserved: sent as zero, ignored on receipt.
- A scanner MUST ignore an advertisement whose layout version is not `001` or whose rotating UUID is not 16 B.
- All 16 bytes change together at each rotation (README, "Epochs"). On Android each epoch starts a new advertising set (ADR 0010 decision 3). Before each rotation a node tells its linked peers its next identifier in `NEIGHBOR_LIST` ([link.md](link.md#neighbor-list)).

Vectors: `advertisement.json`.

### Mesh tag

(ADR 0010 decision 1; [D-05].)

```text
tag_key  = MeshIdDigest                                              open meshes and unsecured
tag_key  = KDF(mesh_key, MeshIdDigest, "mesh-tag-key", "", 32)       gated meshes
mesh_tag = trunc_4(H("mesh-tag", u32(epoch); key = tag_key))
```

A scanner computes the mesh tags for epochs e − 1, e and e + 1 and ignores advertisements that match none of them. Because each node rotates at its own phase, members show tags of two adjacent epochs at any time (see [conflict C-03](decisions.md#c-03)).

Vectors: `derivations.json`, derivations `mesh-id-digest`, `mesh-tag-key`, `mesh-tag`.

### Resolvable tag

(ADR 0010 decision 4; [D-07].)

In a gated mesh each node holds a 32 B random resolving key, replaced daily. It gives the key to a peer only inside the authenticated link handshake ([link.md](link.md#handshake-payload)) and sends the next key over the link before rotating ([link.md](link.md#resolving-key-update)).

```text
resolvable_tag = trunc_8(H("resolvable-tag", u32(epoch); key = resolving_key))
```

Peers precompute the tags for epochs e − 1, e and e + 1 of every resolving key they hold, so a sighting resolves by table lookup. A peer deletes a resolving key at its `not_after`. Open meshes and `unsecured` put 8 random bytes here, fresh each epoch.

<!-- data: limits.json -->

| Name | Value | Unit | Decision |
|---|---|---|---|
| resolving-key-lifetime | 86,400 | s | - |
| resolving-key-lead | 3,600 | s | D-41 |

Vectors: `derivations.json`, derivation `resolvable-tag`.

## GATT service

(ADR 0009 decision 1; ADR 0010 decision 5; [D-13].)

Every link starts on GATT. The peripheral exposes one primary service with the 128-bit MESH UUID and three characteristics:

<!-- data: constants.json -->

| Name | Value | Decision |
|---|---|---|
| capability-characteristic-uuid | 4D455348-4D4C-4E4B-8001-000000000001 | D-13 |
| central-to-peripheral-characteristic-uuid | 4D455348-4D4C-4E4B-8001-000000000002 | D-13 |
| peripheral-to-central-characteristic-uuid | 4D455348-4D4C-4E4B-8001-000000000003 | D-13 |

- **Capability** (read): the capability record below. Readable without encryption or bonding.
- **Central to peripheral** (write without response): the central's link byte stream ([link.md](link.md#link-records)).
- **Peripheral to central** (notify, with a Client Characteristic Configuration descriptor): the peripheral's link byte stream.

The central reads the capability record first, then enables notifications, then starts the link handshake.

## Capability record

(ADR 0009 decision 1; ADR 0010 decision 5; ADR 0012 decisions 6 and 7; ADR 0019 decision 3; [D-14].)

| Offset | Size | Name | Encoding | Notes |
|---|---|---|---|---|
| 0 | 1 | version_count | `u8` | 1 to 8 |
| 1 | var | versions | `u8` each | Protocol versions spoken, strictly descending, each ≥ 1 |
| 1 + n | var | extensions | TLVs | Capability TLVs, to the end of the record |

The whole record is at most 512 B, the largest ATT attribute value.

<!-- data: limits.json -->

| Name | Value | Unit | Decision |
|---|---|---|---|
| versions-max | 8 | versions | D-14 |
| capability-record-max | 512 | B | D-14 |

<!-- data: tlv-types.json context=capability -->

| Code | Name | Critical | Length |
|---|---|---|---|
| 0x01 | L2CAP_PSM | no | 2 |
| 0x02 | ADVERTISEMENT | no | 16 |

- `L2CAP_PSM`: `u16` PSM of the peripheral's L2CAP CoC listener. Present only when the peripheral accepts CoC.
- `ADVERTISEMENT`: the peripheral's current 16 B advertisement payload. A backgrounded iPhone advertises no rotating UUID, so a central reads its identifier here (ADR 0010 decision 5). A peripheral SHOULD always include it.
- The central selects the highest version present in both its own list and the record. It sends that version in its first `HANDSHAKE` or `PLAIN_HELLO` frame ([link.md](link.md#version-selection)).

Vectors: `capability.json`.

## Informative: scanning and peer choice

Scan schedules, the density estimator, the bridge rule and the stale-address rule are local choices (ADR 0008 decisions 1 to 3 and amendments; ADR 0010 decision 6). They do not affect interoperability.
