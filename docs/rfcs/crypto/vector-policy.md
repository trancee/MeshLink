# Crypto Vector Policy

This document is for maintainers extending MeshLink's local crypto corpus or the M002 hardening gate. After reading it, you should be able to classify a new vector, predict whether the current `CryptoProvider` seam must accept, reject, or fail closed, and rerun the authoritative S01 gate without confusing it with the older BLE proof workflow.

## Canonical evidence for S01

- The machine-readable corpus policy lives in [`wycheproof/policy.json`](wycheproof/policy.json).
- The authoritative automated evidence now comes from the repository's current Gradle/CI crypto verification bundle rather than the older slice-specific shell wrappers.
- The iOS bridge boundary and install contract stay documented in the [MeshLink SDK API reference](../../reference/meshlink-sdk-api.md#ios-bridge-entry-points) and [How to use MeshLink from Swift](../../how-to/use-meshlink-from-swift.md).
- The higher-level peer-trust behavior stays documented in [The Trust Model](../../explanation/trust-model.md).
- Physical Android ↔ iOS proof evidence now lives with the [benchmark and validation baselines](../../../benchmarks/README.md) and the proof-app guides rather than the older M002 runbook set.

## Policy verdicts

| policy | Provider expectation | Runtime expectation |
| --- | --- | --- |
| `accept` | The provider must produce the expected result. Ed25519 verification must return `true`. X25519 derivation must produce the tracked shared secret exactly. | Runtime can proceed normally only after the accepted provider result is available. |
| `reject` | The provider must not accept the vector. Returning `false` or throwing is acceptable; silently succeeding is not. | Runtime fails closed. There is no plaintext fallback and no permissive retry path. |
| `fail_closed_or_match` | The provider may reject or throw, but if it returns a value then that value must match the tracked shared secret exactly. | Runtime still fails closed on rejection. Accepting the vector with the wrong derived secret is a regression. |

`fail_closed_or_match` is currently used only for X25519 edge cases where different providers may reject malformed public keys at different layers, but any accepting provider must still converge on the same secret bytes.

## Current manifest coverage

The tracked manifest currently covers Ed25519 verification and X25519 shared-secret derivation.

| algorithm | accept | reject | fail_closed_or_match | buckets |
| --- | ---: | ---: | ---: | ---: |
| ed25519 | 3 | 7 | 0 | 10 |
| x25519 | 6 | 0 | 18 | 24 |
| total | 9 | 7 | 18 | 34 |

If `wycheproof/policy.json` changes, update this table in the same change. The current automated verification bundle should treat stale counts as documentation drift.

## Provider coverage matrix

| Surface | What it proves now | Evidence path |
| --- | --- | --- |
| Corpus integrity | Every tracked Wycheproof tcId is classified explicitly and stale tcIds fail the build. | `CryptoPolicyCorpusTest` plus [`wycheproof/policy.json`](wycheproof/policy.json) |
| JVM provider conformance | The JVM test provider matches the RFC vectors and the explicit Wycheproof policy buckets. | `JvmCryptoPolicyConformanceTest` |
| Android provider conformance | `AndroidCryptoProvider` honors the same RFC vectors and explicit policy buckets on host execution. | `AndroidCryptoPolicyConformanceTest` |
| Runtime fail-closed behavior | `MeshLink.create(transport, cryptoProvider, diagnostics)` rejects malformed crypto before HKDF/session transport use. | `CryptoProviderRuntimeContractTest` and `MeshRuntimeAndroidCryptoTest` |
| iOS bridge boundary | The iOS bridge contract remains compile + link only for `iosArm64`; this slice does not claim simulator or on-device runtime execution. | Targeted `iosArm64` compile/link coverage in the current Gradle/CI verification bundle |

## Fail-closed runtime expectations

The current runtime proof boundary is deterministic and deliberately narrow:

- Malformed advertisements fail before a peer becomes reachable and surface `DiagnosticPayload.TrustFailure` at `DIRECT_ADVERTISEMENT` plus `DiagnosticPayload.SendFailure` at `TRUST_VALIDATION`.
- Rejected or all-zero X25519 shared secrets fail at `DIRECT_ENCRYPTION` before HKDF derivation or transport dispatch.
- A decrypt, sign, verify, or derivation failure stops the operation immediately. MeshLink does not fall back to plaintext, cached shared secrets, or an alternate permissive provider path.
- Test failures should identify the provider, policy bucket or tcId, and runtime stage only. Human-readable reasons are good; secret bytes are not.

## Redaction rules

Redacted output is part of the contract:

- Identify vectors by `algorithm`, `tcId`, bucket id, Wycheproof flags, provider label, and runtime stage.
- Use structured reason codes such as `accepted-vector-rejected`, `reject-vector-accepted`, `fail-closed-vector-wrong-shared-secret`, `DIRECT_ENCRYPTION`, and `TRUST_VALIDATION`.
- Never print private keys, shared-secret bytes, session keys, HKDF output, or full vector payloads in docs, test failures, or gate output.
- Do not dump DER/PKCS8 blobs or raw delegate payloads to explain a regression. Use bucket context and stage labels instead.

## Maintenance workflow

1. Update the tracked local RFC or Wycheproof corpus.
2. Classify every new tcId in [`wycheproof/policy.json`](wycheproof/policy.json); uncategorized or stale tcIds are gate failures.
3. Update this document so the coverage table and prose still match the manifest and current proof boundary.
4. Run the current Gradle/CI verification bundle for the crypto/runtime contract.
5. If later work broadens live-radio or cross-platform proof, extend that current verification bundle rather than reintroducing slice-specific shell gates.
