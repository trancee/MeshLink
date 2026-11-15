# hpke-pq test vectors (vendored)

- **Source:** <https://github.com/hpkewg/hpke-pq>, file `test-vectors.json`, the JSON form of draft-ietf-hpke-pq-05 Appendix A.
- **Commit:** `6433c8fce0b8b749dfc86c1095081a88698ccfab` (tag `draft-ietf-hpke-pq-05`).
- **License:** an IETF contribution under BCP 78 and the IETF Trust Legal Provisions; code components are under the Revised BSD License.
- **Checksums:** [`SHA256SUMS`](SHA256SUMS). `generateSpecVectors` refuses a file whose SHA-256 differs.
- **Used by:** the vector runner in `:meshlink-crypto-sodium` `commonTest` ([vectors README](../../../docs/spec/vectors/README.md#vector-runner)). The runner checks the X-Wing (`0x647a`) KEM fields; the HPKE fields belong to the HPKE ticket.

To update: download `test-vectors.json` at the new draft's tag, run `shasum -a 256 *.json > SHA256SUMS` in this directory, and update the commit here and in the [dependency register](../../../docs/reference/dependencies.md).
