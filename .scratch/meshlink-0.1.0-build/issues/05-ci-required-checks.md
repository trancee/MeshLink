# Run the build in CI and make its jobs required on `main`

Status: ready-for-human
Type: HITL
Blocked by: 02, 03, 04

## Behavior

Every pull request runs the Gradle gates on Linux and the iOS simulator tests and SwiftPM consumer on macOS, and `main` accepts a merge only when those jobs and the existing docs and privacy jobs pass. A scheduled, non-required job builds and tests with the Xcode 27 preview. This completes milestone M0.

## Spec and ADR references

- [ADR 0021](../../../docs/adr/0021-change-workflow-and-merge-gates.md) decision 6 (ruleset on `main`: strict required checks, linear history, squash-only pull requests, no bypass) and decision 7 (ruleset changes need the owner's explicit approval).
- [ADR 0005](../../../docs/adr/0005-toolchain-and-dependency-baseline.md) "CI" (actions pinned to commit SHAs with version comments; required macOS jobs on `macos-26` with `/Applications/Xcode_26.4.1.app` selected explicitly; scheduled `xcode-27` job with Xcode 27.2; NDK 30.0.16248370 installed with `sdkmanager`) and the 2026-11-08 amendment (pull-request tier; no automatic retries).
- [ADR 0017](../../../docs/adr/0017-testing-architecture.md) decision 12 (pull requests target at most 15 min) and decision 13.
- [`AGENTS.md`](../../../AGENTS.md) "GIT/EXTERNAL" (latest stable official action release, SHA pin with matching version comment); Constitution G2, G3, E7.

## Acceptance criteria

- [ ] A workflow runs on `pull_request` and on pushes to `main`. An `ubuntu-24.04` job runs `./gradlew check` (Android host tests with Robolectric, Kover, detekt, Spotless, ABI, changelog and direction checks) and `./gradlew dokkaGenerate`.
- [ ] A `macos-26` job selects `/Applications/Xcode_26.4.1.app` explicitly, prints the selected Xcode version, and runs `./gradlew iosSimulatorArm64Test` and `tools/swiftpm-consumer/test.sh`.
- [ ] NDK 30.0.16248370 is installed explicitly with `sdkmanager` in every job that builds Android native code, and the job fails if it is missing.
- [ ] A scheduled, non-required job runs the macOS steps on the `xcode-27` preview image with Xcode 27.2 selected explicitly. The ticket verifies the runner label from GitHub's runner-images documentation.
- [ ] Every action is the latest stable official release, pinned to its full commit SHA with a matching version comment, and each new action has a row in the dependency register's CI tools section.
- [ ] No step retries a test. Gradle and the Robolectric android-all jars are cached between runs, keyed by the catalog and wrapper.
- [ ] `actionlint` reports nothing for every workflow.
- [ ] The PR's own run shows each required job under 15 min; the PR records the times.
- [ ] Owner-approved, after this PR merges: the `main` ruleset gains the new job names as strict required checks, alongside "Markdown lint, spelling and ADR headers", "Protocol spec" and "No private identifiers in any branch history", with linear history, squash-only pull requests with 0 approvals and no bypass actors. The agent proposes the exact `gh api` call; the owner approves it; the result is read back through the GitHub API and pasted into this ticket.
- [ ] A test PR with a deliberately failing Gradle check shows the merge blocked, then is closed.

## Proof commands

```sh
actionlint
./gradlew check
./gradlew iosSimulatorArm64Test
tools/swiftpm-consumer/test.sh
npx --yes markdownlint-cli2@0.24.1
typos
lychee --offline .
python3 tools/check_private.py
```

## Docs to update

- `PROJECT.md`: CI jobs, hosts and times; the proof matrix gains the authoritative CI host for each row; "Limitations" no longer says the docs checks are the only gates.
- `docs/reference/dependencies.md`: CI tools rows for every new action and for the runner images.
- `docs/adr/0021-change-workflow-and-merge-gates.md`: none, unless the required check names differ from decision 6's list; then a dated amendment.

## Devices needed

None. The owner must approve the ruleset change on GitHub.
