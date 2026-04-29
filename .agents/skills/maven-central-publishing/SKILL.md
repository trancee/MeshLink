---
name: maven-central-publishing
description: Complete reference for publishing artifacts to Maven Central via the Sonatype Central Portal (central.sonatype.com). Covers the full end-to-end workflow from account registration through namespace verification to artifact publication. Registration (Central Portal account via Google/GitHub/username+password, social login auto-provisions io.github.<username> namespace). Namespace verification (reverse-DNS domain ownership via TXT record with verification key, or code hosting services — GitHub io.github.<user>, GitLab io.gitlab.<user>, Gitee io.gitee.<user>, Bitbucket io.bitbucket.<user> — verified by creating a temporary public repo named with the verification key). Coordinates (groupId = reverse domain, artifactId = project name lowercase with dashes, version = semver recommended, no -SNAPSHOT for releases). Requirements checklist (POM with groupId/artifactId/version/name/description/url/licenses/developers/scm, sources JAR, javadoc JAR, .md5 and .sha1 checksums for all files, GPG/PGP .asc signatures for all files, public key distributed to keyserver.ubuntu.com or keys.openpgp.org or pgp.mit.edu). GPG setup (gpg --gen-key, gpg --keyserver keyserver.ubuntu.com --send-keys <keyid>, expired key extension via gpg --edit-key). Immutability (once published, artifacts cannot be modified/deleted/updated — ever). Publishing methods — Maven plugin (central-publishing-maven-plugin with autoPublish/waitForPublishCompletion options), Publisher API (REST: POST /api/v1/publisher/upload with multipart bundle, POST /api/v1/publisher/status?id=<id>, POST /api/v1/publisher/deployment/<id> to publish, DELETE to drop; Bearer token auth from base64(username:password)), manual upload (zip bundle following Maven repository layout, up to 1GB), Gradle (no official plugin — community options include vanniktech/gradle-maven-publish-plugin, GradleUp/nmcp, JReleaser, or use OSSRH Staging API). Deployment states (PENDING → VALIDATING → VALIDATED → PUBLISHING → PUBLISHED, or FAILED). Token generation (central.sonatype.com/account → Generate User Token, tokens cannot be retrieved after modal closes). Webhooks (POST with JSON body: deploymentId, status, packageUrls, centralPaths). Use when publishing a library to Maven Central, setting up GPG signing, configuring POM metadata for Central requirements, debugging validation failures, choosing between Maven/Gradle/API publishing methods, verifying a namespace, or any Maven Central publishing question.
---

<essential_principles>

**Maven Central Publishing via the Sonatype Central Portal** — the current way to publish JVM artifacts to Maven Central Repository (repo1.maven.org/maven2).

Portal URL: https://central.sonatype.com

### End-to-End Workflow

```
1. Create account → central.sonatype.com (Google/GitHub/email)
2. Register & verify namespace → proves you own the domain/account
3. Generate user token → for API/CLI authentication
4. Prepare artifacts → POM + JAR + sources + javadoc + checksums + GPG signatures
5. Upload bundle → via Maven plugin, API, or manual upload
6. Validation → Portal checks requirements automatically
7. Publish → manual click or autoPublish=true
8. Immutable on Maven Central → cannot be changed after publication
```

### Namespace Verification

| Method | Namespace Format | Verification |
|--------|-----------------|--------------|
| Domain you own | `com.example` (reverse DNS) | DNS TXT record with verification key |
| GitHub | `io.github.<username>` | Auto-verified if signed up with GitHub; otherwise create temp public repo |
| GitLab | `io.gitlab.<username>` | Create temp public repo with verification key |
| Gitee | `io.gitee.<username>` | Create temp public repo with verification key |
| Bitbucket | `io.bitbucket.<username>` | Create temp public repo with verification key |

Sub-groups are automatic — verifying `com.example` grants access to `com.example.sdk`, `com.example.lib.core`, etc.

### Requirements Checklist

For each artifact, you must provide:

- [ ] **POM** with: groupId, artifactId, version, name, description, url, licenses, developers, scm
- [ ] **Main JAR** (or other packaging artifact)
- [ ] **Sources JAR** (`-sources.jar`)
- [ ] **Javadoc JAR** (`-javadoc.jar`) — placeholder with README.md accepted if no real docs
- [ ] **Checksums** — `.md5` and `.sha1` for every file (`.sha256`/`.sha512` optional)
- [ ] **GPG signatures** — `.asc` for every file (not for checksums themselves)
- [ ] **Public key** distributed to a supported keyserver

### Quick GPG Setup

```bash
gpg --gen-key                                    # generate key pair
gpg --list-keys --keyid-format long              # find your keyid
gpg --keyserver keyserver.ubuntu.com --send-keys <KEYID>  # distribute
gpg -ab myfile.jar                               # sign a file → myfile.jar.asc
```

Supported keyservers: `keyserver.ubuntu.com`, `keys.openpgp.org`, `pgp.mit.edu`

### Authentication Token

