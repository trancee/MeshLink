# CI/CD Setup

GitHub Actions workflow templates for automated Maven Central publishing.

## Table of Contents

- [Maven: GitHub Actions workflow](#maven-github-actions-workflow)
- [Gradle: GitHub Actions workflow](#gradle-github-actions-workflow)
- [Required secrets](#required-secrets)
- [GPG key export for CI](#gpg-key-export-for-ci)
- [Release strategies](#release-strategies)

## Maven: GitHub Actions workflow

This workflow publishes on every GitHub Release:

```yaml
name: Publish to Maven Central

on:
  release:
    types: [published]

jobs:
  publish:
    runs-on: ubuntu-latest

    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
          server-id: central
          server-username: MAVEN_CENTRAL_USERNAME
          server-password: MAVEN_CENTRAL_PASSWORD
          gpg-private-key: ${{ secrets.GPG_PRIVATE_KEY }}
          gpg-passphrase: GPG_PASSPHRASE

      - name: Publish to Maven Central
        run: mvn deploy -Dgpg.passphrase="${GPG_PASSPHRASE}"
        env:
          MAVEN_CENTRAL_USERNAME: ${{ secrets.MAVEN_CENTRAL_USERNAME }}
          MAVEN_CENTRAL_PASSWORD: ${{ secrets.MAVEN_CENTRAL_PASSWORD }}
          GPG_PASSPHRASE: ${{ secrets.GPG_PASSPHRASE }}
```

The `setup-java` action with `server-id: central` automatically generates a `settings.xml` with the right credentials.

### Version from release tag

If your release tag matches the artifact version (e.g., tag `v1.2.3` for version `1.2.3`), you can set the version dynamically:

```yaml
      - name: Set version from tag
        run: mvn versions:set -DnewVersion=${GITHUB_REF_NAME#v}

      - name: Publish to Maven Central
        run: mvn deploy -Dgpg.passphrase="${GPG_PASSPHRASE}"
        env:
          MAVEN_CENTRAL_USERNAME: ${{ secrets.MAVEN_CENTRAL_USERNAME }}
          MAVEN_CENTRAL_PASSWORD: ${{ secrets.MAVEN_CENTRAL_PASSWORD }}
          GPG_PASSPHRASE: ${{ secrets.GPG_PASSPHRASE }}
```

## Gradle: GitHub Actions workflow

Using `vanniktech/gradle-maven-publish-plugin`:

```yaml
name: Publish to Maven Central

on:
  release:
    types: [published]

jobs:
  publish:
    runs-on: ubuntu-latest

    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'

      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v4

      - name: Publish to Maven Central
        run: ./gradlew publish
        env:
          ORG_GRADLE_PROJECT_mavenCentralUsername: ${{ secrets.MAVEN_CENTRAL_USERNAME }}
          ORG_GRADLE_PROJECT_mavenCentralPassword: ${{ secrets.MAVEN_CENTRAL_PASSWORD }}
          ORG_GRADLE_PROJECT_signingInMemoryKeyId: ${{ secrets.GPG_KEY_ID }}
          ORG_GRADLE_PROJECT_signingInMemoryKey: ${{ secrets.GPG_PRIVATE_KEY }}
          ORG_GRADLE_PROJECT_signingInMemoryKeyPassword: ${{ secrets.GPG_PASSPHRASE }}
```

Using `gradle-nexus/publish-plugin`:

```yaml
      - name: Publish to Maven Central
        run: ./gradlew publishToSonatype closeAndReleaseSonatypeStagingRepository
        env:
          MAVEN_CENTRAL_USERNAME: ${{ secrets.MAVEN_CENTRAL_USERNAME }}
          MAVEN_CENTRAL_PASSWORD: ${{ secrets.MAVEN_CENTRAL_PASSWORD }}
          ORG_GRADLE_PROJECT_signingKey: ${{ secrets.GPG_PRIVATE_KEY }}
          ORG_GRADLE_PROJECT_signingPassword: ${{ secrets.GPG_PASSPHRASE }}
```

Using custom integration (no third-party plugins):

```yaml
name: Publish to Maven Central

on:
  release:
    types: [published]

jobs:
  publish:
    runs-on: ubuntu-latest

    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'

      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v4

      - name: Publish to Maven Central
        run: ./gradlew publishToMavenCentral
        env:
          MAVEN_CENTRAL_USERNAME: ${{ secrets.MAVEN_CENTRAL_USERNAME }}
          MAVEN_CENTRAL_PASSWORD: ${{ secrets.MAVEN_CENTRAL_PASSWORD }}
          MAVEN_CENTRAL_AUTO_PUBLISH: 'true'
          ORG_GRADLE_PROJECT_signingKey: ${{ secrets.GPG_PRIVATE_KEY }}
          ORG_GRADLE_PROJECT_signingPassword: ${{ secrets.GPG_PASSPHRASE }}
```

This uses the custom `publishToMavenCentral` task defined in `references/custom-gradle-integration.md`. The task handles bundling, uploading, and waiting for validation internally — no community plugins needed.

## Required secrets

Set these in your GitHub repo → Settings → Secrets and variables → Actions:

| Secret | Value | How to get it |
|--------|-------|---------------|
| `MAVEN_CENTRAL_USERNAME` | Token username | central.sonatype.com → User Token → Generate |
| `MAVEN_CENTRAL_PASSWORD` | Token password | Same modal as above — save it immediately |
| `GPG_PRIVATE_KEY` | ASCII-armored private key | `gpg --armor --export-secret-keys <KEY_ID>` |
| `GPG_PASSPHRASE` | GPG key passphrase | The passphrase you set when generating the key |
| `GPG_KEY_ID` | Last 8 chars of key ID | `gpg --list-keys --keyid-format short` |

## GPG key export for CI

Export the private key in ASCII armor format:

```bash
gpg --armor --export-secret-keys <KEY_ID>
```

This outputs a block starting with `-----BEGIN PGP PRIVATE KEY BLOCK-----`. Copy the entire block (including the markers) and paste it as the `GPG_PRIVATE_KEY` secret.

For Gradle's in-memory signing, the key format depends on which plugin you use:

- **vanniktech plugin**: Pass the ASCII-armored key directly via `signingInMemoryKey` — no base64 encoding needed
- **Gradle built-in signing / custom integration**: Pass the ASCII-armored key via `ORG_GRADLE_PROJECT_signingKey` — also no base64
- **Some older plugins**: May expect base64-encoded armor. Export with: `gpg --armor --export-secret-keys <KEY_ID> | base64 -w 0` (the `-w 0` prevents line wrapping)

## Release strategies

### Tag-triggered release

```yaml
on:
  push:
    tags:
      - 'v*'
```

Push a tag like `v1.2.3` to trigger a release.

### GitHub Release-triggered (recommended)

```yaml
on:
  release:
    types: [published]
```

Create a release through the GitHub UI or CLI. This gives you a place for release notes and a clear audit trail.

### Manual dispatch

```yaml
on:
  workflow_dispatch:
    inputs:
      version:
        description: 'Version to publish (e.g., 1.2.3)'
        required: true
```

Useful for projects where release timing is carefully controlled.

### Snapshot publishing on main

```yaml
on:
  push:
    branches: [main]

jobs:
  publish-snapshot:
    runs-on: ubuntu-latest
    steps:
      # ... same setup steps ...
      - name: Publish snapshot
        run: mvn deploy -Dgpg.passphrase="${GPG_PASSPHRASE}"
        env:
          # ... same env vars ...
```

This requires your version to end in `-SNAPSHOT` and snapshot publishing to be enabled for your namespace on the portal.
