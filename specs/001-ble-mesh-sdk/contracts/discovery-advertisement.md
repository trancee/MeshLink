# MeshLink discovery advertisement contract

## Purpose

Define the normative BLE discovery advertisement shape that Android and iOS must
share for MeshLink interoperability.

## Canonical advertisement shape

A conformant MeshLink advertisement carries exactly two normative UUID values in
one advertisement payload:

1. **Fixed discovery service UUID** — the 32-bit service UUID `4d455348`
2. **Discovery payload UUID** — one 128-bit service UUID whose 16 raw UUID
   bytes are the MeshLink discovery payload for the advertising node

Platform APIs may surface the 32-bit UUID in expanded 128-bit form, but it is
still the same logical fixed discovery UUID.

## Encoding rules

- The discovery payload UUID is exactly 16 bytes and is encoded entirely inside
  the second advertised UUID value.
- Byte 0 of the 16-byte payload is a shared header:
  - bits 7..5 = protocol version
  - bits 4..3 = power mode
  - bits 2..0 = platform-family / initiator-policy hint
- Current platform-family values are:
  - `0` = unknown / legacy
  - `1` = Android
  - `2` = iOS
- Mixed Android/iOS peers that both advertise the current hint use Android as
  the deterministic L2CAP initiator so the iPhone can host the inbound channel.
- Same-platform peers, or any peer still advertising the unknown/legacy hint,
  fall back to the existing key-hash ordering policy.
- The full normative discovery contract must fit in a single advertisement.
- Discovery advertisements must not rely on a scan response.
- Receivers must be able to discover a peer from that single advertisement.
- Manufacturer data, service data, local-name fields, or platform-private BLE
  metadata may exist for debugging, but they are not part of the normative
  MeshLink contract and must not be required for interoperability.

## Cross-platform invariants

- Android and iOS advertise the same fixed discovery UUID.
- Android and iOS encode the same 16-byte payload semantics in the second UUID
  slot.
- Android and iOS interpret the platform-family hint the same way when choosing
  the deterministic mixed-platform L2CAP initiator.
- Discovery parsing treats the two UUID entries as one contract: fixed marker
  plus payload carrier.
- The normative product path remains L2CAP-first. Discovery must not imply that
  proof-only GATT experiments are product-conformance fallback behavior.

## Reserved and prohibited shapes

- The UUID block beginning at `4d455348-0001-1000-8000-000000000000` is
  reserved for proof-only or future experiments.
- Using that reserved block on the normative path is non-conformance evidence
  unless `spec.md` is explicitly amended.
- A conformant implementation must not split the required discovery information
  across advertisement and scan response.
- A conformant implementation must not depend on platform-specific BLE record
  ordering beyond the logical presence of the fixed UUID and the payload UUID.
- A conformant implementation must not require host apps to parse manufacturer
  data or out-of-band metadata to recover the 16-byte payload.

## Reviewer evidence expectations

- Contract tests cover the fixed `4d455348` UUID, the second 128-bit payload
  UUID, the 16-byte payload length, the shared header bits, the
  single-advertisement constraint, and the no-scan-response rule.
- Android and iOS proof integrations retain logs or snapshots that show both
  advertised UUID values and confirm the same payload semantics.
- Negative tests reject unsupported shapes such as missing fixed UUID, missing
  payload UUID, payload-length mismatch, scan-response dependence, or reserved
  proof-only UUID usage on the normative path.

## Evolution rules

Any future discovery-shape change requires a specification amendment plus
synchronized updates to this contract, `spec.md`, `plan.md`, `tasks.md`, and
reviewer-facing evidence expectations.
