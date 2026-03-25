# Publisher Portal API

REST API for programmatic interaction with the Central Publisher Portal. Use this for custom tooling, scripts, or when you need more control than build plugins provide.

## Table of Contents

- [Authentication](#authentication)
- [Upload a bundle](#upload-a-bundle)
- [Check deployment status](#check-deployment-status)
- [Publish a deployment](#publish-a-deployment)
- [Drop a deployment](#drop-a-deployment)
- [Test a deployment before publishing](#test-a-deployment-before-publishing)

## Authentication

All API requests require a Bearer token derived from your portal user token:

```bash
TOKEN=$(printf "token_username:token_password" | base64)
```

Use it in the `Authorization` header:
```
Authorization: Bearer <base64-encoded-token>
```

## Upload a bundle

Upload a zip bundle to the portal for validation:

```bash
curl --request POST \
  --header "Authorization: Bearer ${TOKEN}" \
  --form bundle=@central-bundle.zip \
  "https://central.sonatype.com/api/v1/publisher/upload?publishingType=AUTOMATIC&name=my-library-1.0.0"
```

**Query parameters:**
- `name` (optional): Human-readable deployment name
- `publishingType` (optional):
  - `USER_MANAGED` (default): Requires manual publish via portal UI
  - `AUTOMATIC`: Auto-publishes to Maven Central after validation passes

**Response:** The deployment ID (UUID string), e.g., `28570f16-da32-4c14-bd2e-c1acc0782365`

### Bundle format

The bundle is a zip file with Maven repository layout:

```
com/
  example/
    my-library/
      1.0.0/
        my-library-1.0.0.pom
        my-library-1.0.0.pom.asc
        my-library-1.0.0.pom.md5
        my-library-1.0.0.pom.sha1
        my-library-1.0.0.jar
        my-library-1.0.0.jar.asc
        my-library-1.0.0.jar.md5
        my-library-1.0.0.jar.sha1
        my-library-1.0.0-sources.jar
        my-library-1.0.0-sources.jar.asc
        my-library-1.0.0-sources.jar.md5
        my-library-1.0.0-sources.jar.sha1
        my-library-1.0.0-javadoc.jar
        my-library-1.0.0-javadoc.jar.asc
        my-library-1.0.0-javadoc.jar.md5
        my-library-1.0.0-javadoc.jar.sha1
```

The bundle can contain multiple components and can be up to 1GB.

## Check deployment status

```bash
curl --request POST \
  --header "Authorization: Bearer ${TOKEN}" \
  "https://central.sonatype.com/api/v1/publisher/status?id=${DEPLOYMENT_ID}" \
  | jq
```

**Response:**
```json
{
  "deploymentId": "28570f16-da32-4c14-bd2e-c1acc0782365",
  "deploymentName": "my-library-1.0.0",
  "deploymentState": "PUBLISHED",
  "purls": [
    "pkg:maven/com.example/my-library@1.0.0"
  ]
}
```

**Deployment states:**
- `PENDING` — Uploaded, waiting for processing
- `VALIDATING` — Being processed by validation
- `VALIDATED` — Passed validation, waiting for manual publish
- `PUBLISHING` — Being uploaded to Maven Central
- `PUBLISHED` — Available on Maven Central
- `FAILED` — Error occurred (check `errors` field for details)

## Publish a deployment

After a deployment reaches `VALIDATED` state, publish it:

```bash
curl --request POST \
  --header "Authorization: Bearer ${TOKEN}" \
  "https://central.sonatype.com/api/v1/publisher/deployment/${DEPLOYMENT_ID}"
```

Returns HTTP 204 on success.

## Drop a deployment

Drop a `VALIDATED` or `FAILED` deployment to clean up:

```bash
curl --request DELETE \
  --header "Authorization: Bearer ${TOKEN}" \
  "https://central.sonatype.com/api/v1/publisher/deployment/${DEPLOYMENT_ID}"
```

Returns HTTP 204 on success. Don't drop `FAILED` deployments if you're contacting support — the files are useful for debugging.

## Test a deployment before publishing

After validation, you can use the deployment as a Maven/Gradle repository for testing before publishing to Central. This is useful for CI workflows where you build a release, validate it, then run integration tests against the validated artifacts before actually publishing.

The base URL for fetching validated artifacts is:
```
https://central.sonatype.com/api/v1/publisher/deployments/download/
```

**Maven settings.xml:**
```xml
<servers>
    <server>
        <id>central.manual.testing</id>
        <configuration>
            <httpHeaders>
                <property>
                    <name>Authorization</name>
                    <value>Bearer ${TOKEN}</value>
                </property>
            </httpHeaders>
        </configuration>
    </server>
</servers>

<profiles>
    <profile>
        <id>central.manual.testing</id>
        <repositories>
            <repository>
                <id>central.manual.testing</id>
                <url>https://central.sonatype.com/api/v1/publisher/deployments/download/</url>
            </repository>
        </repositories>
    </profile>
</profiles>
```

Use with: `mvn <command> -Pcentral.manual.testing`

**Gradle:**
```kotlin
repositories {
    maven {
        name = "centralManualTesting"
        url = uri("https://central.sonatype.com/api/v1/publisher/deployments/download/")
        credentials(HttpHeaderCredentials::class)
        authentication {
            create<HttpHeaderAuthentication>("header")
        }
    }
    mavenCentral()
}
```

With `gradle.properties`:
```properties
centralManualTestingAuthHeaderName=Authorization
centralManualTestingAuthHeaderValue=Bearer <token>
```
