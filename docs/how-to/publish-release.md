# How to Publish a Release

## When to use this

You're ready to ship a new version of MeshLink to Maven Central and SPM.

## Steps

### 1. Update the version

In `gradle.properties`:

```properties
VERSION=0.2.0
```

### 2. Update the BCV baseline

```bash
./gradlew :meshlink:apiDump
git diff meshlink/api/
```

Review the API diff. If there are removals or signature changes, this is a binary-breaking change — bump the major version.

### 3. Run the full verification suite

```bash
./gradlew :meshlink:jvmTest :meshlink:koverVerify :meshlink:apiCheck :meshlink:detekt :meshlink:ktfmtCheck
```

All must pass.

### 4. Test local publishing

```bash
./gradlew publishToMavenLocal
scripts/verify-publish.sh
```

This verifies all 33 artifacts across 5 publication coordinates resolve correctly from `~/.m2`.

### 5. Build the XCFramework (macOS only)

```bash
./gradlew assembleMeshLinkReleaseXCFramework
```

Verify the output in `build/XCFrameworks/release/MeshLink.xcframework/`.

### 6. Commit and tag

```bash
git add -A
git commit -m "release: v0.2.0"
git tag v0.2.0
git push origin main --tags
```

### 7. CI handles the rest

`release.yml` triggers on the version tag and runs two jobs:

**publish-maven:**
- Runs `publishAllPublicationsToOSSRHRepository`
- Signs with `SIGNING_KEY` (GPG armored private key in GitHub Secrets)
- Publishes to OSSRH staging → auto-release to Maven Central

**publish-xcframework:**
- Builds XCFramework on macOS runner
- Zips and uploads as GitHub Release asset
- Computes SHA-256 checksum
- Updates `Package.swift` binaryTarget URL and checksum
- Commits the updated Package.swift

### 8. Verify resolution

After ~30 minutes (Maven Central sync):

```kotlin
// In a fresh project
implementation("ch.trancee:meshlink:0.2.0")
```

For SPM: Xcode should resolve the new version from the updated Package.swift.

## Required secrets (GitHub repository settings)

| Secret | Purpose |
|--------|---------|
| `SIGNING_KEY` | GPG armored private key for Maven signing |
| `SIGNING_PASSWORD` | GPG key passphrase |
| `OSSRH_USERNAME` | Sonatype OSSRH username |
| `OSSRH_PASSWORD` | Sonatype OSSRH password/token |

## Signing is optional locally

`publishToMavenLocal` works without any signing keys — signing is gated on the presence of the `SIGNING_KEY` environment variable. This means local development and testing never requires GPG setup.

## Rollback

If a release has issues:
1. Do NOT delete the Maven Central artifact (it's immutable)
2. Publish a patch release (0.2.1) with the fix
3. For SPM: update Package.swift to point to the new release asset
