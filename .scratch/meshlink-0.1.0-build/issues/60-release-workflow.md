# Release through a workflow, dry run first

Status: ready-for-agent
Type: AFK
Blocked by: 57, 58

## Behavior

Pushing a `vX.Y.Z` tag on `main` starts `.github/workflows/release.yml` on `macos-26`. It runs every gate, waits for the owner's approval in the `release` environment, then signs and publishes to Maven Central, attaches the XCFramework ZIP to a GitHub Release, deploys Pages, updates and tags `meshlink-swift`, and runs consumer smoke tests. A dry-run mode runs every step that is reversible and none that is not: it signs with a throwaway key, publishes to Maven local, builds and checks the ZIP, and renders the `meshlink-swift` update without pushing. This ticket proves the dry run only; the real release is ticket 64.

## Spec and ADR references

- [ADR 0019](../../../docs/adr/0019-release-versioning-and-api-compatibility.md#decision) decisions 1 (one version for every artifact), 2 (`docs/reference/compatibility.md`), 4 (ABI dumps; `swift-api-digester` in `meshlink-swift` CI), 6 (`CHANGELOG.md`, `checkChangelog`), 7 (the seven workflow steps) and 8 (App token, release-environment secrets), with its 2026-11-13 [amendment](../../../docs/adr/0019-release-versioning-and-api-compatibility.md#amendments): `macos-26`, Xcode 26.4.1.
- [ADR 0011](../../../docs/adr/0011-modules-toggles-and-presets.md#decision) decision 5 and its [amendments](../../../docs/adr/0011-modules-toggles-and-presets.md#amendments): `Package.swift` with URL and checksum, a fresh SwiftPM consumer as smoke test, the fixed coordinates and names.
- [ADR 0005](../../../docs/adr/0005-toolchain-and-dependency-baseline.md#decision), "CI" and "Publishing": vanniktech 0.37.0, SHA-pinned actions, register rows for `actions/attest-build-provenance` and `actions/create-github-app-token`.
- [ADR 0017](../../../docs/adr/0017-testing-architecture.md#decision) decision 7: every release needs a device evidence record.
- [Ticket 02](02-quality-gates.md) for `CHANGELOG.md` and the `checkChangelog` task in `check`; this ticket uses them and adds neither. [Ticket 04](04-apple-packaging-proof.md) for the license in every POM.
- [Ticket 58](58-release-infrastructure.md) for the environment, secret names and App; [ticket 59](59-pages-workflow.md) for the reusable Pages workflow.

## Acceptance criteria

- [ ] `docs/reference/compatibility.md` maps each library release to its protocol versions, starting with 0.1.0 → protocol 1.
- [ ] The tag job fails before the approval gate if the tag differs from the build's version, if any gate fails, if `CHANGELOG.md` has no section for the version, or if that section's evidence-record link does not resolve.
- [ ] After the gate, in ADR 0019's order: sign and upload with `automaticRelease`; build `MeshLinkKit.xcframework.zip`, compute its checksum with `swift package compute-checksum`, attest it, and create the GitHub Release with `gh` using the changelog section as notes; call `pages.yml`; push `Package.swift`, `Sources/` and `Tests/` from `swift/` to `meshlink-swift` with the binary target's URL and checksum, wait for its CI, and tag the same version; run the smoke tests.
- [ ] Secrets are read only by jobs in the `release` environment. Default permissions are `contents: read`; each job widens only what it needs.
- [ ] `meshlink-swift` CI, kept in `swift/` and pushed once in the approval step below, builds and tests the package on the iOS simulator, writes a `swift-api-digester -dump-sdk` dump of `MeshLink`, and diagnoses it against the previous release's dump. With no earlier release, it stores the dump as the baseline.
- [ ] `tools/release/verify_release.py --version <X.Y.Z>` (standard library only) downloads every Maven artifact from Central, checks its PGP signature against the fingerprint in `docs/reference/release-infrastructure.md` and its published checksums, resolves `meshlink-swift` at the tag in a fresh SwiftPM consumer under `build/`, recomputes the ZIP checksum, compares it with `Package.swift` and the GitHub Release, and runs `gh attestation verify`. Its parsing has unit tests against fixture files.
- [ ] Dry run: `workflow_dispatch` with `dry_run: true`, and `pull_request` runs touching the release files, use a throwaway signing key and no environment. They upload nothing, create no release or tag, deploy nothing and push nothing. They render the `meshlink-swift` update and build a consumer against the local ZIP. A dry run on this PR passes.
- [ ] Every action is pinned to its latest stable release by SHA with a version comment, verified when written, and registered. `actionlint` passes.
- [ ] **Owner approval steps:** pushing the initial `meshlink-swift` content and running a dry run on `main` after merge each need the owner's explicit approval. No tag is pushed in this ticket.

## Proof commands

```sh
actionlint
./gradlew check
./gradlew publishToMavenLocal
tools/swiftpm-consumer/test.sh
python3 -m unittest discover tools/release
gh workflow run release.yml --ref main -f dry_run=true
```

New for the spine: `python3 tools/release/verify_release.py --version <X.Y.Z>` (used in ticket 64), its unit tests, and the dry-run dispatch.

## Docs to update

- `CHANGELOG.md`: the release entry for this workflow (the file exists since ticket 02).
- `docs/reference/compatibility.md` (new).
- `docs/how-to/release-meshlink.md` (new): tag, approve, verify, rerun steps 5–7 after a partial failure.
- [`docs/reference/dependencies.md`](../../../docs/reference/dependencies.md): release actions.
- [`PROJECT.md`](../../../PROJECT.md): the release workflow and `tools/release/`.

## Devices needed

None.
