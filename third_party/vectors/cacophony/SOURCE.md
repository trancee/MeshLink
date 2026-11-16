# cacophony test vectors (vendored)

- **Source:** <https://github.com/haskell-cryptography/cacophony>, file `vectors/cacophony.txt`: JSON `{"vectors":[…]}`, one vector for each of 59 Noise patterns in 16 suites.
- **Commit:** `8ee9d41e34a1a596cfa3ab12aa4069ff87dc1247` (2025-01-08).
- **License:** the Unlicense (public domain), [`LICENSE`](LICENSE).
- **Checksums:** [`SHA256SUMS`](SHA256SUMS). `generateSpecVectors` refuses a file whose SHA-256 differs.
- **Used by:** the vector runner in `:meshlink-crypto-sodium` `commonTest` ([vectors README](../../../docs/spec/vectors/README.md#vector-runner)). The runner replays `Noise_XX_25519_ChaChaPoly_SHA256` and `Noise_XXpsk3_25519_ChaChaPoly_SHA256`, handshake and transport messages; `IK` waits for ticket 46, and the other patterns and suites are outside MeshLink (ADR 0002).

To update: download `vectors/cacophony.txt` and `LICENSE` at the new commit, run `shasum -a 256 cacophony.txt > SHA256SUMS` in this directory, and update the commit here and in the [dependency register](../../../docs/reference/dependencies.md).
