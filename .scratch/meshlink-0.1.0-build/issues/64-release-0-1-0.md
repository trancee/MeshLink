# Release 0.1.0

Status: ready-for-human
Type: HITL
Blocked by: 59, 60, 63

## Behavior

MeshLink 0.1.0 is published to Maven Central and through `meshlink-swift`, with its API reference live on Pages and its release notes on GitHub. A consumer can fetch it, and the downloaded artifacts are verified from the consumer side before the release counts as done. Every irreversible step waits for the owner's explicit approval.

## Spec and ADR references

- [ADR 0019](../../../docs/adr/0019-release-versioning-and-api-compatibility.md#decision) decisions 1 (version), 6 (changelog), 7 (workflow steps) and 9 (support before 1.0), its "Risks" section (partial releases: only steps 5–7 rerun; a failure after Central upload is fixed by the next PATCH), and its [amendments](../../../docs/adr/0019-release-versioning-and-api-compatibility.md#amendments).
- [ADR 0011](../../../docs/adr/0011-modules-toggles-and-presets.md#amendments): coordinates, `meshlink-swift`, fresh SwiftPM consumer.
- [ADR 0017](../../../docs/adr/0017-testing-architecture.md#decision) decision 7: the release links its evidence records.
- The [0.1.0 map](../../meshlink-0.1.0/map.md) and the [spine](../spec.md) for the release scope.
- [Ticket 60](60-release-workflow.md) for `release.yml` and `tools/release/verify_release.py`; [ticket 59](59-pages-workflow.md) for Pages; [ticket 58](58-release-infrastructure.md) for the recorded signing fingerprint.
- `AGENTS.md`, "GIT/EXTERNAL": push, tag, publish and external-service changes need prior explicit owner approval.

## Acceptance criteria

- [ ] **Readiness:** `main` is green; the evidence records of tickets 62 and 63 are linked from `CHANGELOG.md`; a release dry run passes on the release commit; the owner confirms that the 0.1.0 scope from the map is met.
- [ ] **Release PR:** a PR renames "Unreleased" to `[0.1.0] - <date>`, sets the version to `0.1.0`, and updates `docs/reference/compatibility.md` if needed. The owner approves the merge.
- [ ] **Owner approval 1:** the owner approves pushing tag `v0.1.0` on the merged commit. The agent pushes it, or the owner does.
- [ ] **Owner approval 2:** the owner approves the `release` environment gate after the gate jobs pass.
- [ ] The workflow completes every step. After a partial failure, only steps 5–7 are rerun, as ADR 0019 "Risks" allows. A failure before Central upload needs a fix and a new dry run; a fault in published artifacts is fixed by 0.1.1, never a re-release.
- [ ] **Independent verification:** on a clean machine or fresh worktree, `python3 tools/release/verify_release.py --version 0.1.0` passes. It downloads every artifact from Maven Central, checks coordinates, POM identity, PGP signatures against the recorded fingerprint and published checksums. It resolves `meshlink-swift` at `0.1.0` through SwiftPM, matches the ZIP checksum to `Package.swift` and the GitHub Release, and verifies the attestation. A fresh Gradle consumer and a fresh SwiftPM consumer each build against the published 0.1.0 and run one reference scenario.
- [ ] **Pages live:** the site root and `/api/` return the 0.1.0 content.
- [ ] **Release notes:** the GitHub Release `v0.1.0` carries the changelog section, the evidence links, the background-unverified note and the density-is-simulator-evidence note.
- [ ] **State:** a PR updates [`PROJECT.md`](../../../PROJECT.md) (released 0.1.0, coordinates, `meshlink-swift` tag) and [`docs/README.md`](../../../docs/README.md). The release is done only after this PR merges.

## Proof commands

```sh
./gradlew check
gh workflow run release.yml --ref main -f dry_run=true
git tag -a v0.1.0 -m "MeshLink 0.1.0"
git push origin v0.1.0
python3 tools/release/verify_release.py --version 0.1.0
npx --yes markdownlint-cli2@0.24.1 "**/*.md"
lychee --offline .
```

The `git tag` and `git push` lines run only after owner approval 1. `verify_release.py` comes from ticket 60.

## Docs to update

- `CHANGELOG.md`: the `[0.1.0]` section.
- [`PROJECT.md`](../../../PROJECT.md) and [`docs/README.md`](../../../docs/README.md): release state.
- `docs/reference/compatibility.md`: if the protocol version changed.
- [`docs/agents/lessons.md`](../../../docs/agents/lessons.md): release root causes, if any.

## Devices needed

None for the release itself. The consumer reference scenarios run on the host and the iOS simulator.
