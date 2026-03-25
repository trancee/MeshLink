# Custom Gradle Integration (No Third-Party Plugins)

Publish to Maven Central from Gradle using only built-in Gradle plugins + custom tasks that call the Central Portal API directly. This avoids any dependency on community publishing plugins.

This approach mirrors what `vanniktech/gradle-maven-publish-plugin` does under the hood: use Gradle's built-in `maven-publish` and `signing` plugins to generate signed artifacts, bundle them into a zip following Maven repository layout, and upload via the Central Portal REST API.

## Table of Contents

- [Architecture overview](#architecture-overview)
- [Complete build.gradle.kts](#complete-buildgradlekts)
- [The publishToMavenCentral task](#the-publishtomavencentral-task)
- [How it works step by step](#how-it-works-step-by-step)
- [CI/CD integration](#cicd-integration)
- [Multi-module projects](#multi-module-projects)
- [Customization points](#customization-points)
- [Troubleshooting](#troubleshooting)

## Architecture overview

The flow uses three layers:

1. **Gradle built-in `maven-publish`** → generates POM, stages artifacts to a local directory
2. **Gradle built-in `signing`** → signs all artifacts with GPG
3. **Custom task** → bundles the staged directory into a zip, uploads it via the Portal API, polls for status

No community plugins. The only external dependency is the HTTP call to `central.sonatype.com`.

## Complete build.gradle.kts

```kotlin
import java.io.FileInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

plugins {
    `java-library`  // or kotlin("jvm"), etc.
    `maven-publish`
    signing
}

group = "com.example"
version = "1.0.0"

java {
    withSourcesJar()
    withJavadocJar()
}

// ── POM metadata (required by Maven Central) ───────────────────────

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])

            pom {
                name.set("My Library")
                description.set("A library that does useful things")
                url.set("https://github.com/username/my-library")

                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }

                developers {
                    developer {
                        name.set("Your Name")
                        email.set("you@example.com")
                    }
                }

                scm {
                    connection.set("scm:git:git://github.com/username/my-library.git")
                    developerConnection.set("scm:git:ssh://github.com:username/my-library.git")
                    url.set("https://github.com/username/my-library/tree/main")
                }
            }
        }
    }

    // Publish to a local staging directory (not a remote repo)
    repositories {
        maven {
            name = "staging"
            url = uri(layout.buildDirectory.dir("staging-deploy"))
        }
    }
}

// ── Signing ────────────────────────────────────────────────────────

signing {
    // For local development: uses ~/.gnupg
    // For CI: use in-memory key via environment variables
    val signingKey: String? by project
    val signingPassword: String? by project
    if (signingKey != null) {
        useInMemoryPgpKeys(signingKey, signingPassword)
    }
    sign(publishing.publications["mavenJava"])
}

// ── Custom publish task ────────────────────────────────────────────

tasks.register("publishToMavenCentral") {
    description = "Publish artifacts to Maven Central via the Portal API"
    group = "publishing"

    dependsOn("publishMavenJavaPublicationToStagingRepository")

    doLast {
        val stagingDir = layout.buildDirectory.dir("staging-deploy").get().asFile
        val bundleFile = layout.buildDirectory.file("central-bundle.zip").get().asFile

        val username = providers.environmentVariable("MAVEN_CENTRAL_USERNAME")
            .orElse(providers.gradleProperty("mavenCentralUsername"))
            .orNull ?: error("MAVEN_CENTRAL_USERNAME not set")
        val password = providers.environmentVariable("MAVEN_CENTRAL_PASSWORD")
            .orElse(providers.gradleProperty("mavenCentralPassword"))
            .orNull ?: error("MAVEN_CENTRAL_PASSWORD not set")

        val autoPublish = providers.environmentVariable("MAVEN_CENTRAL_AUTO_PUBLISH")
            .orElse(providers.gradleProperty("mavenCentralAutoPublish"))
            .orElse("false")
            .get().toBoolean()

        // Step 1: Generate checksums for all artifact files
        logger.lifecycle("Generating checksums...")
        generateChecksums(stagingDir)

        // Step 2: Bundle the staging directory into a zip
        logger.lifecycle("Creating bundle: ${bundleFile.name}")
        createBundle(stagingDir, bundleFile)

        // Step 3: Upload the bundle to the Central Portal
        val token = Base64.getEncoder().encodeToString("$username:$password".toByteArray())
        val publishingType = if (autoPublish) "AUTOMATIC" else "USER_MANAGED"
        val deploymentName = "${project.group}:${project.name}:${project.version}"

        logger.lifecycle("Uploading bundle to Central Portal...")
        val deploymentId = uploadBundle(bundleFile, token, publishingType, deploymentName)
        logger.lifecycle("Upload complete. Deployment ID: $deploymentId")

        // Step 4: Poll for validation status
        logger.lifecycle("Waiting for validation...")
        waitForValidation(deploymentId, token, waitForPublishing = autoPublish)

        if (autoPublish) {
            logger.lifecycle("Deployment published to Maven Central!")
        } else {
            logger.lifecycle("Deployment validated. Publish manually at https://central.sonatype.com/publishing/deployments")
        }

        // Cleanup
        bundleFile.delete()
    }
}

// ── Helper functions ───────────────────────────────────────────────

fun generateChecksums(dir: File) {
    dir.walkTopDown()
        .filter { it.isFile && !it.name.endsWith(".md5") && !it.name.endsWith(".sha1")
            && !it.name.endsWith(".sha256") && !it.name.endsWith(".sha512")
            && !it.name.startsWith("maven-metadata") }
        .forEach { file ->
            writeChecksum(file, "MD5", ".md5")
            writeChecksum(file, "SHA-1", ".sha1")
        }
}

fun writeChecksum(file: File, algorithm: String, extension: String) {
    val digest = MessageDigest.getInstance(algorithm)
    file.inputStream().use { input ->
        val buffer = ByteArray(8192)
        var bytesRead: Int
        while (input.read(buffer).also { bytesRead = it } != -1) {
            digest.update(buffer, 0, bytesRead)
        }
    }
    val hex = digest.digest().joinToString("") { "%02x".format(it) }
    File(file.absolutePath + extension).writeText(hex)
}

fun createBundle(stagingDir: File, outputFile: File) {
    ZipOutputStream(outputFile.outputStream()).use { zos ->
        stagingDir.walkTopDown()
            .filter { it.isFile && !it.name.startsWith("maven-metadata") }
            .forEach { file ->
                val entryPath = file.relativeTo(stagingDir).path
                zos.putNextEntry(ZipEntry(entryPath))
                file.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
    }
}

fun uploadBundle(bundleFile: File, token: String, publishingType: String, name: String): String {
    val boundary = "----FormBoundary${System.currentTimeMillis()}"
    val body = buildMultipartBody(boundary, bundleFile)

    val request = HttpRequest.newBuilder()
        .uri(URI.create(
            "https://central.sonatype.com/api/v1/publisher/upload" +
            "?publishingType=$publishingType" +
            "&name=${java.net.URLEncoder.encode(name, Charsets.UTF_8)}"
        ))
        .header("Authorization", "Bearer $token")
        .header("Content-Type", "multipart/form-data; boundary=$boundary")
        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
        .build()

    val client = HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(60))
        .build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofString())

    if (response.statusCode() != 201) {
        error("Upload failed (${response.statusCode()}): ${response.body()}")
    }

    return response.body().trim()
}

fun buildMultipartBody(boundary: String, file: File): ByteArray {
    val output = java.io.ByteArrayOutputStream()

    // Part header
    output.write("--$boundary\r\n".toByteArray())
    output.write("Content-Disposition: form-data; name=\"bundle\"; filename=\"${file.name}\"\r\n".toByteArray())
    output.write("Content-Type: application/octet-stream\r\n".toByteArray())
    output.write("\r\n".toByteArray()) // blank line between headers and body

    // Part body
    FileInputStream(file).use { it.copyTo(output) }

    // Closing boundary
    output.write("\r\n".toByteArray())
    output.write("--$boundary--\r\n".toByteArray())

    return output.toByteArray()
}

fun waitForValidation(deploymentId: String, token: String, waitForPublishing: Boolean) {
    val client = HttpClient.newHttpClient()
    val maxWaitMs = 30 * 60 * 1000L  // 30 minutes
    val pollIntervalMs = 5_000L
    val start = System.currentTimeMillis()

    while (System.currentTimeMillis() - start < maxWaitMs) {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://central.sonatype.com/api/v1/publisher/status?id=$deploymentId"))
            .header("Authorization", "Bearer $token")
            .POST(HttpRequest.BodyPublishers.noBody())
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            error("Status check failed (${response.statusCode()}): ${response.body()}")
        }

        val body = response.body()
        // Parse deploymentState from JSON response without external dependencies.
        // The Portal API always returns {"deploymentId":"...","deploymentState":"...","purls":[...]}.
        val state = Regex("\"deploymentState\"\\s*:\\s*\"([A-Z_]+)\"").find(body)?.groupValues?.get(1)
        if (state == null) {
            logger.warn("Could not parse deploymentState from response: $body")
            Thread.sleep(pollIntervalMs)
            continue
        }

        when (state) {
            "PENDING", "VALIDATING" -> {
                logger.lifecycle("  Status: $state ...")
            }
            "VALIDATED" -> {
                if (!waitForPublishing) return
                logger.lifecycle("  Status: VALIDATED, waiting for publishing...")
            }
            "PUBLISHING" -> {
                logger.lifecycle("  Status: PUBLISHING...")
            }
            "PUBLISHED" -> {
                return
            }
            "FAILED" -> {
                // Extract error details if present
                val errors = Regex("\"errors\"\\s*:\\s*\\{([^}]+)\\}").find(body)?.groupValues?.get(1) ?: ""
                val errorMsg = if (errors.isNotBlank()) "\nValidation errors:\n$errors" else ""
                error("Deployment FAILED.$errorMsg\nFull response: $body\nCheck https://central.sonatype.com/publishing/deployments for details.")
            }
            else -> logger.warn("  Unknown state: $state")
        }

        Thread.sleep(pollIntervalMs)
    }

    error("Timed out waiting for deployment validation after ${maxWaitMs / 1000}s")
}
```

## How it works step by step

1. **`publishMavenJavaPublicationToStagingRepository`** (built-in Gradle task)
   - Generates the POM from the `pom {}` block
   - Copies the main JAR, sources JAR, and javadoc JAR to `build/staging-deploy/`
   - Signs all artifacts (`.asc` files) via the `signing` plugin
   - Writes them in Maven repository layout: `com/example/my-library/1.0.0/...`

2. **`generateChecksums()`** (custom)
   - Walks the staging directory
   - Generates `.md5` and `.sha1` files for every artifact (required by Maven Central)
   - Skips existing checksum files and `maven-metadata.xml`

3. **`createBundle()`** (custom)
   - Zips the entire staging directory into `central-bundle.zip`
   - Preserves the Maven repository layout paths inside the zip

4. **`uploadBundle()`** (custom)
   - Sends a multipart POST to `https://central.sonatype.com/api/v1/publisher/upload`
   - Returns the deployment ID (UUID)

5. **`waitForValidation()`** (custom)
   - Polls `https://central.sonatype.com/api/v1/publisher/status?id={deploymentId}`
   - Logs state transitions: PENDING → VALIDATING → VALIDATED → PUBLISHING → PUBLISHED
   - Throws on FAILED with error details

## CI/CD integration

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

      - uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'

      - uses: gradle/actions/setup-gradle@v4

      - name: Publish to Maven Central
        run: ./gradlew publishToMavenCentral
        env:
          MAVEN_CENTRAL_USERNAME: ${{ secrets.MAVEN_CENTRAL_USERNAME }}
          MAVEN_CENTRAL_PASSWORD: ${{ secrets.MAVEN_CENTRAL_PASSWORD }}
          MAVEN_CENTRAL_AUTO_PUBLISH: 'true'
          ORG_GRADLE_PROJECT_signingKey: ${{ secrets.GPG_PRIVATE_KEY }}
          ORG_GRADLE_PROJECT_signingPassword: ${{ secrets.GPG_PASSPHRASE }}
```

## Multi-module projects

For multi-module builds, there are two approaches:

### Option A: Convention plugin (per-module upload)

Extract the custom task into a convention plugin so each module uploads independently:

**buildSrc/src/main/kotlin/maven-central-publish.gradle.kts:**
```kotlin
// Move the publishing, signing, and publishToMavenCentral task config here
// Each module gets its own publishToMavenCentral task
plugins {
    id("maven-central-publish")
}
```

### Option B: Aggregated upload (single zip)

Create a root-level task that bundles ALL modules into one zip upload. This is more efficient (one API call, one validation cycle) but requires aggregating all staging directories:

```kotlin
// In the root build.gradle.kts
tasks.register("publishAllToMavenCentral") {
    // Depend on all subproject publish-to-local tasks
    dependsOn(subprojects.filter { it.plugins.hasPlugin("maven-publish") }
        .map { "${it.path}:publishAllPublicationsToStagingRepository" })

    doLast {
        // Collect all staging dirs into one zip
        val allStagingDirs = subprojects.mapNotNull { sub ->
            val dir = sub.layout.buildDirectory.dir("staging-deploy").get().asFile
            if (dir.exists()) dir else null
        }
        // Bundle, upload, poll — same logic as single-module task
    }
}
```

Option B is recommended for projects with many modules since it reduces API calls and keeps all artifacts in one deployment.

## Customization points

| What | How |
|------|-----|
| Auto-publish | Set `MAVEN_CENTRAL_AUTO_PUBLISH=true` or `mavenCentralAutoPublish=true` in gradle.properties |
| Polling interval | Change `pollIntervalMs` in `waitForValidation()` |
| Timeout | Change `maxWaitMs` in `waitForValidation()` |
| Additional checksums | Add SHA-256/SHA-512 in `generateChecksums()` (optional but recommended) |
| Skip metadata files | The `maven-metadata*.xml` filter prevents local Gradle metadata from being included |
| Custom deployment name | Modify the `deploymentName` variable in the task |

## When to use this approach

**Choose custom integration when:**
- You want zero third-party publishing dependencies
- You need full control over the upload flow
- Your organization restricts community plugins
- You want to understand exactly what's happening

**Choose a community plugin when:**
- You want a battle-tested, maintained solution
- You don't want to maintain the HTTP/multipart/polling logic
- You need features like automatic Kotlin Multiplatform support

## Troubleshooting

| Problem | Cause | Fix |
|---------|-------|-----|
| `403 Forbidden` on upload | Token doesn't have publish permissions, or namespace not verified | Regenerate token at central.sonatype.com/usertoken; verify namespace ownership |
| `401 Unauthorized` | Token expired or malformed | Tokens are `username:password` base64-encoded. Ensure `MAVEN_CENTRAL_TOKEN` is the full `Basic <base64>` header value |
| Multipart upload rejected | Malformed HTTP body (missing boundary, wrong Content-Type) | Ensure the boundary string in the Content-Type header matches the one in the body. Check for extra whitespace. |
| Validation fails: missing checksums | `generateChecksums()` didn't run or didn't cover all files | The task generates `.md5` and `.sha1` for every file. Verify the staging directory has checksum files before zipping. |
| Validation fails: invalid signature | Wrong GPG key, or signing used a sub-key that's not on key servers | Use `gpg --list-keys --keyid-format long` to check which key is signing. Upload the exact key to `keyserver.ubuntu.com`. |
| `FAILED` status with no error details | API returned an error but the task couldn't parse it | Check the raw JSON response. The `errors` field in the status response contains details. |
| Timeout waiting for validation | Large bundle or portal backlog | Increase `maxWaitMs`. Bundles under 100MB typically validate in under 2 minutes. |
| `maven-metadata*.xml` in bundle | Gradle's `maven-publish` generates local metadata | The zip task already filters these out. If you customized the task, ensure the filter is in place. |
