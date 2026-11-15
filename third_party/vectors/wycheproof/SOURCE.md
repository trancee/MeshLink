# Wycheproof test vectors (vendored)

- **Source:** <https://github.com/C2SP/wycheproof>, directory `testvectors_v1/`.
- **Commit:** `65ca494727a52944c204441c860b039e6786b99b` (2026-11-11).
- **License:** Apache-2.0, copied unchanged to [`LICENSE`](LICENSE).
- **Checksums:** [`SHA256SUMS`](SHA256SUMS). `generateSpecVectors` refuses a file whose SHA-256 differs.
- **Files:** unchanged copies of `x25519_test.json`, `ed25519_test.json`, `chacha20_poly1305_test.json`, `hkdf_sha256_test.json`, `mlkem_768_test.json`, `mlkem_768_encaps_test.json`, `mlkem_768_keygen_seed_test.json` and `mlkem_768_semi_expanded_decaps_test.json`.
- **Used by:** the vector runner in `:meshlink-crypto-sodium` `commonTest` ([vectors README](../../../docs/spec/vectors/README.md#vector-runner)).

To update: download the same files at a newer commit, run `shasum -a 256 *.json > SHA256SUMS` in this directory, and update the commit here and in the [dependency register](../../../docs/reference/dependencies.md).
