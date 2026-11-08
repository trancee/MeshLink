# Identity

Status: Draft. Part of [MeshLink protocol 1](README.md).

## NodeId

(ADR 0003 decision; ADR 0012 decision 8.)

Each installation has one long-term Ed25519 identity key. Its NodeId is the full 32 B hash:

```text
NodeId = H("node-id", ed25519_public_key)          32 B, unkeyed
```

- NodeIds rarely go on air: routing uses rotating tags. They appear in clear only in `unsecured` (sender field, `PLAIN_HELLO`), and inside encrypted handshakes and end-to-end plaintexts.
- The short display form and the local `PeerRef` (ADR 0020) are local and never go on the wire.
- In `unsecured` the NodeId is claimed, not proven (ADR 0012 decision 12).

Vectors: `derivations.json`, derivation `node-id`.

## Identity certificate

(ADR 0012 decision 9; ADR 0003 amendment; ADR 0014 decisions 9 and 10; [D-11].)

The secured profiles bind the identity key to rotating static keys with a fixed-layout certificate. `unsecured` has no certificates (ADR 0018 decision 10).

### Classical layout (profile `0x01`)

| Offset | Size | Name | Encoding | Notes |
|---|---|---|---|---|
| 0 | 1 | version | `u8` | `0x01` [D-11] |
| 1 | 1 | profile | `u8` | `0x01` |
| 2 | 32 | ed25519_key | `bytes[32]` | Identity key; hashes to the NodeId |
| 34 | 32 | x25519_key | `bytes[32]` | Static Diffie-Hellman key for `IK` and public tags |
| 66 | 4 | not_before | `u32` | Unix seconds |
| 70 | 4 | not_after | `u32` | Unix seconds |
| 74 | 64 | signature | `bytes[64]` | Ed25519, see below |

Total: 138 B.

### Post-quantum layout (profile `0x02`)

| Offset | Size | Name | Encoding | Notes |
|---|---|---|---|---|
| 0 | 1 | version | `u8` | `0x01` |
| 1 | 1 | profile | `u8` | `0x02` |
| 2 | 32 | ed25519_key | `bytes[32]` | Identity key |
| 34 | 32 | x25519_key | `bytes[32]` | Static key for `IKhfs` and public tags |
| 66 | 1,216 | xwing_key | `bytes[1216]` | X-Wing (MLKEM768-X25519) public key for the HPKE seal |
| 1,282 | 4 | not_before | `u32` | Unix seconds |
| 1,286 | 4 | not_after | `u32` | Unix seconds |
| 1,290 | 64 | signature | `bytes[64]` | Ed25519, see below |

Total: 1,354 B. Both totals match ADR 0012 decision 9 and the "about 140 B / 1.35 KB" of ADR 0018.

<!-- data: limits.json -->

| Name | Value | Unit | Decision |
|---|---|---|---|
| certificate-version | 1 | - | D-11 |
| certificate-classical-length | 138 | B | - |
| certificate-post-quantum-length | 1,354 | B | - |
| certificate-validity-max | 86,400 | s | - |
| certificate-renewal-interval | 43,200 | s | - |
| static-key-grace | 3,630 | s | - |

### Signature

The signature is Ed25519 (RFC 8032, pure) by the identity key over

```text
DS("certificate") ‖ certificate bytes 0 .. (length − 65)
```

that is, every byte before the signature, prefixed by the encoded domain string (README, "Derivation rules").

### Validation

A receiver MUST reject a certificate unless all of these hold:

1. `version` is `0x01` and `profile` is the mesh's profile. The length matches the profile exactly.
2. `0 < not_after − not_before ≤ 86,400`.
3. The signature verifies under `ed25519_key`.
4. `H("node-id", ed25519_key)` equals the NodeId the certificate is used for (link peer, `IK` sender, lookup target).
5. For sealing a new message: `now < not_after`, allowing the 30 s clock skew of ADR 0008. A sender MUST NOT seal to an expired certificate (ADR 0014 decision 10).
6. `now ≥ not_before − 30 s`.

Vectors: `certificate.json` (layout only; signatures are placeholders) and `derivations.json`, derivation `certificate-signing-input`. Signature vectors are tier 2.

## Static-key rotation

(ADR 0003 amendment; ADR 0014 decision 9; ADR 0016 decision 1.)

- A node issues a new certificate with fresh X25519 (and X-Wing) static keys every 12 h. Each certificate is valid for 24 h, so two are valid at once.
- A node MUST keep the static private keys of a certificate until its `not_after` plus 3,630 s (the 1 h maximum message lifetime plus 30 s of skew), then delete them. It accepts `IK` messages and public tags for every certificate whose keys it still holds.
- A node attaches its current certificate to `IK` replies and receipts whenever the peer's copy is past half its validity (ADR 0014 decision 10); see [end-to-end.md](end-to-end.md#plaintext-extensions).
- The certificate is also sent in every link handshake ([link.md](link.md#handshake-payload)).

Certificate acquisition by lookup is in [routing.md](routing.md#certificate-lookup).
