# MeshLink Discovery Advertisement Contract

## Purpose

Define the normative BLE discovery advertisement shape that Android and iOS
implementations must share for MeshLink interoperability.

## Normative Contract

- Discovery advertisements use the fixed 32-bit discovery service UUID
  `4d455348` (Bluetooth-base expanded form
  `4d455348-0000-1000-8000-00805f9b34fb`).
- The same advertisement also carries one second 128-bit service UUID whose
  payload contains the 16-byte MeshLink discovery payload.
- The entire discovery payload MUST fit in a single advertisement.
- Discovery advertisements MUST NOT rely on a scan response.
- The UUID block beginning at `4d455348-0001-1000-8000-000000000000` is
  reserved for proof-only or future experiments and is not product-conformance
  evidence unless `spec.md` is explicitly amended.

## Validation Expectations

- Android and iOS proof integrations emit the same discovery UUID values and
  payload semantics.
- Contract tests cover the fixed UUID, payload length, single-advertisement
  constraint, and no-scan-response rule.
- Any future discovery-shape change requires a specification amendment plus
  synchronized updates to this contract, `spec.md`, `plan.md`, and `tasks.md`.
