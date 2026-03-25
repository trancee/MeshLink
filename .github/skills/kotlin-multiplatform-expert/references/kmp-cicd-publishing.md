# KMP CI/CD and Publishing Reference

Detailed CI/CD configuration, Maven Central publishing setup, and distribution patterns for Kotlin Multiplatform projects.

## Table of Contents
- [GitHub Actions CI patterns](#github-actions-ci-patterns)
- [Maven Central publishing setup](#maven-central-publishing-setup)
- [XCFramework distribution](#xcframework-distribution)
- [Version management](#version-management)

---

## GitHub Actions CI patterns

### Build matrix for multi-platform testing

For libraries that need to verify behavior on multiple OS hosts:

```yaml
jobs:
  test:
    strategy:
      matrix:
        os: [macos-latest, ubuntu-latest]
    runs-on: ${{ matrix.os }}
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: 'zulu', java-version: '21' }
      - uses: gradle/actions/setup-gradle@v4
      - name: Cache Kotlin/Native
        uses: actions/cache@v4
        with:
          path: ~/.konan
          key: konan-${{ runner.os }}-${{ hashFiles('**/*.gradle.kts') }}
      - name: Run tests
        run: ./gradlew allTests --parallel
```

Note: iOS-related tasks (iosSimulatorArm64Test, framework generation) only work on macOS runners. Linux runners can handle JVM/Android and JS/WASM targets.

### Caching strategies

```yaml
# Kotlin/Native compiler + platform libs (~1GB)
- uses: actions/cache@v4
  with:
    path: ~/.konan
    key: konan-${{ runner.os }}-${{ hashFiles('**/*.gradle.kts') }}
    restore-keys: konan-${{ runner.os }}-

# Gradle wrapper + dependencies (handled by setup-gradle)
- uses: gradle/actions/setup-gradle@v4
```

The `gradle/actions/setup-gradle@v4` action automatically caches:
- Gradle wrapper distribution
- Gradle dependency cache
- Build cache

### Separating Android and iOS jobs

For faster CI, split Android and iOS testing into parallel jobs:

```yaml
jobs:
  android-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: 'zulu', java-version: '21' }
      - uses: gradle/actions/setup-gradle@v4
      - run: ./gradlew testDebugUnitTest

  ios-tests:
    runs-on: macos-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: 'zulu', java-version: '21' }
      - uses: gradle/actions/setup-gradle@v4
      - uses: actions/cache@v4
        with:
          path: ~/.konan
          key: konan-macos-${{ hashFiles('**/*.gradle.kts') }}
      - run: ./gradlew iosSimulatorArm64Test
```

Android unit tests can run on cheaper Linux runners since they execute on the local JVM.

### Common CI Gradle flags

```bash
# Recommended CI flags
./gradlew allTests \
  --parallel \
  --build-cache \
  --no-daemon \
  --stacktrace \
  -Pkotlin.native.ignoreDisabledTargets=true
```

- `--parallel`: multi-project parallel execution
- `--build-cache`: reuse outputs from previous builds
- `--no-daemon`: avoids daemon overhead in ephemeral CI environments
- `-Pkotlin.native.ignoreDisabledTargets=true`: allows building on hosts that don't support all declared targets (e.g., skipping iOS targets on Linux)

---

## Maven Central publishing setup

### Prerequisites

1. **Maven Central account** — register at https://central.sonatype.com/
2. **Verified namespace** — e.g., `io.github.yourusername` (verified via GitHub repo) or `com.yourdomain` (verified via DNS TXT record)
3. **GPG key pair** for artifact signing
4. **User token** for CI authentication

### Namespace verification

For GitHub-based namespaces:
1. Register `io.github.<username>` at Maven Central → Namespaces
2. Create a public GitHub repo named with the verification key
3. Click "Verify Namespace" on Maven Central
4. Delete the verification repo after verification

### GPG key generation

```bash
# Option 1: Using Gradle (Kotlin-specific, outputs to build/pgp/)
./gradlew -Psigning.password=your-password generatePgpKeys --name "Your Name <you@example.com>"

# Option 2: Using gpg (standard approach)
gpg --full-generate-key  # follow prompts, choose defaults

# List keys to get key ID
gpg --list-keys
# Output shows: F175482952A225BFC4A07A715EE6B5F76620B385CE

# Upload public key to keyserver
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>

# Export private key for CI
gpg --armor --export-secret-keys <KEY_ID> > key.gpg
```

### GitHub repository secrets

Add these secrets to your repo (Settings → Secrets → Actions):

| Secret name | Value |
|---|---|
| `MAVEN_CENTRAL_USERNAME` | From Maven Central "Generate User Token" |
| `MAVEN_CENTRAL_PASSWORD` | From Maven Central "Generate User Token" |
| `SIGNING_KEY_ID` | Last 8 chars of your GPG key ID |
| `SIGNING_PASSWORD` | Your GPG key passphrase |
| `GPG_KEY_CONTENTS` | Full contents of `key.gpg` file |

### vanniktech/gradle-maven-publish-plugin

The recommended plugin for KMP Maven Central publishing:

```kotlin
// shared/build.gradle.kts
plugins {
    kotlin("multiplatform")
    id("com.vanniktech.maven.publish") version "0.36.0"
}

mavenPublishing {
    publishToMavenCentral()          // or publishAndReleaseToMavenCentral()
    signAllPublications()

    coordinates(group.toString(), "my-library", version.toString())

    pom {
        name = "My KMP Library"
        description = "Cross-platform shared module"
        inceptionYear = "2026"
        url = "https://github.com/you/my-library"

        licenses {
            license {
                name = "Apache-2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0"
            }
        }

        developers {
            developer {
                id = "yourid"
                name = "Your Name"
                url = "https://github.com/yourid"
            }
        }

        scm {
            url = "https://github.com/you/my-library"
            connection = "scm:git:git://github.com/you/my-library.git"
            developerConnection = "scm:git:ssh://git@github.com/you/my-library.git"
        }
    }
}
```

### Local verification before publishing

```bash
# Check signing configuration
./gradlew checkSigningConfiguration

# Check POM meets Maven Central requirements
./gradlew checkPomFileForMavenPublication

# Publish to local Maven for testing
./gradlew publishToMavenLocal
# Artifacts appear in ~/.m2/repository/
```

### Publishing workflow

```yaml
# .github/workflows/publish.yml
name: Publish to Maven Central
on:
  release:
    types: [released, prereleased]

jobs:
  publish:
    runs-on: macos-latest  # Required for iOS targets
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: 'zulu'
          java-version: '21'
      - uses: gradle/actions/setup-gradle@v4
      - uses: actions/cache@v4
        with:
          path: ~/.konan
          key: konan-macos-${{ hashFiles('**/*.gradle.kts') }}

      - name: Publish to Maven Central
        run: ./gradlew publishAndReleaseToMavenCentral --no-configuration-cache
        env:
          ORG_GRADLE_PROJECT_mavenCentralUsername: ${{ secrets.MAVEN_CENTRAL_USERNAME }}
          ORG_GRADLE_PROJECT_mavenCentralPassword: ${{ secrets.MAVEN_CENTRAL_PASSWORD }}
          ORG_GRADLE_PROJECT_signingInMemoryKeyId: ${{ secrets.SIGNING_KEY_ID }}
          ORG_GRADLE_PROJECT_signingInMemoryKeyPassword: ${{ secrets.SIGNING_PASSWORD }}
          ORG_GRADLE_PROJECT_signingInMemoryKey: ${{ secrets.GPG_KEY_CONTENTS }}
```

- `publishToMavenCentral` — uploads + validates, but requires manual release via Central Portal UI
- `publishAndReleaseToMavenCentral` — uploads + validates + auto-releases

---

## XCFramework distribution

### Generating XCFrameworks in CI

```yaml
- name: Build XCFramework
  run: ./gradlew assembleReleaseXCFramework

- name: Upload XCFramework artifact
  uses: actions/upload-artifact@v4
  with:
    name: shared.xcframework
    path: shared/build/XCFrameworks/release/
```

### Distributing via GitHub Releases

```yaml
- name: Zip XCFramework
  run: |
    cd shared/build/XCFrameworks/release/
    zip -r shared.xcframework.zip shared.xcframework

- name: Attach to release
  uses: softprops/action-gh-release@v2
  with:
    files: shared/build/XCFrameworks/release/shared.xcframework.zip
```

### Swift Package Manager integration

After generating the XCFramework, create a `Package.swift` for distribution:

```swift
// Package.swift
let package = Package(
    name: "SharedKit",
    platforms: [.iOS(.v15)],
    products: [
        .library(name: "SharedKit", targets: ["shared"])
    ],
    targets: [
        .binaryTarget(
            name: "shared",
            url: "https://github.com/you/my-lib/releases/download/1.0.0/shared.xcframework.zip",
            checksum: "<sha256-checksum>"
        )
    ]
)
```

Generate the checksum: `swift package compute-checksum shared.xcframework.zip`

---

## Version management

### Gradle version catalogs for KMP

```toml
# gradle/libs.versions.toml
[versions]
kotlin = "2.3.20"
agp = "8.7.3"
coroutines = "1.10.2"
ktor = "3.1.1"

[libraries]
kotlin-test = { module = "org.jetbrains.kotlin:kotlin-test", version.ref = "kotlin" }
coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }

[plugins]
kotlinMultiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
androidLibrary = { id = "com.android.library", version.ref = "agp" }
```

### Kotlin/Gradle/AGP compatibility

KMP is sensitive to version compatibility. Key constraints:
- Kotlin version determines the Kotlin/Native compiler version (bundled)
- AGP version must be compatible with both the Gradle version and the Kotlin version
- Check https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html for breaking changes
- Check https://developer.android.com/build/releases/gradle-plugin for AGP/Gradle compatibility
