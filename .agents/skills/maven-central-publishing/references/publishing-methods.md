# Maven Central Publishing — Publishing Methods Reference

<publisher_api>
## Publisher API (REST)

Base URL: `https://central.sonatype.com/api/v1/publisher`

OpenAPI docs: https://central.sonatype.com/api-doc

### Authentication

Generate a user token at https://central.sonatype.com/account → **Generate User Token**.

**Tokens cannot be retrieved after the modal closes.** Save immediately.

Compute the auth header:

```bash
printf "TOKEN_USERNAME:TOKEN_PASSWORD" | base64
# → ZXhhbXBsZV91c2VybmFtZTpleGFtcGxlX3Bhc3N3b3Jk
```

Use as: `Authorization: Bearer <base64_value>`

### Upload a Bundle

```bash
curl --request POST \
  --header 'Authorization: Bearer <TOKEN>' \
  --form bundle=@central-bundle.zip \
  'https://central.sonatype.com/api/v1/publisher/upload?publishingType=AUTOMATIC'
```

**Parameters:**
- `name` (optional): Human-readable deployment name
- `publishingType`:
  - `AUTOMATIC` — validate + auto-publish if valid
  - `USER_MANAGED` (default) — validate, then wait for manual publish

**Response:** `201 Created` with deployment UUID as plain text body.

### Check Deployment Status

```bash
curl --request POST \
  --header 'Authorization: Bearer <TOKEN>' \
  'https://central.sonatype.com/api/v1/publisher/status?id=<DEPLOYMENT_ID>'
```

**Response JSON:**

```json
{
  "deploymentId": "28570f16-da32-4c14-bd2e-c1acc0782365",
  "deploymentName": "central-bundle.zip",
  "deploymentState": "PUBLISHED",
  "purls": ["pkg:maven/com.example/my-lib@1.0.0"]
}
```

### Deployment States

| State | Meaning |
|-------|---------|
| `PENDING` | Uploaded, waiting for validation service |
| `VALIDATING` | Validation in progress |
| `VALIDATED` | Passed validation, awaiting manual publish (USER_MANAGED) |
| `PUBLISHING` | Being uploaded to Maven Central |
| `PUBLISHED` | Successfully on Maven Central |
| `FAILED` | Error occurred — check `errors` field in response |

### Publish a Validated Deployment

```bash
curl --request POST \
  --header 'Authorization: Bearer <TOKEN>' \
  'https://central.sonatype.com/api/v1/publisher/deployment/<DEPLOYMENT_ID>'
```

Response: `204 No Content`

### Drop a Deployment

Works for `VALIDATED` or `FAILED` deployments:

```bash
curl --request DELETE \
  --header 'Authorization: Bearer <TOKEN>' \
  'https://central.sonatype.com/api/v1/publisher/deployment/<DEPLOYMENT_ID>'
```

Response: `204 No Content`

**Do not drop FAILED deployments if requesting support** — Sonatype needs the files for debugging.

### Manual Testing (Pre-Release Verification)

Download files from a validated (unpublished) deployment:

- Specific deployment: `/api/v1/publisher/deployment/<id>/download/<relativePath>`
- Any validated deployment: `/api/v1/publisher/deployments/download/<relativePath>`

**Maven settings.xml for testing:**

```xml
<servers>
  <server>
    <id>central.testing</id>
    <configuration>
      <httpHeaders>
        <property>
          <name>Authorization</name>
          <value>Bearer <TOKEN></value>
        </property>
      </httpHeaders>
    </configuration>
  </server>
</servers>
<profiles>
  <profile>
    <id>central.testing</id>
    <repositories>
      <repository>
        <id>central.testing</id>
        <url>https://central.sonatype.com/api/v1/publisher/deployments/download</url>
      </repository>
    </repositories>
  </profile>
</profiles>
```

**Gradle for testing:**

```kotlin
repositories {
    maven {
        name = "centralTesting"
        url = uri("https://central.sonatype.com/api/v1/publisher/deployments/download/")
        credentials(HttpHeaderCredentials::class) {
            name = "Authorization"
            value = "Bearer <TOKEN>"
        }
        authentication { create<HttpHeaderAuthentication>("header") }
    }
    mavenCentral()
}
```
</publisher_api>

