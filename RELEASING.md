# Releasing MeshLink

This runbook is for maintainers preparing a MeshLink release.

It covers the current `release-please`-based release flow, version ownership,
verification expectations, and the remaining steps that still sit outside the
repository automation.

## Current release shape

Today, MeshLink is still source-distributed from this repository checkout.

The intended first public release shape is:

- a published `:meshlink` artifact for Gradle consumers
- the existing generated Apple framework path for iOS Xcode hosts
- root-level Swift Package Manager support for both checkout and release binary targets

Artifact publication is partially wired into this repository. The release
workflow now packages the SwiftPM XCFramework zip and checksum on published
GitHub releases, while the `:meshlink` module now defines Maven Central
publishing, signing, and documentation-jar wiring. The remaining follow-up work
is workflow automation and consumer smoke validation.

## Release automation

MeshLink now prepares releases with `release-please` in manifest mode.

Repository files that define this flow:

- `.github/workflows/release-please.yml`
- `release-please-config.json`
- `.release-please-manifest.json`
- `gradle.properties`

When `release-please` creates a release, the workflow now:

- publishes `:meshlink` to Maven Central with the release tag checkout
- runs a consumer smoke compile against the released coordinates

The release-please workflow runs on pushes to `main` and is also available
through manual dispatch.

## Version source of truth

Repository versioning is controlled by `VERSION_NAME` in `gradle.properties`.

`release-please` updates that value through the annotated version block in the
same file.

Normal version posture remains:

- development work stays on a `-SNAPSHOT` version
- a release PR proposes the plain release version
- after the release is created, a follow-up snapshot PR proposes the next
  `-SNAPSHOT` version

Examples:

- `0.1.0-SNAPSHOT` during preparation
- `0.1.0` in the release PR and tagged release
- `0.1.1-SNAPSHOT` in the post-release snapshot PR

## How the release flow works

### 1. Merge normal releasable PRs to `main`

Maintainers keep merging normal feature and fix PRs into `main`.

`release-please` collects releasable commits since the last release and opens
or updates one cumulative release PR. A release can therefore combine multiple
already-merged PRs.

### 2. Review the cumulative release PR

That release PR is the thing you merge when you want to ship.

Review at least:

- proposed version bump
- generated changelog text in `CHANGELOG.md`
- any docs or release-status wording changed by the release PR

### 3. Merge the release PR

When the release PR is merged, `release-please` creates:

- the git tag
- the GitHub release

For this repository, tags are expected to look like `vX.Y.Z`.

### 4. Merge the snapshot PR

Because this repository uses the `java` release-please strategy, the workflow
opens a follow-up snapshot PR after each release. Merge that PR promptly so
`main` returns to the normal development posture.

## Releasable commit guidance

`release-please` decides version bumps and changelog entries from the merged
commit history.

For the configured Java strategy, treat these commit types as release-driving:

- `feat`
- `fix`
- `deps`
- `docs`

Treat these as normally non-releasable unless you intentionally want them to
change release notes or release cadence:

- `chore`
- `build`

If you use squash merges, the final squash commit message must still follow the
Conventional Commit format.

## Token posture

The release workflow currently uses the repository's default `GITHUB_TOKEN`.
That is the right starting point for normal `release-please` operation.

A non-default token is only needed if you later want events created by
`release-please` itself to trigger additional workflows. In that case, prefer a
GitHub App token. Use a fine-grained PAT only as a fallback if an App-based
setup is not available.

The reason for that escalation is GitHub Actions behavior, not a
`release-please` requirement: events created with `GITHUB_TOKEN` do not trigger
follow-on workflows.

## First public release checklist

Before cutting `0.1.0`, confirm all of the following:

- the project license in `LICENSE` still matches the intended public distribution posture
- `CHANGELOG.md` describes the release in reader-facing terms
- `README.md` and the install docs match the current distribution shape
- `SECURITY.md` and this runbook are still accurate
- the repository verification bundle passes from a clean checkout
- the release tag name and next snapshot version are decided in advance
- Maven Central publishing, signing, and consumer smoke validation are wired and pass in a release dry-run

The repository pins `bootstrap-sha` in `release-please-config.json` to the last
pre-release-readiness commit on `main` so the first live release PR does not
sweep the full historical pre-automation commit range. The first public release
PR still needs maintainer curation before merge.

## Release workflow dry-run and PR verification

The release workflow supports a manual dry-run that publishes to Maven Local
and runs the consumer smoke check without creating a release:

```bash
gh workflow run release-please.yml --ref main -f dry_run=true
```

Because the repository currently uses the default `GITHUB_TOKEN` for
`release-please`, release-please-created PRs do not automatically trigger the
normal PR workflow.

Before merging a release PR, maintainers should do one of the following:

- run the verification bundle locally from the release PR branch, or
- run the `ci` workflow manually with `workflow_dispatch` against the release
  PR branch

This is an operational constraint of GitHub Actions event propagation, not a
special requirement from `release-please` itself.

## Verification bundle

Run this bundle after the last non-generated change that will ship in the
release:

```bash
./gradlew \
  :meshlink:allTests \
  :meshlink:detekt \
  :meshlink:apiCheck \
  :meshlink:koverVerify \
  verifyDocs \
  checkAgp9Invariants \
  --console=plain
```

If the release change touches benchmark-sensitive MeshLink code, also run:

```bash
./gradlew verifyJvmSmokeBenchmarks --console=plain
```

If the release change touches workflow files, also validate YAML before merging
or tagging.

## What is still manual

These items still sit outside the automated release-please flow:

- provisioning the GitHub Actions secrets used by the publish job: `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`, `SIGNING_KEY`, `SIGNING_KEY_ID`, and `SIGNING_KEY_PASSWORD`
- rollback guidance for failed publishes

## Follow-up after publishing support lands

Now that `:meshlink` publishing is wired in, keep this runbook updated if any of the following change:

- the exact publish command or workflow
- required credentials and signing-key setup
- rollback or failed-release handling guidance
