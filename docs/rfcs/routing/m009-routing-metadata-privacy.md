# M009 Routing Metadata Privacy Envelope and Negotiation Contract

Status: Draft (S01 design contract)

This RFC defines the wire contract for protecting routing metadata in MeshLink, including
Envelope format, Versioning, Capability negotiation, Fallback behavior, Downgrade handling,
and deterministic test vectors for S02 implementation.

## Scope

This contract covers routing-control metadata only (Babel update/withdrawal semantics).
It does not redesign trust, identity, or payload-layer application encryption.

## Goals

- Protect route-control metadata from passive BLE observers when both peers support privacy mode.
- Preserve interoperability with legacy peers through explicit negotiation and deterministic fallback.
- Make downgrade and compatibility outcomes machine-observable for acceptance artifacts.

## Non-goals

- Full endpoint-compromise resistance.
- New PKI/trust infrastructure.
- Product-level UX policy decisions.

## Baseline (pre-M009)

Current route-control payloads are plaintext `BabelRouteControlFrame` encoded by
`BabelRouteFrameCodec` with wire types:

- `0x21` UPDATE
- `0x22` WITHDRAWAL

This leaks destination/routerId/nextHop/metric/seqno visibility to nearby observers.

## Envelope

### New frame types

To preserve backward compatibility and explicit negotiation, add two frame types:

- `0x20` ROUTE_CAPS (capability advertisement)
- `0x23` ROUTE_PRIVACY_ENVELOPE (encrypted route-control metadata)

Legacy frame types (`0x21`, `0x22`) remain valid for negotiated fallback mode.

### ROUTE_CAPS frame (0x20)

`ROUTE_CAPS` is plaintext and small; it does not reveal route graph data.

| Byte(s) | Field | Type | Notes |
|---|---|---|---|
| 0 | frame_type | u8 | `0x20` |
| 1 | version | u8 | ROUTE_CAPS schema version (initial `1`) |
| 2 | capability_bits | u8 | bit0 = supports privacy envelope v1 |
| 3 | max_privacy_version | u8 | highest privacy version supported |
| 4 | min_privacy_version | u8 | lowest privacy version supported |
| 5 | flags | u8 | reserved (must be `0` in v1) |

### ROUTE_PRIVACY_ENVELOPE frame (0x23)

`ROUTE_PRIVACY_ENVELOPE` carries an encrypted inner route-control frame.

| Byte(s) | Field | Type | Notes |
|---|---|---|---|
| 0 | frame_type | u8 | `0x23` |
| 1 | envelope_version | u8 | privacy envelope version (initial `1`) |
| 2 | inner_frame_type | u8 | `0x21` or `0x22` |
| 3 | cipher_suite_id | u8 | `0x01` = Noise-session AEAD default |
| 4 | key_phase | u8 | sender key phase / rekey epoch |
| 5..8 | seq | u32 LE | monotonic per-neighbour privacy seq |
| 9 | nonce_len | u8 | v1 requires `12` |
| 10..(10+nonce_len-1) | nonce | bytes | AEAD nonce |
| next 2 | ciphertext_len | u16 LE | encrypted inner frame bytes length |
| next N | ciphertext | bytes | AEAD-encrypted inner frame bytes |
| next 16 | tag | bytes | AEAD auth tag |

Plaintext encrypted into `ciphertext` is exactly the existing `BabelRouteFrameCodec.encode(...)`
output for UPDATE/WITHDRAWAL, so route-table logic can reuse current decode/application flow
after successful decryption.

### Authenticated data (AAD)

AAD for v1 is:

`frame_type || envelope_version || inner_frame_type || cipher_suite_id || key_phase || seq || nonce_len`

This binds routing-control type/version metadata to ciphertext integrity.

## Version

- `ROUTE_CAPS.version` and `ROUTE_PRIVACY_ENVELOPE.envelope_version` are independently versioned.
- Unknown `ROUTE_CAPS.version` or `envelope_version` is a hard parse failure for that frame.
- A peer may only select privacy mode when both peers share at least one envelope version in
  `[min_privacy_version, max_privacy_version]`.
- In v1, selected envelope version is `1`.

## Capability negotiation

### Exchange timing

- After a Noise session is available for a neighbour, each side emits one `ROUTE_CAPS` frame.
- Capability state is bound to the current session and cleared on disconnect/session reset.

### Mode selection algorithm (deterministic)

Per neighbour:

1. If local supports privacy and remote `ROUTE_CAPS` advertises matching privacy version: `mode=privacy_v1`.
2. Else: `mode=legacy` with explicit fallback reason.

No implicit mode switching is allowed.

### Compatibility matrix