1. Log in to https://central.sonatype.com/account
2. Click **Generate User Token** → set name + expiration
3. Save credentials immediately — **cannot be retrieved after modal closes**
4. For API: `Authorization: Bearer <base64(username:password)>`

### Publishing — Fastest Path (Maven)

```xml
<!-- pom.xml -->
<build>
  <plugins>
    <plugin>
      <groupId>org.sonatype.central</groupId>
      <artifactId>central-publishing-maven-plugin</artifactId>
      <version>0.10.0</version>
      <extensions>true</extensions>
      <configuration>
        <publishingServerId>central</publishingServerId>
        <autoPublish>true</autoPublish>
      </configuration>
    </plugin>
  </plugins>
</build>
```

```xml
<!-- ~/.m2/settings.xml -->
<servers>
  <server>
    <id>central</id>
    <username>TOKEN_USERNAME</username>
    <password>TOKEN_PASSWORD</password>
  </server>
</servers>
```

```bash
mvn deploy   # stages, uploads, validates, and publishes
```

### Deployment States

`PENDING` → `VALIDATING` → `VALIDATED` → `PUBLISHING` → `PUBLISHED`

If validation fails → `FAILED` (with error details). Fix locally, re-upload.

**Once PUBLISHED, artifacts are immutable forever.** No updates, no deletes, no modifications.

</essential_principles>

<routing>

| Topic | Reference |
|-------|-----------|
| Full POM requirements (coordinates, name/description/url, licenses, developers, SCM with examples), GPG key management (generation, listing, signing, distributing, expired key renewal, subkey deletion), checksum requirements, file layout, immutability policy | `references/requirements-and-gpg.md` |
| Publisher API (REST endpoints, auth header, upload/status/publish/drop, deployment states, manual testing), Maven plugin (all config options, autoPublish, waitForPublishCompletion), bundle format (Maven repository layout, zip structure, 1GB limit), Gradle community plugins, webhooks (JSON format, status values, curl examples) | `references/publishing-methods.md` |

</routing>

<reference_index>

**requirements-and-gpg.md** — Full POM metadata requirements: correct coordinates (groupId reverse-DNS, artifactId lowercase with dashes, version semver, packaging types jar/war/pom/aar/etc.), name/description/url (required, ${project.groupId}:${project.artifactId} pattern acceptable for name), license block (Apache 2.0 / MIT examples), developer block (name, email, organization, organizationUrl), SCM block (connection, developerConnection, url — private URLs acceptable — examples for GitHub/BitBucket/Subversion/Mercurial), complete example POM. Checksum requirements (.md5 and .sha1 required for every artifact file, .sha256/.sha512 optional, hex-encoded). GPG setup (install gnupg, gpg --gen-key, key expires in 2 years default, gpg --list-keys for keyid, gpg -ab to sign, gpg --keyserver --send-keys to distribute, supported servers keyserver.ubuntu.com/keys.openpgp.org/pgp.mit.edu). Expired key handling (gpg --edit-key → expire → save → redistribute). Multiple keys (gpg --list-signatures --keyid-format 0xshort, use signature keyid in hex format in pom.xml). Javadoc/sources JARs (required for non-pom packaging, placeholder JAR with README.md accepted). Immutability policy (published artifacts cannot be modified, deleted, updated — ever; deployments cleaned up after 90 days).

**publishing-methods.md** — Publisher API endpoints (base: central.sonatype.com/api/v1/publisher): POST /upload (multipart/form-data, bundle field, publishingType AUTOMATIC|USER_MANAGED, returns deployment UUID), POST /status?id= (returns deploymentId, deploymentName, deploymentState enum PENDING/VALIDATING/VALIDATED/PUBLISHING/PUBLISHED/FAILED with errors field, purls array), POST /deployment/<id> (publish a VALIDATED deployment → 204), DELETE /deployment/<id> (drop VALIDATED or FAILED → 204). Auth: Bearer base64(tokenUsername:tokenPassword). Manual testing endpoints (/deployment/<id>/download/<path> and /deployments/download/<path> with Maven settings.xml or Gradle repository config). Maven plugin (central-publishing-maven-plugin v0.10.0, extensions=true, config options: autoPublish bool, waitForPublishCompletion bool, waitMaxTime int minutes, checksums enum all/required, deploymentName, excludeArtifacts, publishingServerId default "central", skipPublishing, stagingDirectory, outputDirectory/outputFilename). Bundle format (zip/tar.gz up to 1GB, Maven repository layout com/example/artifact/version/files, can contain multiple components). Gradle (no official plugin — community: vanniktech/gradle-maven-publish-plugin, GradleUp/nmcp, JReleaser, DanySK/publish-on-central, deepmedia/MavenDeployer, etc. — NOT supported by Sonatype). Webhooks (POST to configured URL, JSON body: deploymentId string UUID, deploymentName string, timestamp long, status VALIDATED|PUBLISHING|PUBLISHED|FAILED, packageUrls string array of PURLs, centralPaths string array of repo1 URLs).

</reference_index>
