# MeshLink protocol 1

Status: **Draft**. Protocol version byte `0x01`.

This is the normative specification of the MeshLink wire protocol, version 1. It defines everything two implementations must agree on to interoperate: bytes, derivations, cryptographic constructions, state machines, peer-visible timers and limits. Purely local choices (neighbor selection, density estimation, scan schedules, presets, the API) are described only briefly, as informative text that links to the ADRs.

## Documents

| Document | Contents |
|---|---|
| [identity.md](identity.md) | NodeId, identity certificate, static-key rotation |
| [discovery.md](discovery.md) | Advertisement payload, mesh and resolvable tags, GATT service, capability record |
| [link.md](link.md) | Link records, link handshakes, fragments, link-local frames, rekey, catch-up |
| [routing.md](routing.md) | Routing headers, unicast and flood forwarding, route discovery, Plumtree, certificate lookups |
| [end-to-end.md](end-to-end.md) | `IK` and `IKhfs` sessions, HPKE X-Wing seal, plaintext and padding, conversation tags, receipts, tombstones |
| [decisions.md](decisions.md) | Decision log: every value or layout no ADR fixed, and every ADR conflict resolved |
| [data/](data/) | Authoritative JSON data: limits, codes, frame types, TLV types, domain strings, constants |
| [vectors/README.md](vectors/README.md) | Test vectors: tier 1 (published) and tier 2 (pending) |

## Status and change control

Protocol 1 is a draft. Until library 0.1.0 it may change freely. From library 0.1.0 on, Constitution rules C6 and C7 apply: a wire change needs a version decision, compatibility tests and new vectors (ADR 0019 decision 2).

Values marked `[D-nn]` are decisions that no ADR fixed. Each one is listed in the [decision log](decisions.md) with its evidence and alternatives; the owner accepted all of them on 2026-11-08.

## Requirements language

The key words "MUST", "MUST NOT", "REQUIRED", "SHALL", "SHALL NOT", "SHOULD", "SHOULD NOT", "RECOMMENDED", "NOT RECOMMENDED", "MAY", and "OPTIONAL" in this specification are to be interpreted as described in BCP 14 (RFC 2119, RFC 8174) when, and only when, they appear in all capitals, as shown here.

## Authority

