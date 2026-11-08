# 0019: Release, versioning and API compatibility

Status: accepted (2026-11-08; ticket "What is the release, versioning and API-compatibility process?"). Version: 1.

## Context

- **Constitution:**
  - Q6: SemVer. While the major version is 0, a breaking change bumps the MINOR version.
  - Q7: public API removal needs at least one MINOR of deprecation. Immediate removal is allowed only for a security incident.
  - C6 and C7: released wire IDs are never reused, and a breaking wire change needs a MAJOR version, a migration and compatibility tests.
  - E1: reproducible releases.
  - G4: public changes carry docs, compatibility artifacts and their SemVer impact.
- **Artifacts ([ADR 0011](0011-modules-toggles-and-presets.md)):**
  - `meshlink` and `meshlink-testing` on Maven Central.
  - `MeshLinkKit.xcframework` as a ZIP release asset.
  - A separate `meshlink-swift` repository holding `Package.swift`, the Swift overlay and `MeshLinkTesting`.

  One release must update both repositories.
- **Wire versioning ([ADR 0012](0012-wire-format-and-codec.md)):** one protocol version byte, exchanged in the capability read and bound into the Noise prologue. Unknown routed frame types are relayed, and unknown non-critical TLVs are ignored.
- **Tools, verified 2026-11-08:**
  - **KGP `abiValidation`** is still Experimental: the docs carry the label, and it needs `@OptIn(ExperimentalAbiValidation)`. It writes JVM and klib dumps; `checkKotlinAbi` runs in `check`, and `updateKotlinAbi` rewrites the dumps.
  - **`swift-api-digester`** ships in the Xcode 16.4 toolchain, with `-dump-sdk` and `-diagnose-sdk`.
  - **vanniktech maven-publish 0.37.0** supports `publishToMavenCentral(automaticRelease = true)`.
  - **Maven Central** requires PGP signatures, and its releases are immutable.
  - **Actions:** `actions/attest-build-provenance` is at v4.2.2 and `actions/create-github-app-token` at v3.3.0. `gh` is preinstalled on GitHub runners.
- **Testing ([ADR 0017](0017-testing-architecture.md)):** every release needs a device evidence record.

## Decision

1. **Library version.** One SemVer version covers every artifact: `meshlink`, `meshlink-testing`, the XCFramework and the `meshlink-swift` tag.
   - The first release is 0.1.0.
   - 1.0.0 follows once the protocol spec is written, the release device evidence exists and the public API has been reviewed.
2. **Protocol version.**
   - The protocol version byte is independent of the library version. It changes only on an incompatible wire change.
   - Compatible additions use new frame types or non-critical TLVs, and keep the byte.
   - `docs/reference/compatibility.md` maps each library release to the protocol versions it speaks. It is created with the first release.
3. **Protocol transitions.**
   - A release that introduces protocol N+1 also speaks N, for at least one library MINOR.
   - The capability read selects the highest version both sides speak.
   - Each overlap has compatibility tests between N and N+1 nodes (C7).
4. **API compatibility checks.**
   - **Kotlin:** committed KGP ABI dumps, JVM and klib, plus `explicitApi()` in strict mode.
   - **Swift overlay:** `meshlink-swift` CI writes a `swift-api-digester -dump-sdk` dump and diagnoses it against the previous release's dump.
   - Any change to a dump needs a changelog entry stating its SemVer impact.
5. **Deprecation.**
   - Kotlin: `@Deprecated` at `WARNING` for at least one MINOR, then `ERROR`, then removal.
   - Swift: `@available(*, deprecated)` on the same schedule.
   - Immediate removal is allowed only for a security incident (Q7).
6. **Changelog.**
   - A hand-written `CHANGELOG.md` in the Keep a Changelog style. Each PR adds an entry under "Unreleased" with its SemVer impact.
   - A build-logic task, `checkChangelog`, wired into `check`, fails if an API dump changed without a changelog change.
   - No changelog tool and no new dependency.
7. **Release workflow.** Pushing a `vX.Y.Z` tag on `main` starts a GitHub Actions workflow on `macos-15`:
   1. Run every gate, and check that the changelog has a section for the version and that the device evidence record linked from it exists.
   2. Wait in a GitHub environment that requires the owner's manual approval. Nothing irreversible happens before this step.
   3. Sign the Maven artifacts with PGP, upload them to Maven Central, and release them automatically once Central has validated them (vanniktech `automaticRelease`).
   4. Build the XCFramework ZIP, compute its SwiftPM checksum, attest its provenance with `actions/attest-build-provenance`, and create the GitHub Release with `gh`, with the changelog section as notes.
   5. Publish the Dokka API reference to GitHub Pages (ADR 0006).
   6. Update `meshlink-swift`'s `Package.swift` with the URL and checksum, run its CI, and tag the same version there.
   7. Run the smoke tests: a fresh Gradle consumer resolves `meshlink` from Central and runs a reference scenario; a fresh SwiftPM consumer resolves the `meshlink-swift` tag and builds for the iOS simulator.
8. **Cross-repository credentials.**
   - A GitHub App installed only on `meshlink-swift`, with contents write access. The workflow takes a short-lived token from it with `actions/create-github-app-token`.
   - The PGP key, the Central token and the App's private key are secrets of the release environment only.
   - Creating the App and the secrets is part of ticket 30.
9. **Support before 1.0.** Only the latest release is supported, and fixes ship as new releases.

## Alternatives

- **Separate versions per artifact:** the artifacts are released together and depend on each other exactly; separate versions only add a matrix. Rejected.
- **Protocol version equal to the library version:** every library release would look like a wire change. Rejected.
- **A hard protocol cut:** mixed meshes would split while phones update. Rejected.
- **The legacy binary-compatibility-validator:** in maintenance mode, with experimental klib support (ADR 0005).
- **A generated changelog** (git-cliff, Conventional Commits): a new dependency, and commit subjects aren't release notes. Rejected.
- **Manual releases from a laptop:** neither reproducible nor attested (E1). Rejected.
- **A long-lived personal access token for `meshlink-swift`:** broader and longer-lived than an App token. Rejected.
- **Staging Central uploads for a manual release in the portal:** a second manual step after the approval gate adds nothing. Rejected.

## Risks

- **Experimental ABI validation:** it may change. The ADR 0005 hold tracks it.
- **Partial releases:** Maven Central is immutable. If a later step fails after its upload, the fix is the next PATCH, not a re-release. Steps 5–7 can be rerun.
- **Two-repository drift:** if `meshlink-swift` CI fails, Kotlin users have the release and Swift users don't. The smoke test reports it, and the step is rerun after a fix.
- **Protocol overlap cost:** speaking two versions doubles parts of the codec for one MINOR. This is accepted, because mixed meshes are the normal case during updates.
- **Free Apple team:** the release builds an unsigned XCFramework, so signing limits apply only to device runs.

## Migration

None: greenfield. The first release creates `CHANGELOG.md`, `docs/reference/compatibility.md`, the ABI dumps and the release workflow.

## Consequences

- **[ADR 0005](0005-toolchain-and-dependency-baseline.md):** the CI section gains the release workflow, and the register gains the two actions when the workflow is written.
- **[ADR 0011](0011-modules-toggles-and-presets.md):** the `meshlink-swift` update and its smoke test are defined here.
- **Ticket 30:** unblocked. It creates both repositories, the release environment, the GitHub App and the secrets, with approval.
