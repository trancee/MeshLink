---
name: maven-central-publisher
description: "Publish Java, Kotlin, or JVM libraries to Maven Central via the Sonatype Central Publisher Portal. Use this skill whenever users want to publish a JAR/library to Maven Central, deploy artifacts to Sonatype, set up the central-publishing-maven-plugin, configure Gradle for Maven Central publishing, migrate from OSSRH (oss.sonatype.org) to the Central Portal, fix Maven Central validation failures, set up GPG signing for artifact publishing, automate releases with GitHub Actions, publish SNAPSHOT versions, verify a Sonatype namespace, or troubleshoot 401/credential issues with the Central Portal. Also trigger when users say things like 'make my library available as a dependency', 'publish my JAR to a public repo', 'get my library on Maven Central', or ask about POM metadata requirements for Central. Covers Maven and Gradle (including a zero-dependency custom Portal API integration), GPG key management, CI/CD workflows, and the Publisher Portal REST API."
---

# Maven Central Publisher

Help the user publish JVM artifacts (Java, Kotlin, Scala, etc.) to Maven Central through the Sonatype Central Publisher Portal. This skill covers the entire process from initial setup to automated CI/CD releases.

## Assess where the user is

Publishing to Maven Central involves several stages. Before diving in, figure out what the user has already done:

1. **Namespace**: Do they have a verified namespace on central.sonatype.com?
2. **GPG**: Do they have a GPG key generated and distributed to a key server?
3. **Portal token**: Do they have a user token from the Central Publisher Portal?
4. **Build tool**: Are they using Maven or Gradle?
5. **POM metadata**: Does their POM/build config include all required metadata (name, description, URL, licenses, developers, SCM)?
6. **Signing**: Is their build configured to sign artifacts with GPG?
7. **Publishing plugin**: Is the build configured with the appropriate publishing plugin?
8. **CI/CD**: Do they want automated publishing from CI?

Ask about these early. Many users will come in having done some steps but not others — or they'll have a build that "almost works" but fails validation. Meet them where they are.

## The publishing pipeline at a glance

Here's what needs to happen, end to end:

1. Create an account at central.sonatype.com
2. Verify a namespace (your groupId)
3. Generate a GPG key and publish it to a key server
4. Generate a portal user token
5. Configure your build tool (Maven or Gradle) with:
   - All required POM metadata
   - Source and Javadoc JAR generation
   - GPG signing
   - The publishing plugin
6. Run the deploy command
7. Verify and publish the deployment on the portal (or configure auto-publish)

Steps 1–4 are one-time setup. Steps 5–7 happen each release.

## Stage 1: One-time setup

### Namespace verification

The user's `groupId` must correspond to a verified namespace on central.sonatype.com. The two common patterns:

- **GitHub-based**: `io.github.<username>` — verified by creating a temporary public repo named after a verification key
- **Domain-based**: reverse domain like `com.example` — verified via DNS TXT record

**IMPORTANT: Watch for the `com.github` trap.** Users frequently try to use `com.github.<username>` as their groupId. This will NOT work — they don't own the `github.com` domain. The correct namespace for GitHub users is `io.github.<username>`. If the user mentions `com.github.*`, proactively correct them and explain why.

Point the user to central.sonatype.com → Namespaces and walk them through whichever flow applies. This is a manual step that happens in the browser.

### GPG key setup

Every artifact file must be signed with GPG. Walk the user through this:

```bash
# Generate a key pair
gpg --gen-key

# List keys to find the key ID (the 40-character hex string)
gpg --list-keys

# Distribute the public key to a key server
# Central supports: keyserver.ubuntu.com, keys.openpgp.org, pgp.mit.edu
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
```

Important points to convey:
- The passphrase protects the private key — it should be strong and stored securely
- For CI/CD, the private key will need to be exported and stored as a secret
- If they have sub-keys with signing (`S`) usage, Maven may use the sub-key instead of the primary key, which can cause verification failures. Read `references/gpg-details.md` if this comes up.