<maven_plugin>
## Maven Plugin (Official)

```xml
<plugin>
  <groupId>org.sonatype.central</groupId>
  <artifactId>central-publishing-maven-plugin</artifactId>
  <version>0.11.0</version>
  <extensions>true</extensions>
  <configuration>
    <publishingServerId>central</publishingServerId>
    <autoPublish>true</autoPublish>
  </configuration>
</plugin>
```

### Credentials

In `~/.m2/settings.xml`:

```xml
<servers>
  <server>
    <id>central</id>
    <username>TOKEN_USERNAME</username>
    <password>TOKEN_PASSWORD</password>
  </server>
</servers>
```

The `<id>` must match `<publishingServerId>`.

### Usage

```bash
mvn deploy              # stage + upload + validate (+ publish if autoPublish=true)
```

### Configuration Options

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `autoPublish` | boolean | `false` | Auto-publish after validation passes |
| `waitUntil` | string | `validated` | State to block until: `uploaded`, `validated`, or `published`. Replaces the deprecated `waitForPublishCompletion` — use `autoPublish=true` with `waitUntil=published` to block until the artifact is live |
| `waitMaxTime` | int (**seconds**) | `1800` | Max time to wait for `waitUntil`'s target state. Cannot be set below 1800 |
| `waitPollingInterval` | int (seconds) | `5` | Interval between status polls. Cannot be set below 5 |
| `checksums` | string | `all` | `all` (md5+sha1+sha256+sha512), `required` (md5+sha1 only), or `none` (generate no checksums — validation may fail unless another tool supplies them) |
| `failOnBuildFailure` | boolean | `true` | Whether the plugin is allowed to fail the build before uploading/publishing occurs |
| `deploymentName` | string | auto | Human-readable name for the deployment |
| `publishingServerId` | string | `central` | Server ID matching settings.xml |
| `skipPublishing` | boolean | `false` | Skip the publishing step entirely |
| `excludeArtifacts` | string | — | Comma-separated artifactIds to exclude |
| `ignorePublishedComponents` | boolean | `false` | Skip already-published components |
| `stagingDirectory` | path | `central-staging` | Local staging directory |
| `outputDirectory` | path | `central-publishing` | Directory the plugin writes the bundle into |
| `outputFilename` | string | `central-bundle.zip` | Bundle file name |
| `centralBaseUrl` | string | `https://central.sonatype.com` | Portal base URL |
| `centralSnapshotsUrl` | string | — | Snapshots repository URL (falls back to `<distributionManagement>` if unset) |

**Deprecated:** `waitForPublishCompletion` (use `autoPublish` + `waitUntil` instead) and `publishCompletionPollInterval` (use `waitPollingInterval` instead). Both remain accepted but should not be used in new configs.

### Automatic Publishing, Blocking Until Live

```xml
<configuration>
  <autoPublish>true</autoPublish>
  <waitUntil>published</waitUntil>
  <waitMaxTime>1800</waitMaxTime>
</configuration>
```

This deploys, validates, publishes, and blocks until the artifact appears on Maven Central or `waitMaxTime` (seconds) expires.
</maven_plugin>

<bundle_format>
## Bundle Format (Manual Upload)

A deployment bundle is a zip (or tar.gz) following the **Maven Repository Layout**:

```
com/
  example/
    my-library/
      1.0.0/
        my-library-1.0.0.jar
        my-library-1.0.0.jar.asc
        my-library-1.0.0.jar.md5
        my-library-1.0.0.jar.sha1
        my-library-1.0.0.pom
        my-library-1.0.0.pom.asc
        my-library-1.0.0.pom.md5
        my-library-1.0.0.pom.sha1
        my-library-1.0.0-sources.jar
        my-library-1.0.0-sources.jar.asc
        my-library-1.0.0-sources.jar.md5
        my-library-1.0.0-sources.jar.sha1
        my-library-1.0.0-javadoc.jar
        my-library-1.0.0-javadoc.jar.asc
        my-library-1.0.0-javadoc.jar.md5
        my-library-1.0.0-javadoc.jar.sha1
```

