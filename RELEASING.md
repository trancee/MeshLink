# Releasing MeshLink

This runbook is for maintainers preparing a MeshLink release.

It covers versioning, verification, changelog updates, tagging, and the current
release-preparation checkpoints.

## Current release shape

Today, MeshLink is still source-distributed from this repository checkout.

The intended first public release shape is:

- a published `:meshlink` artifact for Gradle consumers
- the existing generated Apple framework path for iOS Xcode hosts
- no Swift Package Manager package yet
- no CocoaPods pod yet

Artifact publication is not yet wired into this repository. Until the
publishing and signing configuration lands, this runbook covers the release
preparation path rather than the final publish step.

## Version source of truth

Repository versioning is controlled by `VERSION_NAME` in `gradle.properties`.

Use these rules:

- development work stays on a `-SNAPSHOT` version
- a public release removes the `-SNAPSHOT` suffix
- the first post-release commit bumps to the next snapshot version

Examples:

- `0.1.0-SNAPSHOT` during preparation
- `0.1.0` for the tagged release
- `0.1.1-SNAPSHOT` after the release branch or mainline moves forward

## First public release checklist

Before cutting `0.1.0`, confirm all of the following:

- the project license is final and replaces or confirms the conservative pre-release rights notice in `LICENSE`
- `CHANGELOG.md` describes the release in reader-facing terms
- `README.md` and the install docs match the current distribution shape
- `SECURITY.md` and this runbook are still accurate
- the repository verification bundle passes from a clean checkout
- the release tag name and next snapshot version are decided in advance
- Maven Central publishing, signing, and consumer smoke validation are ready if the release will publish an artifact

## Verification bundle

Run this bundle after the last change that will ship in the release:

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

If the release change touches workflow files, also validate YAML before tagging.

## Tagging flow

1. Set `VERSION_NAME` in `gradle.properties` to the release version.
2. Update `CHANGELOG.md` and any release-status docs that changed during the cut.
3. Run the verification bundle.
4. Commit the release preparation changes with a Conventional Commit.
5. Create the release tag.
6. Publish artifacts once repository publishing support exists.
7. Bump `VERSION_NAME` to the next snapshot version.
8. Commit the post-release bump.

## Follow-up after publishing support lands

Once `:meshlink` publishing is implemented, extend this runbook with:

- required credentials and signing-key setup
- the exact publish command or workflow
- consumer smoke validation against the published artifact
- rollback or failed-release handling guidance