### Portal user token

Tokens are generated at central.sonatype.com/usertoken. The user clicks "Generate User Token", sets a name and expiration, and saves the resulting username/password pair. These credentials cannot be retrieved after closing the modal — emphasize this.

## Stage 2: Build configuration

This is where Maven and Gradle diverge significantly. Determine which build tool the user has and consult the appropriate reference:

- **Maven**: Read `references/maven-setup.md` for complete configuration details
- **Gradle with a plugin**: Read `references/gradle-setup.md` for community plugin options and configuration
- **Gradle without plugins (custom integration)**: Read `references/custom-gradle-integration.md` for a zero-dependency approach using Gradle's built-in `maven-publish` + `signing` and custom tasks that call the Portal API directly. This approach mirrors what `vanniktech/gradle-maven-publish-plugin` does under the hood.

When the user asks about Gradle, present all three options: community plugin (easiest), custom integration (full control, no dependencies), or OSSRH staging API (migration path). Ask which they prefer if it's not clear from context.

### POM requirements (both build tools)

Regardless of build tool, the deployed POM must include these elements or the deployment will fail validation:

- **GAV coordinates**: `groupId`, `artifactId`, `version` (version must not end in `-SNAPSHOT` for releases)
- **Project info**: `name`, `description`, `url`
- **License**: At least one `<license>` with `name` and `url`
- **Developers**: At least one `<developer>` with `name`
- **SCM**: `connection`, `developerConnection`, and `url`

Also required alongside the POM:
- **Sources JAR**: `<artifactId>-<version>-sources.jar`
- **Javadoc JAR**: `<artifactId>-<version>-javadoc.jar`
- **GPG signatures**: `.asc` file for every artifact file
- **Checksums**: `.md5` and `.sha1` for every artifact file (many plugins generate these automatically)

When the user's POM is missing required elements, tell them exactly what to add and where. Don't just say "add SCM info" — show the XML block with their actual repository URL filled in.

## Stage 3: Deploy and publish

### Maven

With the `central-publishing-maven-plugin` configured:

```bash
mvn deploy
```

This stages, bundles, uploads, and waits for validation. With `autoPublish: true`, it also publishes to Maven Central automatically.

### Gradle

Depends on the chosen approach:
- **Custom integration (no plugins)**: `./gradlew publishToMavenCentral`
- `vanniktech/gradle-maven-publish-plugin`: `./gradlew publish`
- `gradle-nexus/publish-plugin`: `./gradlew publishToSonatype closeAndReleaseSonatypeStagingRepository`
- JReleaser: `./gradlew jreleaserFullRelease`

### What happens after upload

1. The bundle is uploaded to the portal
2. Validation runs (checks metadata, signatures, checksums, sources, javadoc)
3. If validation passes and auto-publish is off, the user clicks "Publish" on central.sonatype.com
4. The artifact syncs to repo1.maven.org (usually within 30 minutes)

## Stage 4: CI/CD automation

Read `references/ci-cd.md` for GitHub Actions workflow templates.

The key secrets needed:
- `MAVEN_CENTRAL_USERNAME` / `MAVEN_CENTRAL_PASSWORD` (the portal token pair)
- `GPG_PRIVATE_KEY` (exported via `gpg --armor --export-secret-keys <KEY_ID>`)
- `GPG_PASSPHRASE`

## Snapshot publishing

For pre-release testing, the user can publish `-SNAPSHOT` versions:
- Must enable snapshots for their namespace on central.sonatype.com → Namespaces
- Snapshot versions don't go through validation
- Snapshots are cleaned up after 90 days
- URL: `https://central.sonatype.com/repository/maven-snapshots/`

The `central-publishing-maven-plugin` (≥0.7.0) handles snapshot publishing automatically when the version ends in `-SNAPSHOT`.

## Troubleshooting validation failures

When a deployment fails validation, the portal shows which checks failed. Common failures:

| Failure | Cause | Fix |
|---------|-------|-----|
| Missing POM elements | `name`, `description`, `url`, `licenses`, `developers`, or `scm` missing | Add the missing elements to the POM |
| Missing sources JAR | No `-sources.jar` in the bundle | Configure `maven-source-plugin` or Gradle `withSourcesJar()` |
| Missing javadoc JAR | No `-javadoc.jar` in the bundle | Configure `maven-javadoc-plugin` or Gradle `withJavadocJar()` |
| Invalid signatures | GPG signature can't be verified | Ensure public key is on a supported key server; check for sub-key issues (see `references/gpg-details.md`) |
| Missing checksums | No `.md5` / `.sha1` files | Let the publishing plugin generate them (default behavior). For custom integration, ensure `generateChecksums()` runs. |
| SNAPSHOT version | Version ends in `-SNAPSHOT` | Use a release version for Maven Central |
| Duplicate coordinates | This GAV already exists on Central | Bump the version — published artifacts are immutable |
| 401 Unauthorized | Wrong or expired credentials | Regenerate portal token at central.sonatype.com/usertoken. Old OSSRH tokens don't work. |
| Wrong namespace | Using `com.github.*` instead of `io.github.*` | GitHub-based namespaces must use `io.github.<username>` |
| Bundle too large | Zip exceeds 1GB | Split into multiple deployments or reduce artifact size |

When helping debug a failed deployment, ask the user to share the exact validation error from the portal. The error messages are specific and map directly to fixes.

## Common mistakes to catch

These are errors that users frequently make. Proactively warn about them:

1. **`com.github.*` namespace** — Must be `io.github.*` for GitHub users. This is the #1 mistake.
2. **Missing `<scm>` in POM** — Often forgotten because it's metadata, not a build element. It goes at the `<project>` level, not inside `<build>`.
3. **No javadoc JAR** — Users add `maven-source-plugin` but forget `maven-javadoc-plugin`. Both are required.
4. **Old OSSRH credentials** — Users migrating from OSSRH try to use their old Sonatype JIRA or OSSRH tokens. The Central Portal requires new portal tokens.
5. **GPG key not on a key server** — Generating the key is not enough. It must be uploaded to `keyserver.ubuntu.com`, `keys.openpgp.org`, or `pgp.mit.edu`.
6. **GPG sub-key signing** — If a signing sub-key exists, GPG uses it by default. Maven Central may not verify sub-key signatures. See `references/gpg-details.md`.
7. **Missing checksums in custom integration** — Gradle's `maven-publish` doesn't generate `.md5`/`.sha1` files for the Portal. Custom integrations must generate them explicitly.
8. **Published versions are immutable** — Once a version is on Maven Central, it cannot be changed or deleted. Always test with `-SNAPSHOT` first or use the Portal's manual testing feature.

## Publisher Portal API

For advanced users or custom tooling, the portal offers a REST API. Read `references/portal-api.md` if the user needs to interact with the portal programmatically (uploading bundles, checking deployment status, publishing/dropping deployments).

## Response pattern

1. Determine where the user is in the publishing pipeline
2. Address the immediate blocker or next step
3. Show concrete configuration with their actual values filled in where possible
4. Explain what each piece does (not just "add this XML")
5. Warn about common pitfalls for their specific situation
6. If they're setting up from scratch, walk through stages in order rather than dumping everything at once

## Reference files

- `references/maven-setup.md` — Complete Maven plugin configuration, settings.xml, POM setup
- `references/gradle-setup.md` — Gradle plugin options, configuration for each
- `references/custom-gradle-integration.md` — Zero-dependency Gradle integration using Portal API directly
- `references/ci-cd.md` — GitHub Actions workflow templates for Maven and Gradle
- `references/gpg-details.md` — Advanced GPG topics: sub-keys, key expiry, CI export
- `references/portal-api.md` — REST API for bundle upload, status checks, publish/drop