- **Max size:** 1GB
- **Multiple components:** A single bundle can contain multiple artifacts
- **Upload methods:** Portal UI drag-and-drop or Publisher API
- **Supported formats:** zip, tar.gz

### Creating a Bundle Manually

```bash
# Assuming files are in the correct directory structure
cd staging-directory
zip -r ../central-bundle.zip .

# Upload via API
curl --request POST \
  --header 'Authorization: Bearer <TOKEN>' \
  --form bundle=@central-bundle.zip \
  'https://central.sonatype.com/api/v1/publisher/upload?publishingType=AUTOMATIC'
```
</bundle_format>

<gradle_publishing>
## Gradle Publishing

**No official Sonatype Gradle plugin exists.** Use community plugins or the OSSRH Staging API.

### Recommended Community Plugins

| Plugin | Notes |
|--------|-------|
| [JReleaser](https://jreleaser.org/) | Full release automation; has direct, actively maintained support for the Central Publisher Portal API |
| [vanniktech/gradle-maven-publish-plugin](https://github.com/vanniktech/gradle-maven-publish-plugin/) | Popular, well-maintained |
| [GradleUp/nmcp](https://github.com/GradleUp/nmcp) | Lightweight Central Portal integration |
| [DanySK/publish-on-central](https://github.com/DanySK/publish-on-central) | Straightforward Central publishing |
| [deepmedia/MavenDeployer](https://github.com/deepmedia/MavenDeployer) | Multi-target deployer |
| [SgtSilvio/gradle-maven-central-publishing](https://github.com/SgtSilvio/gradle-maven-central-publishing) | Central Portal-focused |
| [lukebemishprojects/CentralPortalPublishing](https://github.com/lukebemishprojects/CentralPortalPublishing) | Central Portal-focused |

Sonatype's own list is longer and growing (`ani2fun/sonatype-maven-central-publisher`, `Im-Fran/SonatypeCentralUpload`, `Karlatemp/maven-central-publish`, `kernelflux/maven-central-gradle-plugin`, `lalakii/central-portal-plus`, `medivh-project/medivh-publisher`, `moengage/gradle-maven-publish-plugin`, `pkmer/pkmerboot-central-publisher`, `tafilovic/central-portal-publisher`, `tddworks/central-portal-publisher`, `thebugmc/sonatype-central-portal-publisher`, `yananhub/flying-gradle-plugin`, `ysb33rOrg/nempi-mavencentral-publishing-plugin`) — check https://central.sonatype.org/publish/publish-portal-gradle/ for the current roster before picking one.

**All community plugins are NOT supported by Sonatype**, including JReleaser. Do not file Sonatype support tickets for them. Sonatype has stated Gradle support is on their roadmap but there is still no official plugin.

### Alternative: OSSRH Staging API

If you currently publish via OSSRH (Nexus staging), the [Portal OSSRH Staging API](https://central.sonatype.org/publish/publish-portal-ossrh-staging-api/) provides backward-compatible endpoints so existing Gradle plugins continue to work.
</gradle_publishing>

<webhooks>
## Webhooks

Configure at central.sonatype.com → username menu → **View Webhooks**.

The Portal sends a POST request to your webhook URL after deployment events.

### JSON Body

```json
{
  "deploymentId": "abcdefgh-1234-abcd-1234-abcdefghijkl",
  "deploymentName": "my-library-1.0.0",
  "timestamp": 1710000000000,
  "status": "PUBLISHED",
  "packageUrls": [
    "pkg:maven/com.example/my-library@1.0.0"
  ],
  "centralPaths": [
    "https://repo1.maven.org/maven2/com/example/my-library/1.0.0/my-library-1.0.0.pom"
  ]
}
```

### Status Values

| Status | Meaning |
|--------|---------|
| `VALIDATED` | Passed validation (not sent when autoPublish is enabled) |
| `PUBLISHING` | Publication in progress |
| `PUBLISHED` | Successfully on Maven Central (may be duplicated or missing) |
| `FAILED` | Validation or publishing failed |

### Known Issues

- `VALIDATED` notification is **not sent** when `autoPublish` is enabled
- `PUBLISHED` notification may be **duplicated or missing** depending on conditions
</webhooks>