| Local | Remote | Selected mode | Reason |
|---|---|---|---|
| privacy_v1 | privacy_v1 | privacy_v1 | negotiated |
| privacy_v1 | legacy/none | legacy | `fallback_legacy_peer` |
| legacy/none | privacy_v1 | legacy | `fallback_local_legacy` |
| legacy/none | legacy/none | legacy | `fallback_both_legacy` |

## Fallback

Fallback is valid only for explicit capability mismatch, not parse/decrypt/auth failures.

Required fallback reasons (machine-parseable):

- `fallback_legacy_peer`
- `fallback_local_legacy`
- `fallback_both_legacy`
- `fallback_local_policy` (feature disabled by operator/config)

When in fallback mode, route-control uses legacy frame types (`0x21`/`0x22`).

## Downgrade

Downgrade verdict is distinct from benign fallback.

### Verdict values

- `none`
- `downgrade_suspected`
- `downgrade_detected`

### Deterministic rules (v1)

Given peer/session capability state:

1. If peer negotiated privacy and receives legacy route frame (`0x21`/`0x22`) after negotiation complete:
   - first violation: `downgrade_suspected`
   - repeated violation or policy threshold exceeded: `downgrade_detected`
2. If peer advertised privacy support but sends envelope with unsupported/rolled-back version: `downgrade_detected`.
3. If envelope auth/decrypt fails while peer is privacy-negotiated: `downgrade_detected`.
4. Capability mismatch fallback cases always keep `downgrade=none`.

Fail-closed rule for privacy mode: auth/decrypt/version failures drop the offending frame and do
not silently auto-switch to legacy.

## Diagnostics contract (required fields)

S02/S03 must expose machine-readable diagnostics at least for:

- `route.negotiated_mode` (`privacy_v1`|`legacy`)
- `route.fallback_reason` (or `none`)
- `route.downgrade_verdict` (`none|downgrade_suspected|downgrade_detected`)
- `route.envelope_version`
- `route.envelope_failure` (when applicable: `decode_failed`, `auth_failed`, `decrypt_failed`, `inner_decode_failed`, `missing_session`, `negotiation_incomplete`, `legacy_after_privacy`)

These may be emitted via existing `RouteControlDecision` diagnostics (extended fields)
or a dedicated routing-privacy diagnostic payload type.

## S03 integrated acceptance evidence contract

S03 closeout acceptance bundles must include machine-readable evidence for:

- `route.envelope_count.c_to_b` (privacy envelope frames observed on B↔C leg)
- `route.envelope_count.b_to_a` (privacy envelope frames observed on A↔B leg)
- `route.negotiated_mode.privacy_v1.count`
- `route.negotiated_mode.legacy.count`
- `route.fallback_reason.fallback_legacy_peer.count`
- `route.downgrade.suspected.count`
- `route.downgrade.detected.count`
- `route.legacy_compatibility.applied`
- `route.attack_legacy_drop`

The acceptance runner must fail closed if required fields are missing, malformed, or violate
expected invariants (for example envelope/downgrade counts not strictly positive when the scenario
claims PASS).

## Test vector

### Test vector 1 — ROUTE_CAPS advertise privacy v1

Meaning:
- frame type `0x20`
- caps schema version `0x01`
- capability bit0 set
- max/min privacy version `0x01`
- flags `0x00`

Hex:

`20 01 01 01 01 00`

### Test vector 2 — Negotiated privacy matrix expectation

Input:
- local caps: `20 01 01 01 01 00`
- remote caps: `20 01 01 01 01 00`

Expected:
- `negotiated_mode=privacy_v1`
- `fallback_reason=none`
- `downgrade_verdict=none`

### Test vector 3 — Benign fallback with legacy peer

Input:
- local caps: `20 01 01 01 01 00`
- remote caps: absent

Expected:
- `negotiated_mode=legacy`
- `fallback_reason=fallback_legacy_peer`
- `downgrade_verdict=none`

### Test vector 4 — Downgrade detected (legacy frame after privacy negotiation)

Input:
- prior state: `negotiated_mode=privacy_v1`
- received frame type: `0x21`

Expected:
- `downgrade_verdict=downgrade_suspected` (first)
- escalation to `downgrade_detected` on repeated violation
- frame dropped per policy

## S02 implementation notes

- Keep route-table semantics unchanged; decrypt envelope first, then pass inner payload through
  existing `BabelRouteFrameCodec.decode(...)`.
- Maintain backward-compatible decode path: `0x21`/`0x22` remain accepted only when mode is legacy
  or policy explicitly allows transition states.
- Ensure all mode changes and verdicts are diagnostic-visible and acceptance-projectable.
