# MeshLink Discovery Advertisement Contract

## Purpose

Define the normative BLE discovery advertisement shape that Android and iOS
implementations must share for MeshLink interoperability.

## Canonical Advertisement Shape

A conformant MeshLink discovery advertisement carries exactly two normative UUID
values in one advertisement payload:

1. **Fixed discovery service UUID** — the 32-bit service UUID `4d455348`
   (Bluetooth-base expanded form
   `4d455348-0000-1000-8000-00805f9b34fb`).
2. **Discovery payload UUID** — one 128-bit service UUID whose 16 raw UUID
   bytes are the MeshLink discovery payload for the advertising node.

Platform APIs may surface the 32-bit UUID in expanded 128-bit form, but that is
still the same logical fixed discovery UUID for contract and reviewer purposes.

## Encoding Rules

- The discovery payload UUID is exactly 16 bytes and is encoded entirely inside
  the second advertised UUID value.
- The entire normative discovery contract MUST fit in a single advertisement.
- Discovery advertisements MUST NOT rely on a scan response.
- Conformant receivers MUST be able to discover a peer from the single
  advertisement alone.
- Manufacturer data, service data, local-name fields, or platform-private BLE
  metadata MAY exist for debugging or host-app convenience, but they are not
  part of the MeshLink normative discovery contract and MUST NOT be required for
  interoperability.

## Cross-Platform Invariants

- Android and iOS advertise the same fixed discovery UUID value.
- Android and iOS encode the same 16-byte discovery payload semantics in the
  second UUID slot.
- Discovery parsing MUST treat the two UUID entries as one contract: fixed
  discovery marker + payload carrier.
- The normative product path remains MeshLink L2CAP-first. Discovery
  advertisements MUST NOT imply that proof-only GATT experiments are acceptable
  product-conformance fallback behavior.

## Reserved and Prohibited Shapes

- The UUID block beginning at `4d455348-0001-1000-8000-000000000000` is
  reserved for proof-only or future experiments.
- Any use of that reserved block is non-conformance evidence unless `spec.md`
  is explicitly amended.
- A conformant implementation MUST NOT split the required discovery information
  across advertisement + scan response.
- A conformant implementation MUST NOT require platform-specific BLE record
  ordering beyond the logical presence of the fixed discovery UUID and the
  payload UUID.
- A conformant implementation MUST NOT require host applications to parse
  manufacturer data or out-of-band metadata to recover the 16-byte MeshLink
  discovery payload.

## Reviewer Evidence Expectations

- Contract tests cover the fixed `4d455348` UUID, the second 128-bit payload
  UUID, the 16-byte payload length, the single-advertisement constraint, and the
  no-scan-response rule.
- Android and iOS proof integrations retain logs or snapshots that show both
  advertised UUID values and confirm the same payload semantics.
- Negative contract coverage demonstrates rejection of unsupported shapes such
  as missing fixed UUID, missing payload UUID, payload length mismatch,
  scan-response dependence, or reserved proof-only UUID usage on the normative
  path.

## Evolution Rules

Any future discovery-shape change requires a specification amendment plus
synchronized updates to this contract, `spec.md`, `plan.md`, `tasks.md`, and
reviewer-facing proof evidence expectations.