- The JSON files in [data/](data/) are authoritative for every number, code and string. Markdown tables repeat them for readers. A table preceded by a `data:` comment is compared with the data file by `tools/spec/check_spec.py`.
- The accepted ADRs in [`docs/adr/`](../adr/) are the source of each decision. Each section cites them, for example "(ADR 0012 decision 4)". Amendments override the original ADR text.
- If this specification and an ADR disagree, the conflict is a bug: it is logged in [decisions.md](decisions.md#conflicts-resolved).

## Conventions

(ADR 0012 decisions 1 and 2.)

- All fields are byte-aligned. Integers are unsigned, fixed width and big-endian: `u8`, `u16`, `u32`, `u64`. There are no varints.
- Only byte 0 of the advertisement payload is bit-packed.
- `bytes[n]` is exactly n bytes. `bytes[u16]` is a `u16` length followed by that many bytes; `bytes[u32]` likewise with a `u32` length.
- A list is a count (`u8` or `u16`, as stated) followed by that many fixed-size entries.
- `rest` means every remaining byte of the enclosing structure.
- `‖` is concatenation. `trunc_n(x)` is the first n bytes of x.
- Offsets and sizes in layout tables are in bytes. A size written `var` depends on a length or count field.
- A decoder MUST reject a structure that is truncated, that has bytes left over after its last field, or whose length or count field is outside the stated range. Error names used in the vectors are listed in [vectors/README.md](vectors/README.md#error-codes).
- Times on the wire are `u32` Unix seconds (ADR 0012 decision 5). They are valid until 2106.
- A flag bit or field marked reserved MUST be sent as zero and MUST be ignored on receipt, unless the text says otherwise.

## TLVs

(ADR 0012 decision 7.)

TLVs extend the capability record, the link handshake payload and the end-to-end plaintext. Frames have no extension area.

| Offset | Size | Name | Encoding | Notes |
|---|---|---|---|---|
| 0 | 1 | type | `u8` | Bit 7 set means critical |
| 1 | 2 | length | `u16` | Length of value |
| 3 | var | value | `bytes` | |

- TLVs in one sequence MUST appear in strictly ascending type order. A repeated type is an error (`tlv-order`).
- An unknown TLV with bit 7 clear MUST be ignored. An unknown TLV with bit 7 set MUST fail the enclosing structure (`unknown-critical-tlv`); in a handshake this fails the handshake.
- A known TLV whose length differs from its registered fixed length is an error (`tlv-length`).
- TLV types are scoped by context (capability, handshake, end-to-end). The registry is in [data/tlv-types.json](data/tlv-types.json).

## Derivation rules

(ADR 0004 decision; ADR 0012 decision 8; [D-01].)

Every derivation is bound to a registered ASCII domain string of the form `MeshLink/1 <purpose>`. The encoded domain string is

```text
DS(purpose) = u8(len(s)) ‖ s        where s = "MeshLink/1 " ‖ purpose, ASCII, 1..255 bytes
```

Two primitives use it, each in exactly one way:

- **Hash** `H(purpose, inputs; key)`: libsodium `crypto_generichash` (BLAKE2b) with an output length of 32 B, over `DS(purpose) ‖ inputs`. When the derivation is keyed, the key is passed as the BLAKE2b key (32 B in every MeshLink use); otherwise the key is empty. Shorter identifiers are `trunc_n` of the 32 B output. Implementations MUST NOT request a shorter BLAKE2b output instead, because BLAKE2b binds the output length into its parameter block.
- **Key derivation** `KDF(ikm, salt, purpose, context, L)`: HKDF-SHA-256 (RFC 5869) with `info = DS(purpose) ‖ context`. An empty salt means 32 zero bytes, as in RFC 5869. L is 32 unless stated.

Inputs are concatenated in the order given. Every input has a fixed length except where a `u16` length prefix is shown. Epoch numbers are encoded as `u32`.

## Epochs

(ADR 0010 decision 3; ADR 0008 amendment "Rotation"; [D-02].)

The epoch number is `floor(unix_seconds / 300)`, encoded as `u32` when hashed. Each node rotates its advertisement and routing tags once per epoch at its own random phase: at a rotation instant, it switches to the epoch number current at that instant. A node's tags therefore lag the global epoch by less than one epoch. Receivers MUST accept tags for the current epoch number e and for e − 1 and e + 1.

<!-- data: limits.json -->

| Name | Value | Unit | Decision |
|---|---|---|---|
| epoch-length | 300 | s | D-02 |
| epoch-acceptance | 1 | epochs | D-02 |
| clock-skew | 30 | s | - |

## Security profiles

(ADR 0001 decision; [D-04].)

A mesh has exactly one profile, never negotiated. The code appears in the certificate, the mesh identifier digest and the link prologue.

<!-- data: codes.json registry=profile -->

| Code | Name | Decision |
|---|---|---|
| 0x00 | unsecured | D-04 |
| 0x01 | classical | D-04 |
| 0x02 | post-quantum | D-04 |

## Versioning

(ADR 0012 decision 6 and amendment; ADR 0019 decisions 2 and 3.)

<!-- data: constants.json -->

| Name | Value | Decision |
|---|---|---|
| protocol-version | 0x01 | - |

- The version byte is exchanged in the capability record ([discovery.md](discovery.md#capability-record)) and bound into the Noise prologue ([link.md](link.md#prologue)). Frames carry no version.
- Both sides select the highest version they both list. A node that shares no version with its peer MUST close the link with reason `UNSUPPORTED_VERSION`.
- A compatible addition uses a new frame type or a new non-critical TLV and keeps the version byte. An incompatible change gets a new version byte.

## Frame-type registry

(ADR 0012 decision 7; [D-18].)

Every frame starts with a 1 B type. The type's range fixes its class, so a relay can handle a type it does not know:

| Range | Class | Unknown type |
|---|---|---|
| `0x00` | reserved | Reject (`reserved-type`) |
| `0x01`–`0x3F` | link-local | Drop with a diagnostic (`unknown-type`) |
| `0x40`–`0x7F` | routed unicast | Relay by its unicast header; never deliver |
| `0x80`–`0xBF` | routed flood | Relay by its flood header; never deliver |
| `0xC0`–`0xFE` | reserved for future versions | Reject (`reserved-type`) |
| `0xFF` | reserved | Reject (`reserved-type`) |

<!-- data: frame-types.json -->

| Code | Name | Class | Section |
|---|---|---|---|
| 0x01 | FRAGMENT | link-local | link.md |
| 0x02 | HANDSHAKE | link-local | link.md |
| 0x03 | PLAIN_HELLO | link-local | link.md |
| 0x04 | CLOSE | link-local | link.md |
| 0x05 | REKEY | link-local | link.md |
| 0x06 | BUSY | link-local | link.md |
| 0x07 | NEIGHBOR_LIST | link-local | link.md |
| 0x08 | SHUFFLE | link-local | link.md |
| 0x09 | SHUFFLE_REPLY | link-local | link.md |
| 0x0A | CATCH_UP | link-local | link.md |
| 0x0B | IHAVE | link-local | routing.md |
| 0x0C | GRAFT | link-local | routing.md |
| 0x0D | PRUNE | link-local | routing.md |
| 0x0E | RESOLVING_KEY_UPDATE | link-local | link.md |
| 0x0F | BEARER_SWITCH | link-local | link.md |
| 0x40 | MESSAGE | unicast | routing.md |
| 0x41 | DELIVERY_RECEIPT | unicast | end-to-end.md |
| 0x42 | READ_RECEIPT | unicast | end-to-end.md |
| 0x43 | ROUTE_REPLY | unicast | routing.md |
| 0x44 | LOOKUP_REPLY | unicast | routing.md |
| 0x80 | BROADCAST | flood | routing.md |
| 0x81 | ROUTE_REQUEST | flood | routing.md |
| 0x82 | LOOKUP_REQUEST | flood | routing.md |

## Domain-string registry

([D-01].) Each string is used for exactly one purpose. A new purpose needs a new string.

<!-- data: domain-strings.json -->

| Purpose | String | Primitive | Section |
|---|---|---|---|
| node-id | MeshLink/1 node-id | BLAKE2b-256 | identity.md |
| certificate | MeshLink/1 certificate | Ed25519 signature | identity.md |
| mesh-id | MeshLink/1 mesh-id | BLAKE2b-256 | discovery.md |
| mesh-tag | MeshLink/1 mesh-tag | keyed BLAKE2b, 4 B | discovery.md |
| mesh-tag-key | MeshLink/1 mesh-tag-key | HKDF-SHA-256 | discovery.md |
| mesh-psk | MeshLink/1 mesh-psk | HKDF-SHA-256 | link.md |
| resolvable-tag | MeshLink/1 resolvable-tag | keyed BLAKE2b, 8 B | discovery.md |
| link-prologue | MeshLink/1 link-prologue | Noise prologue | link.md |
| public-tag | MeshLink/1 public-tag | BLAKE2b, 8 B | routing.md |
| unsecured-tag | MeshLink/1 unsecured-tag | BLAKE2b, 8 B | routing.md |
| lookup-tag | MeshLink/1 lookup-tag | BLAKE2b, 8 B | routing.md |
| lookup-reply | MeshLink/1 lookup-reply | HKDF-SHA-256 | routing.md |
| broadcast | MeshLink/1 broadcast | Ed25519 signature | routing.md |
| tombstone | MeshLink/1 tombstone | BLAKE2b, 16 B | end-to-end.md |
| e2e-prologue | MeshLink/1 e2e-prologue | Noise prologue | end-to-end.md |
| hpke-first-message | MeshLink/1 hpke-first-message | HPKE info | end-to-end.md |
| conversation | MeshLink/1 conversation | HKDF-SHA-256 | end-to-end.md |
| conversation-tag | MeshLink/1 conversation-tag | keyed BLAKE2b, 8 B | end-to-end.md |

Signatures and prologues use the same `DS(purpose)` encoding as a prefix of the signed or bound bytes.

## Tools

Run from the repository root with Python 3 and no third-party packages:

```sh
python3 tools/spec/gen_vectors.py   # regenerate docs/spec/vectors (deterministic)
python3 tools/spec/check_spec.py    # compare tables with data, check schemas, recompute vectors
```

`tools/spec/mlspec.py` is the reference codec both scripts share. It is a test aid, not a library.

## Change log

| Date | Change |
|---|---|
| 2026-11-08 | First draft of protocol 1 (ticket 29). |
| 2026-11-08 | Owner accepted decisions D-01 to D-43 and conflict resolutions C-01 to C-10; `NEIGHBOR_LIST` carries the sender's next advertisement identifier (D-24). Decision log renamed to `decisions.md`. |
| 2026-11-13 | Errata, no layout change: ADR decision citations in `link.md` (keep one link, close, neighbor list, shuffle, catch-up) and D-24 corrected; in `end-to-end.md`, retries of a first message resend the same container re-tagged for the current epoch, as `routing.md` already stated. |
