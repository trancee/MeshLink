# M009 routing metadata privacy envelope and negotiation contract

Status: Draft (S01 design contract)

This RFC defines the wire contract for protecting routing metadata in MeshLink,
including envelope format, versioning, capability negotiation, fallback
behavior, downgrade handling, and the machine-readable evidence required for the
next implementation slices.

## Scope

This contract covers routing-control metadata only. It does **not** redesign:

- trust or identity
- payload-layer application encryption
- product-level UX policy

## Goals

- protect route-control metadata from passive BLE observers when both peers
  support privacy mode
- preserve interoperability with legacy peers through explicit negotiation
- make fallback and downgrade outcomes machine-observable

## Baseline

Before M009, route-control payloads are plaintext `BabelRouteControlFrame`
values encoded by `BabelRouteFrameCodec` with wire types:

- `0x21` — UPDATE
- `0x22` — WITHDRAWAL

That leaves destination, next-hop, metric, and sequence metadata visible to a
nearby observer.

## Envelope design

### New frame types

To preserve backward compatibility and explicit negotiation, M009 adds:

- `0x20` — `ROUTE_CAPS`
- `0x23` — `ROUTE_PRIVACY_ENVELOPE`

Legacy frame types `0x21` and `0x22` remain valid for negotiated fallback mode.

### `ROUTE_CAPS` (`0x20`)

`ROUTE_CAPS` is plaintext and intentionally small.

| Byte(s) | Field | Type | Notes |
|---|---|---|---|
| 0 | `frame_type` | u8 | `0x20` |
| 1 | `version` | u8 | schema version, initial `1` |
| 2 | `capability_bits` | u8 | bit0 = supports privacy envelope v1 |
| 3 | `max_privacy_version` | u8 | highest supported privacy version |
| 4 | `min_privacy_version` | u8 | lowest supported privacy version |
| 5 | `flags` | u8 | reserved, must be `0` in v1 |

### `ROUTE_PRIVACY_ENVELOPE` (`0x23`)

`ROUTE_PRIVACY_ENVELOPE` carries an encrypted inner route-control frame.

| Byte(s) | Field | Type | Notes |
|---|---|---|---|
| 0 | `frame_type` | u8 | `0x23` |
| 1 | `envelope_version` | u8 | initial `1` |
| 2 | `inner_frame_type` | u8 | `0x21` or `0x22` |
| 3 | `cipher_suite_id` | u8 | `0x01` = Noise-session AEAD default |
| 4 | `key_phase` | u8 | sender rekey epoch |
| 5..8 | `seq` | u32 LE | monotonic per-neighbour privacy sequence |
| 9 | `nonce_len` | u8 | v1 requires `12` |
| 10.. | `nonce` | bytes | AEAD nonce |
| next 2 | `ciphertext_len` | u16 LE | encrypted inner-frame length |
| next N | `ciphertext` | bytes | encrypted inner frame |
| next 16 | `tag` | bytes | AEAD tag |

The encrypted plaintext is exactly the existing `BabelRouteFrameCodec.encode(...)`
output for UPDATE or WITHDRAWAL, so route-table logic can stay intact after a
successful decrypt.

### Authenticated data (AAD)

AAD for v1 is:

`frame_type || envelope_version || inner_frame_type || cipher_suite_id || key_phase || seq || nonce_len`

That binds envelope metadata to ciphertext integrity.

## Version rules

- `ROUTE_CAPS.version` and `ROUTE_PRIVACY_ENVELOPE.envelope_version` are
  versioned independently
- unknown versions are hard parse failures for the offending frame
- privacy mode is valid only when both peers share at least one supported
  envelope version
- v1 selects envelope version `1`

## Capability negotiation

### Exchange timing

- after a Noise session is available for a neighbour, each side emits one
  `ROUTE_CAPS` frame
- capability state is bound to that session and cleared on disconnect or reset

### Deterministic mode selection

Per neighbour:

1. if local support and remote support overlap on privacy v1, select
   `privacy_v1`
2. otherwise, select `legacy` and record an explicit fallback reason

No implicit mode switching is allowed.

### Compatibility matrix

| Local | Remote | Selected mode | Reason |
|---|---|---|---|
| privacy_v1 | privacy_v1 | privacy_v1 | negotiated |
| privacy_v1 | legacy/none | legacy | `fallback_legacy_peer` |
| legacy/none | privacy_v1 | legacy | `fallback_local_legacy` |
| legacy/none | legacy/none | legacy | `fallback_both_legacy` |

## Fallback and downgrade rules

Fallback is valid only for explicit capability mismatch, not for parse,
decrypt, auth, or version failures.

Required fallback reasons:

- `fallback_legacy_peer`
- `fallback_local_legacy`
- `fallback_both_legacy`
- `fallback_local_policy`

Downgrade verdicts are separate:

- `none`
- `downgrade_suspected`
- `downgrade_detected`

Deterministic v1 rules:

1. if privacy was negotiated and a legacy route frame arrives afterward, flag
   downgrade (`suspected` first, then `detected` on repeat or threshold breach)
2. if a privacy-capable peer sends an unsupported or rolled-back envelope
   version, mark `downgrade_detected`
3. if envelope auth or decrypt fails after privacy negotiation, mark
   `downgrade_detected`
4. benign capability-mismatch fallback keeps `downgrade=none`

Privacy mode is fail-closed: auth, decrypt, and version failures drop the frame
and do not silently switch the session to legacy mode.

## Diagnostics contract

S02/S03 must expose machine-readable diagnostics for at least:

- `route.negotiated_mode`
- `route.fallback_reason`
- `route.downgrade_verdict`
- `route.envelope_version`
- `route.envelope_failure` when applicable

Those fields may extend the current routing diagnostics or use a dedicated
routing-privacy payload, but they must stay acceptance-projectable.

## Acceptance evidence contract

S03 closeout bundles must include machine-readable evidence for:

- privacy-envelope counts on both legs
- negotiated-mode counts
- fallback-reason counts
- suspected/detected downgrade counts
- legacy compatibility application
- attack-path legacy drop behavior

The acceptance runner should fail closed when required fields are missing,
malformed, or inconsistent with the claimed scenario.

## Deterministic test vectors

### Test vector 1 — `ROUTE_CAPS` advertises privacy v1

Hex:

`20 01 01 01 01 00`

Meaning:

- frame type `0x20`
- schema version `0x01`
- capability bit0 set
- max/min privacy version `0x01`
- flags `0x00`

### Test vector 2 — negotiated privacy success

Input:

- local caps: `20 01 01 01 01 00`
- remote caps: `20 01 01 01 01 00`

Expected:

- `negotiated_mode=privacy_v1`
- `fallback_reason=none`
- `downgrade_verdict=none`

### Test vector 3 — benign fallback with a legacy peer

Input:

- local caps: `20 01 01 01 01 00`
- remote caps: absent

Expected:

- `negotiated_mode=legacy`
- `fallback_reason=fallback_legacy_peer`
- `downgrade_verdict=none`

### Test vector 4 — legacy frame after privacy negotiation

Input:

- prior state: `negotiated_mode=privacy_v1`
- received frame type: `0x21`

Expected:

- `downgrade_verdict=downgrade_suspected` on the first violation
- escalation to `downgrade_detected` on repeat
- offending frame dropped per policy

## Implementation note

The route-table semantics should stay unchanged: decrypt the envelope first,
then pass the inner payload through the existing route-frame decode/application
flow.
