# Crypto vector policy

This note explains how MeshLink classifies tracked crypto vectors and what the
runtime is expected to do with each class.

Use it when you are extending the local crypto corpus, updating
`wycheproof/policy.json`, or debugging the crypto/runtime contract gate.

## Canonical evidence

- The machine-readable policy lives in [`wycheproof/policy.json`](wycheproof/policy.json).
- The authoritative automated evidence comes from the repository's current
  Gradle/CI crypto verification bundle.
- The iOS bridge boundary and install contract are documented in the
  [MeshLink SDK API reference](../../reference/meshlink-sdk-api.md#ios-bridge-entry-points)
  and [How to use MeshLink from Swift](../../how-to/use-meshlink-from-swift.md).
- Higher-level peer-trust behavior is documented in
  [The trust model](../../explanation/trust-model.md).
- Physical Android ↔ iOS proof evidence lives with the
  [benchmark and validation baselines](../../../benchmarks/README.md).

## Policy verdicts

| Policy | Provider expectation | Runtime expectation |
|---|---|---|
| `accept` | Provider must produce the expected result. | Runtime may proceed normally once the accepted result is available. |
| `reject` | Provider must not accept the vector. Returning `false` or throwing is acceptable. | Runtime fails closed. There is no permissive fallback. |
| `fail_closed_or_match` | Provider may reject or throw, but any returned value must match the tracked shared secret exactly. | Runtime still fails closed on rejection. Accepting with the wrong value is a regression. |

`fail_closed_or_match` is currently used only for X25519 edge cases where
providers may reject malformed public keys at different layers.

## Current manifest coverage

The tracked manifest currently covers Ed25519 verification and X25519
shared-secret derivation.

| Algorithm | Accept | Reject | Fail closed or match | Total |
|---|---:|---:|---:|---:|
| `ed25519` | 3 | 7 | 0 | 10 |
| `x25519` | 6 | 0 | 18 | 24 |
| **Total** | **9** | **7** | **18** | **34** |

If `wycheproof/policy.json` changes, update this table in the same change.

## Provider coverage matrix

| Surface | What it proves now | Evidence path |
|---|---|---|
| Corpus integrity | Every tracked tcId is classified explicitly and stale tcIds fail the build. | `CryptoPolicyCorpusTest` plus `wycheproof/policy.json` |
| JVM provider conformance | The JVM provider matches RFC vectors and explicit policy buckets. | `JvmCryptoPolicyConformanceTest` |
| Android provider conformance | `JcaCryptoProvider` honors the same vectors and policy buckets on host execution. | `AndroidCryptoPolicyConformanceTest` |
| Runtime fail-closed behavior | MeshLink rejects malformed crypto before HKDF or transport use. | `CryptoProviderRuntimeContractTest` and `MeshRuntimeAndroidCryptoTest` |
| iOS bridge boundary | The iOS bridge contract remains compile/link only for `iosArm64`. | Current Gradle/CI verification bundle |

## Fail-closed runtime expectations

The current runtime proof boundary is deliberately narrow and deterministic:

- malformed advertisements fail before a peer becomes reachable
- rejected or all-zero X25519 shared secrets fail before HKDF derivation or
  transport dispatch
- decrypt, sign, verify, or derivation failure stops the operation immediately
- MeshLink never falls back to plaintext, cached shared secrets, or a permissive
  alternate provider path

## Redaction rules

Use structured identifiers such as algorithm, tcId, bucket id, provider label,
and runtime stage. Do **not** print:

- private keys
- shared-secret bytes
- session keys
- HKDF output
- full raw vector payloads

Human-readable failure context is good. Secret material is not.

## Maintenance workflow

1. Update the tracked local RFC or Wycheproof corpus.
2. Classify every new tcId in `wycheproof/policy.json`.
3. Update this document so the coverage table still matches the manifest.
4. Run the current Gradle/CI verification bundle for the crypto/runtime
   contract.
5. If later work broadens live-radio or cross-platform proof, extend the current
   verification bundle instead of reviving slice-specific shell gates.
